package cn.edu.scut.chatbot.contract.envelope;

/**
 * 平台短码常量：信封 platform 字段的取值集合（data-model.md 第 2 节）。
 *
 * <p>常量类形态（而非 enum）是刻意的：contract 零行为逻辑、枚举本身不进契约，
 * 接入层与主服务各自按需定义自己的 enum，字符串短码才是跨进程契约。
 */
public final class PlatformCodes {

    /**
     * 第一版唯一接入的平台：OneBot 11 协议（QQ，实践形态 NapCat 或其他 OneBot 11 实现端）
     */
    public static final String ONEBOT_V11 = "onebot-v11";

    /**
     * 演进预留：QQ 官方机器人 API
     */
    public static final String QQ_OFFICIAL = "qq-official";

    private PlatformCodes() {
    }
}
