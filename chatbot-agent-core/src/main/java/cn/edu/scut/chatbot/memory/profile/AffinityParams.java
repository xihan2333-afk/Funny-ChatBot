package cn.edu.scut.chatbot.memory.profile;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 好感曲线与显著者判定参数（system_param {@code memory.profile.affinity}，运行层热变更）。
 * 逐字段解析、坏值走缺省（参数错误不拖垮业务）。
 */
public record AffinityParams(int halfLifeDays, double decreaseFactor, int dailySedimentMax,
                             int eventDeltaRangeMin, int eventDeltaRangeMax,
                             int significantAffinity, int significantFamiliarity) {

    private static final Logger log = LoggerFactory.getLogger(AffinityParams.class);

    public static final String PARAM_KEY = "memory.profile.affinity";

    public static AffinityParams defaults() {
        return new AffinityParams(7, 1.5, 10, 1, 3, 30, 50);
    }

    /**
     * 从运行层装载（缺行/坏 JSON 走全缺省）
     */
    public static AffinityParams load(SystemParamService systemParams) {
        String raw = systemParams == null
                ? null : systemParams.getString(PARAM_KEY, null);
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(raw);
            AffinityParams fallback = defaults();
            JsonNode range = node.path("eventDeltaRange");
            return new AffinityParams(
                    node.path("halfLifeDays").asInt(fallback.halfLifeDays()),
                    node.path("decreaseFactor").asDouble(fallback.decreaseFactor()),
                    node.path("dailySedimentMax").asInt(fallback.dailySedimentMax()),
                    range.path(0).asInt(fallback.eventDeltaRangeMin()),
                    range.path(1).asInt(fallback.eventDeltaRangeMax()),
                    node.path("significantAffinity").asInt(fallback.significantAffinity()),
                    node.path("significantFamiliarity").asInt(fallback.significantFamiliarity()));
        } catch (Exception e) {
            log.warn("memory.profile.affinity 解析失败走缺省：{}", e.getMessage());
            return defaults();
        }
    }
}
