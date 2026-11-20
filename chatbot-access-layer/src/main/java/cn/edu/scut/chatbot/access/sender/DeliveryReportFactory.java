package cn.edu.scut.chatbot.access.sender;

import cn.edu.scut.chatbot.access.bootstrap.SnowflakeIdGenerator;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.DeliveryFailureKindCodes;
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload;
import cn.edu.scut.chatbot.contract.in.DeliveryStatusCodes;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

/**
 * 投递回执工厂（data-model.md 第 6 节）：出事件投递的唯一反馈通道——每次发送（含失败、
 * 不支持）都产出 DeliveryReport 作为入事件回流，无「发出即忘」路径（宪法第三条）。
 *
 * <p>回执是事实登记（过去式）：一次投递一条回执，pending 重投产生第二条同 outboxId 回执，
 * Agent 侧按 (outboxId + attemptedAt) 取最新。attemptedAt 由注入 Clock 打点。
 */
@Component
public class DeliveryReportFactory {

    private final SnowflakeIdGenerator idGenerator;
    private final Clock clock;

    public DeliveryReportFactory(SnowflakeIdGenerator idGenerator, Clock clock) {
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 成功回执：platformMessageId 非空
     */
    public DeliveryReportPayload success(String outboxId, String platformMessageId) {
        return new DeliveryReportPayload(outboxId, platformMessageId, DeliveryStatusCodes.SUCCESS,
                null, null, null, clock.millis(), false);
    }

    /**
     * 失败回执：failureKind 取值见 DeliveryFailureKindCodes；detail 截断至 512 字符
     */
    public DeliveryReportPayload failed(String outboxId, String failureKind, String failureDetail, boolean retried) {
        return new DeliveryReportPayload(outboxId, null, DeliveryStatusCodes.FAILED,
                failureKind, truncate(failureDetail), null, clock.millis(), retried);
    }

    /**
     * 不支持回执：能力探测未过 / 协议端明确无此 API——不算 failed
     */
    public DeliveryReportPayload unsupported(String outboxId, String detail) {
        return new DeliveryReportPayload(outboxId, null, DeliveryStatusCodes.UNSUPPORTED,
                null, truncate(detail), null, clock.millis(), false);
    }

    /**
     * 组装回执入信封（type=delivery_report，traceContext 沿用原出事件的 traceId）
     */
    public EventEnvelope toEnvelope(String platform, String botId,
                                    DeliveryReportPayload payload, String traceContext) {
        return new EventEnvelope(
                String.valueOf(idGenerator.nextId()),
                payload.outboxId(),
                contentDedupKey(payload.outboxId(), payload.attemptedAt()),
                platform,
                botId,
                null,
                null,
                PrimitiveTypeCodes.DELIVERY_REPORT,
                payload,
                null,
                clock.millis(),
                java.util.Map.of(),
                null,
                traceContext);
    }

    /**
     * 平台 retcode 的失败分类映射（data-model.md 第 6 节）：
     * 1403 / 12xx 审核风控类 → content_rejected；其余未知失败 → unknown。
     * （网络类失败不走 retcode——连接异常在调用层已归类 network。）
     */
    public static String classifyRetcodeFailure(int retcode, String wording) {
        if (retcode == 1403 || (retcode >= 1200 && retcode < 1300)) {
            return DeliveryFailureKindCodes.CONTENT_REJECTED;
        }
        return DeliveryFailureKindCodes.UNKNOWN;
    }

    /**
     * 回执幂等键：outboxId + 尝试时刻的内容哈希（同 outboxId 重投的第二条回执键不同，Agent 取最新）
     */
    private String contentDedupKey(String outboxId, long attemptedAt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((outboxId + ":" + attemptedAt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= DeliveryReportPayload.FAILURE_DETAIL_MAX_LENGTH
                ? detail
                : detail.substring(0, DeliveryReportPayload.FAILURE_DETAIL_MAX_LENGTH);
    }
}
