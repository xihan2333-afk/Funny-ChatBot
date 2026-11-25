package cn.edu.scut.chatbot.memory.retrieve;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 检索参数（system_param {@code memory.retrieve.params}，运行层热变更）：
 * 检索管道（US3）与去重合并（US2）共用一份——mergeSimilarity 是内容级幂等的判定阈值。
 * 逐字段解析、坏值走缺省（参数错误不拖垮业务）。
 */
public record RetrieveParams(int oversample, int topKMin, int topKMax, int tokenBudget,
                             int boostPerHit, double mergeSimilarity) {

    private static final Logger log = LoggerFactory.getLogger(RetrieveParams.class);

    public static final String PARAM_KEY = "memory.retrieve.params";

    public static RetrieveParams defaults() {
        return new RetrieveParams(50, 5, 8, 1500, 5, 0.85);
    }

    /**
     * 从运行层装载（缺行/坏 JSON 走全缺省）
     */
    public static RetrieveParams load(SystemParamService systemParams) {
        String raw = systemParams == null
                ? null : systemParams.getString(PARAM_KEY, null);
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(raw);
            RetrieveParams fallback = defaults();
            return new RetrieveParams(
                    node.path("oversample").asInt(fallback.oversample()),
                    node.path("topKMin").asInt(fallback.topKMin()),
                    node.path("topKMax").asInt(fallback.topKMax()),
                    node.path("tokenBudget").asInt(fallback.tokenBudget()),
                    node.path("boostPerHit").asInt(fallback.boostPerHit()),
                    node.path("mergeSimilarity").asDouble(fallback.mergeSimilarity()));
        } catch (Exception e) {
            log.warn("memory.retrieve.params 解析失败走缺省：{}", e.getMessage());
            return defaults();
        }
    }
}
