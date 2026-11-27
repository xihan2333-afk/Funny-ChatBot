package cn.edu.scut.chatbot.security.gatepolicy;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link OutboundScreenService} 实现：判定链 block 词库 → review 词库（放行留痕）→
 * 提示词指纹（最长公共子串，滚动数组 DP——发言百字 × systemPrompt 数 KB 毫秒内完成）。
 *
 * <p>词库进程内缓存 + ConfigRefreshEvent 刷新（新 Run 生效）；拦截与 review 留痕
 * 都经 SecurityEventRecorder（contentFiltered，detail 带 source 区分 block/review/指纹）。
 */
@Component
public class OutboundScreenServiceImpl implements OutboundScreenService {

    private static final Logger log = LoggerFactory.getLogger(OutboundScreenServiceImpl.class);

    static final String PARAM_FINGERPRINT_THRESHOLD = "security.screen.fingerprintThreshold";
    static final String PARAM_FINGERPRINT_ENABLED = "security.screen.fingerprintEnabled";

    /**
     * 词库命中留痕的发言摘要长度
     */
    private static final int SNIPPET_CHARS = 120;

    private final ContentFilterWordMapper wordMapper;
    private final SystemParamService systemParams;
    private final SecurityEventRecorder recorder;

    /**
     * 词库缓存（volatile 整体替换——读侧无锁）
     */
    private volatile List<String> blockWords = List.of();
    private volatile List<String> reviewWords = List.of();

    public OutboundScreenServiceImpl(ContentFilterWordMapper wordMapper,
                                     SystemParamService systemParams,
                                     SecurityEventRecorder recorder) {
        this.wordMapper = wordMapper;
        this.systemParams = systemParams;
        this.recorder = recorder;
        reloadWords();
    }

    @Override
    public ScreenResult screen(String speechText, String systemPromptOfCurrentRun) {
        return screen(speechText, systemPromptOfCurrentRun, SecurityDims.none(), null);
    }

    @Override
    public ScreenResult screen(String speechText, String systemPromptOfCurrentRun,
                               SecurityDims dims, String traceId) {
        if (speechText == null || speechText.isBlank()) {
            return ScreenResult.pass();
        }
        SecurityDims effectiveDims = dims != null ? dims : SecurityDims.none();
        // 判定链第一环：block 词库（enabled 词条）
        for (String word : blockWords) {
            if (speechText.contains(word)) {
                recordFiltered("block", word, speechText, effectiveDims, traceId);
                return new ScreenResult(true, ErrorCodes.SECURITY_CONTENT_FILTERED, word, 0);
            }
        }
        // 第二环：review 词库（命中放行但留痕——人工复核口径）
        for (String word : reviewWords) {
            if (speechText.contains(word)) {
                recordFiltered("review", word, speechText, effectiveDims, traceId);
                break;
            }
        }
        // 第三环：提示词指纹（发言 × systemPrompt 连续重合——整段倒出设定是长片段命中）
        if (systemPromptOfCurrentRun != null && !systemPromptOfCurrentRun.isBlank()
                && Boolean.parseBoolean(
                systemParams.getString(PARAM_FINGERPRINT_ENABLED, "true"))) {
            int threshold = systemParams.getInt(PARAM_FINGERPRINT_THRESHOLD, 30);
            int overlap = longestCommonSubstringLength(speechText, systemPromptOfCurrentRun);
            if (overlap >= threshold) {
                recordLeak(overlap, speechText, effectiveDims, traceId);
                return new ScreenResult(true, ErrorCodes.SECURITY_PROMPT_LEAK, null, overlap);
            }
            return new ScreenResult(false, null, null, overlap);
        }
        return ScreenResult.pass();
    }

    /**
     * 词库热刷新（ConfigRefreshEvent——与工具热管理同机制）
     */
    @EventListener(cn.edu.scut.chatbot.tool.registry.ToolRegistry.ConfigRefreshEvent.class)
    public void reloadWords() {
        try {
            List<String> block = new ArrayList<>();
            List<String> review = new ArrayList<>();
            for (ContentFilterWordDO word : wordMapper.selectEnabled()) {
                if (word.getWord() == null || word.getWord().isBlank()) {
                    continue;
                }
                if (ContentFilterWordDO.LEVEL_BLOCK.equals(word.getLevel())) {
                    block.add(word.getWord());
                } else if (ContentFilterWordDO.LEVEL_REVIEW.equals(word.getLevel())) {
                    review.add(word.getWord());
                }
            }
            this.blockWords = List.copyOf(block);
            this.reviewWords = List.copyOf(review);
        } catch (Exception e) {
            // 刷新失败沿用旧缓存（DB 不可达不拖垮快筛）；首次失败为空库（全放行）
            log.warn("词库刷新失败沿用旧缓存", e);
        }
    }

    /**
     * 最长公共子串长度（连续）：滚动数组 DP，char[] 直接比较避免 substring 分配
     */
    static int longestCommonSubstringLength(String speech, String prompt) {
        char[] a = speech.toCharArray();
        char[] b = prompt.toCharArray();
        int[] prev = new int[b.length + 1];
        int[] curr = new int[b.length + 1];
        int best = 0;
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                if (a[i - 1] == b[j - 1]) {
                    curr[j] = prev[j - 1] + 1;
                    if (curr[j] > best) {
                        best = curr[j];
                    }
                } else {
                    curr[j] = 0;
                }
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
            java.util.Arrays.fill(curr, 0);
        }
        return best;
    }

    private void recordFiltered(String source, String hitWord, String speechText,
                                SecurityDims dims, String traceId) {
        try {
            recorder.record(SecurityEventKind.CONTENT_FILTERED, dims,
                    Map.of("source", source,
                            "hitWord", hitWord,
                            "speechSnippet", snippet(speechText)),
                    traceId);
        } catch (Exception e) {
            log.warn("contentFiltered 事件记录失败（不影响判定结果）", e);
        }
    }

    private void recordLeak(int overlapChars, String speechText,
                            SecurityDims dims, String traceId) {
        try {
            recorder.record(SecurityEventKind.CONTENT_FILTERED, dims,
                    Map.of("source", "fingerprint",
                            "overlapChars", overlapChars,
                            "speechSnippet", snippet(speechText)),
                    traceId);
        } catch (Exception e) {
            log.warn("promptLeak 事件记录失败（不影响判定结果）", e);
        }
    }

    private static String snippet(String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= SNIPPET_CHARS ? compact : compact.substring(0, SNIPPET_CHARS);
    }
}
