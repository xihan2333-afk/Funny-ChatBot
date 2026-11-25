package cn.edu.scut.chatbot.memory.emotion;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 情绪参数（system_param 两键合一装载，运行层热变更）：
 * memory.emotion.lazy_decay（半衰期与基线阈值）与 memory.emotion.clamp（单次与日累计钳制）。
 * 坏值走缺省（参数错误不拖垮业务）。
 */
public record EmotionParams(int halfLifeHours, int baselineThreshold,
                            int singleDeltaMax, int dailyAccumulateMax) {

    private static final Logger log = LoggerFactory.getLogger(EmotionParams.class);

    public static final String LAZY_DECAY_KEY = "memory.emotion.lazy_decay";
    public static final String CLAMP_KEY = "memory.emotion.clamp";

    public static final String DEFAULT_BASELINE_LABEL = "平静";

    /**
     * 内置默认情绪集八标签（人格词汇集可覆盖——猫娘可以有"炸毛"）
     */
    public static final java.util.List<String> DEFAULT_VOCABULARIES =
            java.util.List.of("开心", "平静", "兴奋", "好奇", "不爽", "委屈", "无聊", "感动");

    public static EmotionParams defaults() {
        return new EmotionParams(2, 20, 30, 200);
    }

    public static EmotionParams load(SystemParamService systemParams) {
        EmotionParams fallback = defaults();
        if (systemParams == null) {
            return fallback;
        }
        int halfLifeHours = fallback.halfLifeHours();
        int baselineThreshold = fallback.baselineThreshold();
        int singleDeltaMax = fallback.singleDeltaMax();
        int dailyAccumulateMax = fallback.dailyAccumulateMax();
        try {
            String decayRaw = systemParams.getString(LAZY_DECAY_KEY, null);
            if (decayRaw != null && !decayRaw.isBlank()) {
                JsonNode node = JsonMappers.shared().readTree(decayRaw);
                halfLifeHours = node.path("halfLifeHours").asInt(fallback.halfLifeHours());
                baselineThreshold = node.path("baselineThreshold").asInt(fallback.baselineThreshold());
            }
        } catch (Exception e) {
            log.warn("memory.emotion.lazy_decay 解析失败走缺省：{}", e.getMessage());
        }
        try {
            String clampRaw = systemParams.getString(CLAMP_KEY, null);
            if (clampRaw != null && !clampRaw.isBlank()) {
                JsonNode node = JsonMappers.shared().readTree(clampRaw);
                singleDeltaMax = node.path("singleDeltaMax").asInt(fallback.singleDeltaMax());
                dailyAccumulateMax = node.path("dailyAccumulateMax").asInt(fallback.dailyAccumulateMax());
            }
        } catch (Exception e) {
            log.warn("memory.emotion.clamp 解析失败走缺省：{}", e.getMessage());
        }
        return new EmotionParams(halfLifeHours, baselineThreshold, singleDeltaMax, dailyAccumulateMax);
    }
}
