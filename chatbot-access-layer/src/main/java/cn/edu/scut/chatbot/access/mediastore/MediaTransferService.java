package cn.edu.scut.chatbot.access.mediastore;

import cn.edu.scut.chatbot.access.infrastructure.RetryableException;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.contract.message.AudioPart;
import cn.edu.scut.chatbot.contract.message.ImagePart;
import cn.edu.scut.chatbot.contract.message.MediaTransferStatusCodes;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.VideoPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 媒体转存服务（data-model.md 第 3 节转存时序）：事件入流前对图片 / 语音 / 视频段做
 * <b>有界同步转存尝试</b>——QQ 媒体 URL 携带 rkey 约两小时过期（research R2），
 * 收到就要尽快转存，囤积 URL 即丢失。
 *
 * <p>三分支语义（事件一次成型是 Agent 幂等契约的必然要求，不用「追加媒体更新事件」方案）：
 * <ul>
 *   <li>限时内成功 → transferStatus=transferred + storedRef，一次成型入流</li>
 *   <li>超时或失败 → failed + 原 URL 立即入流不阻塞主流程；后台虚拟线程继续完成下载补档，
 *       补档只落存储卷、不追发事件（同 dedupKey 追加事件会被 Agent 幂等层吞掉）</li>
 *   <li>无 URL / 已 failed 的段原样保留</li>
 * </ul>
 * 下载动作跑在媒体下载执行器（每任务一条虚拟线程）上，不占平台应答的关键路径；
 * 限时等待用注入 Clock 的 deadline 计算（固定时钟下限时分支可测）。
 */
@Component
public class MediaTransferService {

    private static final Logger log = LoggerFactory.getLogger(MediaTransferService.class);

    private final SafeFetcher safeFetcher;
    private final ExecutorService mediaDownloadExecutor;
    private final Clock clock;
    private final long transferTimeoutMillis;
    private final Path storeRoot;

    public MediaTransferService(SafeFetcher safeFetcher,
                                @Qualifier("mediaDownloadExecutor") ExecutorService mediaDownloadExecutor,
                                Clock clock,
                                @Value("${access.media.transfer-timeout-millis:3000}") long transferTimeoutMillis,
                                @Value("${access.media.store-root:./data/media}") String storeRoot) {
        this.safeFetcher = safeFetcher;
        this.mediaDownloadExecutor = mediaDownloadExecutor;
        this.clock = clock;
        this.transferTimeoutMillis = transferTimeoutMillis;
        this.storeRoot = Path.of(storeRoot);
    }

    /**
     * 入方向：对段列表中 pending 状态的媒体段做有界同步转存尝试，返回替换后的完整段列表。
     * 非媒体段与非 pending 段原样保留（列表其余顺序不变）。
     */
    public List<MessagePart> transferInbound(List<MessagePart> parts) {
        List<MessagePart> transferred = new ArrayList<>(parts.size());
        for (MessagePart part : parts) {
            transferred.add(transferPart(part));
        }
        return transferred;
    }

    private MessagePart transferPart(MessagePart part) {
        String originalUrl = originalUrlOf(part);
        if (originalUrl == null || originalUrl.isBlank()) {
            return part;
        }
        String status = mediaStatus(part);
        if (!MediaTransferStatusCodes.PENDING.equals(status)) {
            return part;
        }

        long deadlineMillis = clock.millis() + transferTimeoutMillis;
        Future<String> download = mediaDownloadExecutor.submit(() -> downloadAndStore(originalUrl));
        String storedRef;
        try {
            long remainingMillis = Math.max(1, deadlineMillis - clock.millis());
            storedRef = download.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            // 超时分支：段带 failed 与原 URL 立即入流；下载继续跑完只落卷补档（不追发事件）
            scheduleBackgroundCompletion(download, originalUrl);
            return withTransferStatus(part, MediaTransferStatusCodes.FAILED, null);
        } catch (Exception e) {
            log.warn("媒体转存失败（{}）：{}，段以 failed 入流", ErrorCodes.ADAPTER_MEDIA_FETCH_FAILED, e.getMessage());
            return withTransferStatus(part, MediaTransferStatusCodes.FAILED, null);
        }
        return withTransferStatus(part, MediaTransferStatusCodes.TRANSFERRED, storedRef);
    }

    /**
     * 超时后的补档：下载动作完成后把结果落进存储卷（丢给虚拟线程，完成与否都不影响已入流事件）
     */
    private void scheduleBackgroundCompletion(Future<String> download, String url) {
        mediaDownloadExecutor.submit(() -> {
            try {
                // download.get() 无限等待后台剩余下载——补档语义：能落一点是一点
                download.get();
            } catch (Exception e) {
                log.info("超时媒体的补档下载最终失败（仅记录，不影响已入流事件）：{}",
                        e.getMessage() == null ? url : e.getMessage());
            }
        });
    }

    /**
     * 下载并写入存储卷，返回 storedRef。
     * 文件名用内容 SHA-256 十六进制（内容寻址：重推同一文件不重复占空间），两级目录分片。
     */
    String downloadAndStore(String url) {
        SafeFetcher.FetchedMedia media = safeFetcher.fetch(url);
        String hash = sha256Hex(media.body());
        String extension = extensionOf(media.contentType(), url);
        Path target = storeRoot.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4))
                .resolve(hash + extension);
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.write(target, media.body());
            }
        } catch (IOException e) {
            throw new RetryableException(ErrorCodes.ADAPTER_MEDIA_FETCH_FAILED,
                    "媒体写入存储卷失败：" + target, e);
        }
        return target.toString();
    }

    private String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    private String extensionOf(String contentType, String url) {
        String subtype = contentType == null ? "" : contentType.toLowerCase();
        if (subtype.startsWith("image/")) {
            return "." + (subtype.endsWith("jpeg") ? "jpg" : subtype.substring(subtype.indexOf('/') + 1));
        }
        if (subtype.startsWith("audio/")) {
            return "." + subtype.substring(subtype.indexOf('/') + 1);
        }
        if (subtype.startsWith("video/")) {
            return "." + subtype.substring(subtype.indexOf('/') + 1);
        }
        // URL 路径里带短扩展名就用它（octet-stream 常见于 QQ 的无 Content-Type 直链）
        int lastSlash = url.lastIndexOf('/');
        int lastDot = url.lastIndexOf('.');
        if (lastSlash >= 0 && lastDot > lastSlash && url.length() - lastDot <= 5) {
            return url.substring(lastDot);
        }
        return ".bin";
    }

    /**
     * 三种媒体段的原样取 originalUrl（非媒体段返回 null）
     */
    private String originalUrlOf(MessagePart part) {
        if (part instanceof ImagePart image) {
            return image.originalUrl();
        }
        if (part instanceof AudioPart audio) {
            return audio.originalUrl();
        }
        if (part instanceof VideoPart video) {
            return video.originalUrl();
        }
        return null;
    }

    private String mediaStatus(MessagePart part) {
        if (part instanceof ImagePart image) {
            return image.transferStatus();
        }
        if (part instanceof AudioPart audio) {
            return audio.transferStatus();
        }
        if (part instanceof VideoPart video) {
            return video.transferStatus();
        }
        return null;
    }

    /**
     * 以新转存状态重建段 record（record 不可变，替换即重建；字段其余部分原样保留）
     */
    private MessagePart withTransferStatus(MessagePart part, String status, String storedRef) {
        if (part instanceof ImagePart image) {
            return new ImagePart(ImagePart.TYPE_IMAGE, image.originalUrl(),
                    MediaTransferStatusCodes.TRANSFERRED.equals(status) ? storedRef : null,
                    status, image.fileName(), image.fileSize());
        }
        if (part instanceof AudioPart audio) {
            return new AudioPart(AudioPart.TYPE_AUDIO, audio.originalUrl(),
                    MediaTransferStatusCodes.TRANSFERRED.equals(status) ? storedRef : null,
                    status, audio.fileName(), audio.fileSize());
        }
        VideoPart video = (VideoPart) part;
        return new VideoPart(VideoPart.TYPE_VIDEO, video.originalUrl(),
                MediaTransferStatusCodes.TRANSFERRED.equals(status) ? storedRef : null,
                status, video.fileName(), video.fileSize());
    }
}
