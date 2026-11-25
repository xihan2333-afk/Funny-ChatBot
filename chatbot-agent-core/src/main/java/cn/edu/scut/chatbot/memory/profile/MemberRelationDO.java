package cn.edu.scut.chatbot.memory.profile;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * member_relation 表 DO（data-model §1.5）：成员关系单跳无向边——
 * user_id_a 恒为字典序较小方（无向边唯一化，uk_pair 不含 relation_kind，
 * 一行一最新类型，强度累计）。成员 ↔ Agent 的双轴态度不在本表（在 user_profile）。
 */
@Data
public class MemberRelationDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * 字典序较小方（写入前归一化）
     */
    private String userIdA;

    private String userIdB;

    /**
     * interact 常互动 / tease 互怼 / avoid 回避
     */
    private String relationKind;

    /**
     * 关系强度（互动累计）
     */
    private Integer strength;

    private LocalDateTime updatedAt;
}
