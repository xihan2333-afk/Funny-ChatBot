package cn.edu.scut.chatbot.llm.slot;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * credential 表 DO（V6 重建形态）：凭证池——owner_kind 三归属、反馈式冷却、脱敏纪律。
 * 模型供应商（llm 侧 JdbcCredentialPool）与泛化工具（HttpGenericTool 按 id 引用）共用。
 */
@Data
public class CredentialDO {

    private Long id;

    /**
     * provider 模型供应商 / http_tool 泛化工具 / platform 平台
     */
    private String ownerKind;

    /**
     * 供应商 code / 工具名 / bot 标识
     */
    private String ownerCode;

    /**
     * AES-256-GCM：[12 字节随机 IV | 密文 + 16 字节 tag] 原始字节
     */
    private byte[] keyCipher;

    /**
     * 脱敏展示值（如 sk-***abc）——任何读路径与日志只暴露这个
     */
    private String keyMask;

    /**
     * available / cooldown（到期自动恢复）/ disabled（终态）
     */
    private String status;

    private LocalDateTime cooldownUntil;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
