package cn.edu.scut.chatbot.contract.in;

/**
 * 投递失败分类短码（data-model.md 第 6 节）：Agent 行为调度的输入。
 *
 * <p>映射规则（Sender 内判定）：retcode 1403/审核类 → content_rejected；网络异常/超时 → network；
 * 协议端明确额度/风控码 → quota_exceeded；本地媒体文件缺失 → media_missing；
 * 平台限频 → rate_limited；其余按 unknown 兜底。
 */
public final class DeliveryFailureKindCodes {

    /**
     * 网络异常 / 超时
     */
    public static final String NETWORK = "network";

    /**
     * 内容被平台拒绝（审核 / 风控 / retcode 1403 类）
     */
    public static final String CONTENT_REJECTED = "content_rejected";

    /**
     * 平台额度耗尽
     */
    public static final String QUOTA_EXCEEDED = "quota_exceeded";

    /**
     * 平台限频
     */
    public static final String RATE_LIMITED = "rate_limited";

    /**
     * 本地媒体文件缺失（storedRef 指向的文件不在存储卷上）
     */
    public static final String MEDIA_MISSING = "media_missing";

    /**
     * 接入层内部错误（信封损坏、翻译异常等）
     */
    public static final String INTERNAL = "internal";

    /**
     * 未能归类的失败
     */
    public static final String UNKNOWN = "unknown";

    private DeliveryFailureKindCodes() {
    }
}
