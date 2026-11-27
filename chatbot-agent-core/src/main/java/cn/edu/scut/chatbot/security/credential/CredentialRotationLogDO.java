package cn.edu.scut.chatbot.security.credential;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 凭证加密主密钥全量轮换执行历史 DO（对应 credential_rotation_log 表）
 */
@Data
public class CredentialRotationLogDO {

    private Long id;
    private String operator;
    private String status;
    private Integer rotatedCount;
    private Integer skippedCount;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
