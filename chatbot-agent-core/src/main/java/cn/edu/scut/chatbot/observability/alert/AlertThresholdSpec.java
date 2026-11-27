package cn.edu.scut.chatbot.observability.alert;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * threshold JSON 的解析形态（V8 种子全部按此结构写入）：
 * op 比较方式（gt/gte/lt/lte/eq）、value 字面阈值或 paramKey 指向 system_param
 * （部署时设置的阈值走参数）、suppressMin 同维度抑制窗口（分钟）。
 */
public record AlertThresholdSpec(String op,
                                 Double value,
                                 String paramKey,
                                 Integer suppressMin) {

    public static AlertThresholdSpec parse(ObjectMapper mapper, String thresholdJson) {
        try {
            return mapper.readValue(thresholdJson, AlertThresholdSpec.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("告警规则 threshold JSON 解析失败: " + thresholdJson, e);
        }
    }

    /**
     * 阈值比较（值未配置且 paramKey 无效时按 0 处理——.gt 0 语义即"有即告警"）
     */
    public boolean matches(double sample) {
        double bound = value != null ? value : 0;
        return switch (op == null ? "" : op) {
            case "gt" -> sample > bound;
            case "gte" -> sample >= bound;
            case "lt" -> sample < bound;
            case "lte" -> sample <= bound;
            case "eq" -> sample == bound;
            default -> false;
        };
    }
}
