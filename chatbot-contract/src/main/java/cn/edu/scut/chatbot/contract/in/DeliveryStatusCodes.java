package cn.edu.scut.chatbot.contract.in;

/**
 * 投递回执状态短码（data-model.md 第 6 节）：回执三态。
 */
public final class DeliveryStatusCodes {

    /**
     * 投递成功
     */
    public static final String SUCCESS = "success";

    /**
     * 投递失败（细节见 failureKind / failureDetail）
     */
    public static final String FAILED = "failed";

    /**
     * 协议端不支持该能力（如 SetReaction 在当前实现端）——不算 failed
     */
    public static final String UNSUPPORTED = "unsupported";

    private DeliveryStatusCodes() {
    }
}
