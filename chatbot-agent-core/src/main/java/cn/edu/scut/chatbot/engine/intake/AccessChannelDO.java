package cn.edu.scut.chatbot.engine.intake;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 社交平台接入渠道与 Bot 实例 DO（对应 access_channel 表）
 */
@Data
public class AccessChannelDO {

    private Long id;
    private String platform;
    private String botId;
    private String displayName;
    private String transportType;
    private String endpointUrl;
    private Long credentialId;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
