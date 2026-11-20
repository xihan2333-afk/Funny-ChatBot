package cn.edu.scut.chatbot.access.transport;

import org.springframework.stereotype.Component;

/**
 * 协议端能力探测标记（契约 onebot-ws-adapter.md 第 4 节）：表情回应不是 OneBot 标准能力，
 * 协议端能力不保证——探测策略是「首调即拒才标记」，不预判。
 *
 * <p>SetReaction 首次调用失败且 retcode 表「无此 API」（如 1404）→ 标记该协议端不支持，
 * 此后同类出事件直接回执 unsupported 不再调用；协议端重连后清除标记（版本可能升级）。
 */
@Component
public class ReactionCapability {

    private volatile boolean emojiLikeSupported = true;

    /**
     * 当前协议端是否支持 set_msg_emoji_like（默认支持，首调即拒才标记降级）
     */
    public boolean isEmojiLikeSupported() {
        return emojiLikeSupported;
    }

    /**
     * 首调收到「无此 API」类失败（如 retcode=1404）时标记不支持
     */
    public void markEmojiLikeUnsupported() {
        emojiLikeSupported = false;
    }

    /**
     * 协议端重连后清除标记（新连接可能是升级后的实现）
     */
    public void reset() {
        emojiLikeSupported = true;
    }

    /**
     * retcode 是否表示「无此 API」（能力缺失而非业务拒绝）
     */
    public static boolean isApiMissingRetcode(int retcode) {
        // 1404 映射 HTTP 404（WS 形态，research R1）——各实现「action 不存在」的通行码
        return retcode == 1404;
    }
}
