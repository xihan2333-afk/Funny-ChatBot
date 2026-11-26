package cn.edu.scut.chatbot.infrastructure.es;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成本报表（observe-query 契约第 1 节）：llm_call_log 事实源、is_replay=0 口径
 * （重放与评估成本单独记账不污染线上报表，SC-005）。三态 token：输入 / 输出 / 缓存命中。
 */
public record CostReport(CostQuery.CostDim dim, List<CostRow> rows) {

    /**
     * 单维度聚合行：dimensionKey 形态由维度决定（群为 platform:botId:groupId 拼接、
     * 槽位为 slot_code、模型为 modelId、日为 yyyy-MM-dd）
     */
    public record CostRow(String dimensionKey,
                          long calls,
                          long inputTokens,
                          long outputTokens,
                          long cachedTokens,
                          BigDecimal costAmount) {
    }
}
