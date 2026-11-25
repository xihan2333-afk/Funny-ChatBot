package cn.edu.scut.chatbot.memory.entry;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * memory_entry 表 DO（data-model §1.1）：记忆条目事实源——MySQL 是事实源，
 * Qdrant memory_entry 集合是可全量重建的派生索引（点 id = 条目 id）。
 *
 * <p>JSON 列（keywords / source_ref）沿用全库惯例映射 String、服务层经 JsonMappers 解析
 * （GroupConfigDO / TriggerEventDO 同形态）。
 */
@Data
public class MemoryEntryDO {

    /**
     * 雪花 ID（IdGenerator 发放先于落库），同时是 Qdrant 点 id
     */
    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * 五类：episode / fact / topic / preference / relationClue
     */
    private String memoryType;

    /**
     * 正文纯文本
     */
    private String content;

    /**
     * 涉及成员；群体级记忆为 null
     */
    private String userId;

    /**
     * 话题类条目关键词列表 JSON（topic_lifecycle=cooling 时再热判定的匹配源）
     */
    private String keywords;

    /**
     * 仅 topic 类：hot / cooling / closed（独立于 status 的第二维度）
     */
    private String topicLifecycle;

    /**
     * 事件发生时间（衰减双时间轴之一）
     */
    private LocalDateTime occurredAt;

    /**
     * 沉淀时间
     */
    private LocalDateTime sedimentAt;

    /**
     * 0~100，强化与遗忘的操作对象
     */
    private Integer strength;

    /**
     * 最近检索命中时间（衰减双时间轴之二——检索热度抵消衰减）
     */
    private LocalDateTime lastAccessedAt;

    /**
     * active / archived / superseded
     */
    private String status;

    /**
     * 取代链：取代本条的新记忆 id
     */
    private Long supersededBy;

    /**
     * 溯源 JSON：产生本条的沉淀工单 id
     */
    private String sourceRef;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static final String TYPE_EPISODE = "episode";
    public static final String TYPE_FACT = "fact";
    public static final String TYPE_TOPIC = "topic";
    public static final String TYPE_PREFERENCE = "preference";
    public static final String TYPE_RELATION_CLUE = "relationClue";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";
    public static final String STATUS_SUPERSEDED = "superseded";

    public static final String TOPIC_HOT = "hot";
    public static final String TOPIC_COOLING = "cooling";
    public static final String TOPIC_CLOSED = "closed";
}
