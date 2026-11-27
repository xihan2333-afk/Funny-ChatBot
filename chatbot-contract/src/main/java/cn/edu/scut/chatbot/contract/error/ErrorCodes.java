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

    // ===== observe（观测域，005-observability-safety） =====
    /**
     * 观测初始化失败，已降级 noop——观测管道不可用但不阻断应用启动（FR-026 可丢不可卡）
     */
    public static final String OBSERVE_INIT_FAILED = "observe.init_failed";

    /**
     * 观测导出事件被丢弃（有界队列满 / Collector 不可达重试耗尽）——计数喂给「导出管道异常」告警
     */
    public static final String OBSERVE_EXPORT_DROPPED = "observe.export_dropped";

    /**
     * 观测查询不可达（ES 连接失败等）——明确可重试错误，不挂死；只影响观测消费不影响业务
     */
    public static final String OBSERVE_QUERY_UNAVAILABLE = "observe.query_unavailable";

    /**
     * 评估批次并发已达上限（observe.replay.maxConcurrent）——手动圈选被拒，稍后重试
     */
    public static final String OBSERVE_EVAL_BUSY = "observe.eval_busy";

    /**
     * 评估域输入或模型输出非法（场景卡初稿不合结构等）——明确不可重试
     */
    public static final String OBSERVE_EVAL_INVALID = "observe.eval_invalid";

    // ===== security（安全域，005-observability-safety） =====
    /**
     * 出站内容被词库拦截（block 级命中）——isError 工具结果 + 终止循环 + Outbox 零写入
     */
    public static final String SECURITY_CONTENT_FILTERED = "security.contentFiltered";

    /**
     * 提示词指纹检测命中（发言与 systemPrompt 连续片段超阈值）——整段倒出设定是确定性后果
     */
    public static final String SECURITY_PROMPT_LEAK = "security.promptLeak";

    /**
     * 成本熔断已触发（当日累计超阈值）——拒新重任务 / 停主动行为 / 能力类调用拒绝
     */
    public static final String SECURITY_BREAKER_TRIPPED = "security.breakerTripped";

    /**
     * createTask 越权被拒（Mandate 授权不通过）——模型可自然告知群友"这个我做不了"
     */
    public static final String SECURITY_MANDATE_DENIED = "security.mandateDenied";

    /**
     * 频控超出（重任务 / runCode 每日上限）——明确错误结果，次日自动恢复
     */
    public static final String SECURITY_QUOTA_EXCEEDED = "security.quotaExceeded";

    /**
     * 链接抓取域名在黑名单——请求发出前拦截，按既定降级路径处理
     */
    public static final String SECURITY_DOMAIN_BLOCKED = "security.domainBlocked";

    /**
     * 安全事件落库失败（审计写入异常——不反噬业务但必须有日志可查）
     */
    public static final String SECURITY_AUDIT_WRITE_FAILED = "security.auditWriteFailed";

    private ErrorCodes() {
    }
}
