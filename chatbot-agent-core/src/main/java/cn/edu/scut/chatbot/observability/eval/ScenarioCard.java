package cn.edu.scut.chatbot.observability.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景卡五节点结构（data-model 1.2、eval-and-flywheel 契约第 3 节）：
 * groupBackground 群背景 / memberPersonas 成员人设 / preloadedState 预置状态
 * （第一批仅 groupConfig + persona 引用，记忆画像第二批结构预留）/ priorScript
 * 前情脚本（含 Agent 自己此前的发言——考人格连贯）/ triggerEvent 触发事件。
 *
 * <p>结构校验（T050 必测）：五节点齐全且形态正确（背景非空文本、人设与前情是
 * 数组、预置状态是对象、触发事件是对象含非空 digest）。入库面（create /
 * generateDraft 人工确认后）一律先校验——判分与重放侧拿到的一定合法。
 */
public record ScenarioCard(String groupBackground,
                           List<MemberPersona> memberPersonas,
                           PreloadedState preloadedState,
                           List<ScriptLine> priorScript,
                           TriggerEvent triggerEvent) {

    /**
     * 成员人设：userId + 一句话人设
     */
    public record MemberPersona(String userId, String persona) {
    }

    /**
     * 预置状态（第一批）：群配置引用与人格引用（记忆 / 画像 / 情绪基线第二批预留）
     */
    public record PreloadedState(Long personaId, String groupConfigRef) {
    }

    /**
     * 前情脚本一行：speaker（成员 userId 或 "agent"）+ 文本
     */
    public record ScriptLine(String speaker, String text) {
    }

    /**
     * 触发事件：类型 + 内容摘要（重放侧据此构造触发信封）
     */
    public record TriggerEvent(String eventType, String digest) {
    }

    /**
     * 场景卡 JSON → 结构化（结构非法抛 IllegalArgumentException——入库前已校验，
     * 此处的异常是数据损坏信号）
     */
    public static ScenarioCard parse(String scenarioJson) {
        JsonNode root;
        try {
            root = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().readTree(scenarioJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("scenario 不是合法 JSON：" + e.getMessage());
        }
        String background = root.path("groupBackground").asText("");
        if (background.isBlank()) {
            throw new IllegalArgumentException("场景卡缺 groupBackground（群背景）");
        }
        List<MemberPersona> personas = new ArrayList<>();
        for (JsonNode node : root.path("memberPersonas")) {
            personas.add(new MemberPersona(node.path("userId").asText(""),
                    node.path("persona").asText("")));
        }
        JsonNode preloaded = root.path("preloadedState");
        PreloadedState state = new PreloadedState(
                preloaded.path("personaId").isNumber() ? preloaded.path("personaId").asLong() : null,
                preloaded.path("groupConfigRef").isTextual() ? preloaded.path("groupConfigRef").asText() : null);
        List<ScriptLine> script = new ArrayList<>();
        for (JsonNode node : root.path("priorScript")) {
            script.add(new ScriptLine(node.path("speaker").asText(""),
                    node.path("text").asText("")));
        }
        JsonNode trigger = root.path("triggerEvent");
        if (!trigger.isObject() || trigger.path("digest").asText("").isBlank()) {
            throw new IllegalArgumentException("场景卡缺 triggerEvent.digest（触发事件）");
        }
        return new ScenarioCard(background, personas, state, script,
                new TriggerEvent(trigger.path("eventType").asText("message"),
                        trigger.path("digest").asText()));
    }

    /**
     * 校验裸 JSON 结构（不反序列化完整对象——入库前的门卫）
     */
    public static void validate(String scenarioJson) {
        parse(scenarioJson);
    }

    /**
     * 结构化 → 场景卡 JSON（入库序列化）
     */
    public String toJson() {
        ObjectNode root = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().createObjectNode();
        root.put("groupBackground", groupBackground);
        ArrayNode personas = root.putArray("memberPersonas");
        for (MemberPersona persona : memberPersonas) {
            ObjectNode node = personas.addObject();
            node.put("userId", persona.userId());
            node.put("persona", persona.persona());
        }
        ObjectNode state = root.putObject("preloadedState");
        if (preloadedState != null) {
            if (preloadedState.personaId() != null) {
                state.put("personaId", preloadedState.personaId());
            }
            if (preloadedState.groupConfigRef() != null) {
                state.put("groupConfigRef", preloadedState.groupConfigRef());
            }
        }
        ArrayNode script = root.putArray("priorScript");
        for (ScriptLine line : priorScript) {
            ObjectNode node = script.addObject();
            node.put("speaker", line.speaker());
            node.put("text", line.text());
        }
        ObjectNode trigger = root.putObject("triggerEvent");
        trigger.put("eventType", triggerEvent.eventType());
        trigger.put("digest", triggerEvent.digest());
        return root.toString();
    }
}
