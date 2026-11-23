package cn.edu.scut.chatbot.engine.intake;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * credential 表 DO：凭证加密存储（引用不存明文）。
 */
@Data
public class CredentialDO {

    private Long id;

    private String name;

    /**
     * AES-256-GCM 密文（base64：IV + 密文 + tag）
     */
    private String cipherText;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
