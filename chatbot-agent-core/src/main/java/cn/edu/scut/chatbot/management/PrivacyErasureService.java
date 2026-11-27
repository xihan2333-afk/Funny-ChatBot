package cn.edu.scut.chatbot.management;

/**
 * 个体数据清除门面（US12 T103，admin-auth 契约第 4 节，FR-024）：
 * 按（群三元组 + userId）四路同步清除 memory_entry + Qdrant 向量点、user_profile、
 * member_relation、transcript_entry 该用户消息；严格按群隔离保护跨群同 userId 数据；
 * 执行记入 security_event(adminOperate) 审计。
 */
public interface PrivacyErasureService {

    ErasureReport erase(String platform, String botId, String groupId, String userId, String operator);

    record ErasureReport(
            String platform,
            String botId,
            String groupId,
            String userId,
            int deletedMemories,
            int deletedVectorPoints,
            int deletedProfiles,
            int deletedRelations,
            int deletedTranscripts
    ) {
    }
}
