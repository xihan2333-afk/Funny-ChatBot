package cn.edu.scut.chatbot.contract.error;

/**
 * 接入域统一错误码常量：点分字符串短码，adapter.* 命名空间（data-model.md 错误码规约、
 * 设计方案 3.4.8）。
 *
 * <p>使用纪律（宪法「异常与错误码」节）：错误码只描述「系统哪里坏了」，不承载业务分支；
 * ERROR 级日志必须携带 errorCode 与 traceId——写日志时省掉错误码，等于把这条日志踢出告警体系。
 */
public final class ErrorCodes {

    // ===== codec（报文翻译） =====
    /**
     * 信封组装违反非空清单——接入层内部断言，属 Codec bug，不可重试
     */
    public static final String ADAPTER_CODEC_INVALID_ENVELOPE = "adapter.codec.invalid_envelope";

    // ===== transport（协议端连接） =====
    /**
     * 协议端鉴权被拒（retcode=1403 失败帧，或连续多次「连上即断」），已停止重连等待运维介入
     */
    public static final String ADAPTER_TRANSPORT_AUTH_REJECTED = "adapter.transport.auth_rejected";

    /**
     * 与协议端建立连接失败（重连循环内的瞬时故障，退避后自动重试）
     */
    public static final String ADAPTER_TRANSPORT_CONNECT_FAILED = "adapter.transport.connect_failed";

    /**
     * 协议端登录账号与配置的期望 botId 不一致（get_login_info 自检发现，配置或协议端错了）
     */
    public static final String ADAPTER_TRANSPORT_BOT_ID_MISMATCH = "adapter.transport.bot_id_mismatch";

    /**
     * 协议端连接假死（应用层心跳超时判死或协议层 ping 失败），已主动断开触发重连
     */
    public static final String ADAPTER_TRANSPORT_STALLED = "adapter.transport.stalled";

    // ===== stream（Redis Stream 双流） =====
    /**
     * 出流毒消息：投递次数超限仍在重投（无死信队列，持续暴露给观测）
     */
    public static final String ADAPTER_STREAM_POISON = "adapter.stream.poison";

    /**
     * Redis 不可达或 Stream 写入失败（入方向事件不确认、原文已留档，等平台重推）
     */
    public static final String ADAPTER_STREAM_WRITE_FAILED = "adapter.stream.write_failed";

    // ===== media（媒体转存） =====
    /**
     * 媒体下载失败（SafeFetcher 纪律拦截或网络故障，不影响事件入流）
     */
    public static final String ADAPTER_MEDIA_FETCH_FAILED = "adapter.media.fetch_failed";

    /**
     * 出方向媒体装载失败：storedRef 指向的本地文件读取失败或超限
     */
    public static final String ADAPTER_MEDIA_LOAD_FAILED = "adapter.media.load_failed";

    // ===== archive（留档） =====
    /**
     * raw_event_log 留档写入失败
     */
    public static final String ADAPTER_ARCHIVE_WRITE_FAILED = "adapter.archive.write_failed";

    // ===== id（发号器） =====
    /**
     * 雪花发号器时钟回拨超过容忍阈值，拒绝发号（宁可失败不可重复）
     */
    public static final String ADAPTER_ID_CLOCK_BACKWARD = "adapter.id.clock_backward";

    private ErrorCodes() {
    }
}
