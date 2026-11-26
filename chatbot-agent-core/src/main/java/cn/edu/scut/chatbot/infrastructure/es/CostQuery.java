package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 成本报表查询：聚合维度（群 / 槽位 / 模型 / 日）+ 时间范围 + 可选群过滤。
 */
public record CostQuery(CostDim dim,
                        Long fromMillis,
                        Long toMillis,
                        String groupId) {

    public enum CostDim {
        /**
         * 按群（platform + bot_id + group_id 三元组拼接为维度键）
         */
        GROUP,
        /**
         * 按槽位
         */
        SLOT,
        /**
         * 按模型
         */
        MODEL,
        /**
         * 按自然日（llm_call_log.created_at 所在日）
         */
        DAY
    }
}
