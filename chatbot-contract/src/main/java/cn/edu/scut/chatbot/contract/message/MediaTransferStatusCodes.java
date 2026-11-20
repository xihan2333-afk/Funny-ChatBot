package cn.edu.scut.chatbot.contract.message;

/**
 * 媒体段转存状态短码（data-model.md 第 3 节）。
 *
 * <p>入方向转存时序的语义：事件入流前做有界同步转存尝试（默认每媒体限时 3 秒），
 * 成功一次成型入流；超时或失败带 failed 与原 URL 立即入流，后台继续补档只落存储卷
 * 不追发事件——事件一次成型是 Agent 幂等契约的必然要求。
 */
public final class MediaTransferStatusCodes {

    /**
     * 尚未处理
     */
    public static final String PENDING = "pending";

    /**
     * 转存成功，storedRef 有值
     */
    public static final String TRANSFERRED = "transferred";

    /**
     * 转存失败，originalUrl 与失败标记保留
     */
    public static final String FAILED = "failed";

    /**
     * 无媒体，或出方向的本地存储引用形态
     */
    public static final String NONE = "none";

    private MediaTransferStatusCodes() {
    }
}
