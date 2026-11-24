package cn.edu.scut.chatbot.engine.intake;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * tool_config 表 DO：内置工具启用开关与运行参数覆盖。
 */
@Data
public class ToolConfigDO {

    private Long id;

    private String toolName;

    private Integer enabled;

    private String params;

    private String status;

    private Integer versionNo;

    private LocalDateTime updatedAt;
}
