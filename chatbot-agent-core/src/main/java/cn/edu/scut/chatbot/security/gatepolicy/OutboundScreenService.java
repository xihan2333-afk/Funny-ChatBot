package cn.edu.scut.chatbot.security.gatepolicy;

import cn.edu.scut.chatbot.security.audit.SecurityDims;

/**
 * 出站快筛（security-gate 契约第 3 节）：sendMessage 执行入口最先调用、毫秒级同步——
 * block 词库拦截、review 词库放行留痕、提示词指纹（发言 × systemPrompt 最长公共子串）。
 */
public interface OutboundScreenService {

    /**
     * @param speechText               发言文本（sendMessage 的 text 参数）
     * @param systemPromptOfCurrentRun 本次 Run 的 systemPrompt 快照（可空——无快照跳过指纹）
     */
    ScreenResult screen(String speechText, String systemPromptOfCurrentRun);

    /**
     * 带群维度与链路 id 的快筛入口（sendMessage 工具调用——确保落库的 contentFiltered
     * 事件携带隔离三元组与 traceId，方便链路页还原现场）
     */
    default ScreenResult screen(String speechText, String systemPromptOfCurrentRun,
                                SecurityDims dims, String traceId) {
        return screen(speechText, systemPromptOfCurrentRun);
    }

    /**
     * 判定结果：blocked + errorCode（security.contentFiltered | security.promptLeak）
     * + 命中词（词库命中时可空）+ 指纹连续命中字符数
     */
    record ScreenResult(boolean blocked, String errorCode,
                        String hitWord, int fingerprintOverlapChars) {

        public static ScreenResult pass() {
            return new ScreenResult(false, null, null, 0);
        }
    }
}
