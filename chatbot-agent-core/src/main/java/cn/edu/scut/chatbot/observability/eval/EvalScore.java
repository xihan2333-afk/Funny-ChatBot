package cn.edu.scut.chatbot.observability.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 五组分数（eval_result.score JSON 列的结构）：total 与五组单项各 0~100 或 null。
 * 红线失败时 total 无效（null）、含失败红线的维度记 0——分数与判定语义见
 * {@link RubricJudge}。
 */
public record EvalScore(Integer total,
                        Integer respond,
                        Integer process,
                        Integer form,
                        Integer cost,
                        Integer safety) {

    /**
     * score JSON → record（判分侧写入、查询侧读出共用同一结构）
     */
    public static EvalScore parse(String scoreJson) {
        try {
            JsonNode node = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().readTree(scoreJson);
            return new EvalScore(
                    intOrNull(node.path("total")),
                    intOrNull(node.path("respond")),
                    intOrNull(node.path("process")),
                    intOrNull(node.path("form")),
                    intOrNull(node.path("cost")),
                    intOrNull(node.path("safety")));
        } catch (Exception e) {
            return new EvalScore(null, null, null, null, null, null);
        }
    }

    /**
     * record → score JSON
     */
    public String toJson() {
        ObjectNode node = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().createObjectNode();
        putIfNotNull(node, "total", total);
        putIfNotNull(node, "respond", respond);
        putIfNotNull(node, "process", process);
        putIfNotNull(node, "form", form);
        putIfNotNull(node, "cost", cost);
        putIfNotNull(node, "safety", safety);
        return node.toString();
    }

    /**
     * 按维度名取分（一致率统计按五组循环取数）
     */
    public Integer byDimension(String dimension) {
        return switch (dimension) {
            case "respond" -> respond;
            case "process" -> process;
            case "form" -> form;
            case "cost" -> cost;
            case "safety" -> safety;
            default -> null;
        };
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static void putIfNotNull(ObjectNode node, String field, Integer value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
