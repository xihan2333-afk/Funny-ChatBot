package cn.edu.scut.chatbot.llm.slot;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.loop.GenerationParams;
import cn.edu.scut.chatbot.loop.ModelConfig;
import cn.edu.scut.chatbot.loop.ModelRef;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 槽位装配链（contracts/slot-and-credentials.md 第 2 节）：Run 启动时按执行形态
 * 选槽位 → ModelCatalog.resolveSlot → gen_params 解析出 GenerationParams →
 * 产出 ModelConfig(ModelRef, GenerationParams) 成对进 AgentContext 第四要素
 * （快照随 Run——配置刷新不打扰进行中 Run）。
 *
 * <p>gen_params 四项映射：temperature（缺省 0.8）、maxOutputTokens（缺省 2048）、
 * structuredOutput（缺省关）、passthrough（JSON 对象叶子值展开为 Map&lt;String,String&gt;，
 * 缺省空）。解析失败即 {@link ModelCatalog.SlotResolutionException} 上抛——只拖垮
 * 本槽位，走既有 llm.config_missing 失败路径。
 */
@Component
public class SlotResolver {

    private final ModelCatalog catalog;

    public SlotResolver(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 槽位 → ModelConfig 成对（失败抛 SlotResolutionException）
     */
    public ModelConfig resolve(String slotCode) {
        ModelRef model = catalog.resolveSlot(slotCode);
        GenerationParams params = parseGenParams(catalog.genParamsOf(slotCode));
        return new ModelConfig(model, params);
    }

    /**
     * 槽位绑定版本（观测版本三键取数：engine 经本门面拿，不直触 ModelCatalog）
     */
    public long bindingVersionOf(String slotCode) {
        return catalog.bindingVersionOf(slotCode);
    }

    /**
     * gen_params JSON → GenerationParams 四项（叶子值路径展开——嵌套对象序列化为
     * 字符串值，供应商私有参数如 thinking 开关的兜底通道）
     */
    static GenerationParams parseGenParams(String genParamsJson) {
        if (genParamsJson == null || genParamsJson.isBlank()) {
            return defaults();
        }
        JsonNode node;
        try {
            node = JsonMappers.shared().readTree(genParamsJson);
        } catch (Exception e) {
            // ModelCatalog.resolveSlot 已校验合法性——此处不可达，防御态给缺省
            return defaults();
        }
        return new GenerationParams(
                node.path("temperature").asDouble(0.8),
                node.path("maxOutputTokens").asInt(2048),
                node.path("structuredOutput").asBoolean(false),
                flattenPassthrough(node.path("passthrough")));
    }

    private static GenerationParams defaults() {
        return new GenerationParams(0.8, 2048, false, Map.of());
    }

    /**
     * passthrough JSON 对象 → Map&lt;String,String&gt;：原始叶子（字符串/数字/布尔）
     * 字符串化；嵌套对象/数组序列化为 JSON 字符串（叶子值路径的一层展开）
     */
    private static Map<String, String> flattenPassthrough(JsonNode passthrough) {
        if (!passthrough.isObject() || passthrough.isEmpty()) {
            return Map.of();
        }
        Map<String, String> flattened = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = passthrough.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            if (value.isValueNode()) {
                flattened.put(entry.getKey(), value.asText());
            } else {
                flattened.put(entry.getKey(), value.toString());
            }
        }
        return flattened;
    }
}
