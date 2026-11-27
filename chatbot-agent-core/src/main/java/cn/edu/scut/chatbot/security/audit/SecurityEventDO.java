package cn.edu.scut.chatbot.security.audit;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * security_event 表 DO：安全事件与管理操作审计（长期保留不滚动清理）。
 * JSON 列（detail）沿用全库惯例映射 String，由 {@link SecurityEventRecorderImpl} 序列化。
 */
@Data
public class SecurityEventDO {

    private Long id;

    /**
     * 八类事件小写码（SecurityEventKind.code()）
     */
    private String eventKind;

    private String platform;

    private String botId;

    private String groupId;

    private String userId;

    /**
     * 事件载荷 JSON：命中规则、工具与参数摘要、变更摘要、越过门禁理由等
     */
    private String detail;

    /**
     * 凭它还原完整执行现场（关联 ES Span 链路）
     */
    private String traceId;

    private LocalDateTime createdAt;
}
