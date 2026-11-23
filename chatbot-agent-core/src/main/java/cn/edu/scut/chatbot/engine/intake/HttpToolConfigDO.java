package cn.edu.scut.chatbot.engine.intake;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * http_tool_config 表 DO：泛化工具数据面（模型可见三件 + 请求定义 + 凭证引用 + 响应处理）。
 */
@Data
public class HttpToolConfigDO {

    private Long id;

    private String toolName;

    private String description;

    private String parameters;

    private String method;

    private String urlTemplate;

    private String bodyTemplate;

    private String headers;

    private Long credentialId;

    private String extractPath;

    private Integer maxResponseBytes;

    private Integer timeoutMillis;

    private Integer enabled;

    private String status;

    private Integer versionNo;

    private LocalDateTime updatedAt;
}
