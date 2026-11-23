package cn.edu.scut.chatbot.prompt;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * content_version 表 DO：五类实体统一版本快照（回滚 = 旧快照写回主表再走一次发布）。
 */
@Data
public class ContentVersionDO {

    private Long id;

    /**
     * fragment / skill / tool_config / http_tool_config / persona
     */
    private String entityKind;

    private Long entityId;

    private Integer versionNo;

    /**
     * 发布时的全量快照 JSON
     */
    private String snapshot;

    private LocalDateTime createdAt;

    public static final String KIND_FRAGMENT = "fragment";
    public static final String KIND_SKILL = "skill";
    public static final String KIND_TOOL_CONFIG = "tool_config";
    public static final String KIND_HTTP_TOOL_CONFIG = "http_tool_config";
    public static final String KIND_PERSONA = "persona";
}
