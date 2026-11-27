package cn.edu.scut.chatbot.security.credential;

/**
 * 主密钥轮换运维服务（T085，admin-auth 契约第 2 节，R17、FR-020）：
 * 读全量 credential 行 → 旧钥解密 → 新钥重加密（带版本头标记）→ 按行事务更新 →
 * 幂等可重试（重跑跳过已用新钥加密的行）→ 轮换动作与结果进审计。
 */
public interface CredentialRotationService {

    /**
     * 使用新主密钥全量重加密 credential 表（幂等可中断重试）。
     *
     * @param newMasterKey 32 字节 AES-256 新主密钥
     */
    RotationReport rotate(byte[] newMasterKey);

    record RotationReport(int total, int rotated, int skipped, int failed) {
    }
}
