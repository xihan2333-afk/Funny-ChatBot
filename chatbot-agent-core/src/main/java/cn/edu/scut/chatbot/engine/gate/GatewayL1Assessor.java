package cn.edu.scut.chatbot.engine.gate;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.llm.gateway.CallOnceResult;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.gateway.ModelGateway;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.loop.GenerationParams;
import cn.edu.scut.chatbot.loop.LlmMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L1 评估的模型网关实现（contracts/capability-api.md 第 2/3 节）：评估提示词与批量摘要
 * 组装为单次 callOnce（eval_l1 槽位 + 低温度 + structuredOutput 强制 JSON）产出
 * AssessedDecision 列表。EngineConfiguration 的 unavailableL1Model 桩被本 Bean 就位后
 * 自动退位。
 *
 * <p>解析容错策略不在本类重复实现：输出非法 JSON 或调用异常按接口契约上抛，
 * 由既有 L1BatchAssessor 承担退避重试与保守 ignore（decision_layer=degrade）。
 */
@Component
public class GatewayL1Assessor implements L1AssessmentModel {

    private static final GenerationParams L1_PARAMS =
            new GenerationParams(0.1, 2048, true, Map.of());

    private final ModelGateway gateway;

    public GatewayL1Assessor(ModelGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public List<AssessedDecision> assess(String assessmentPrompt,
                                         List<TriggerDigest> batchDigests,
                                         List<String> recentMessages,
                                         String personaPreference,
                                         String groupStateDigest) throws Exception {
        CallOnceResult result = gateway.callOnce(ModelCatalog.SLOT_EVAL_L1,
                LlmCallMeta.of("l1", null), assessmentPrompt,
                List.of(LlmMessage.user(assembleInput(batchDigests, recentMessages,
                        personaPreference, groupStateDigest))),
                L1_PARAMS);
        if (result.failed()) {
            throw new IllegalStateException("L1 调用失败（" + result.errorCode() + "）：" + result.errorMessage());
        }
        return parse(result.text());
    }

    /**
     * 输入面组装：本批触发器 + 近期消息投影 + 注意力偏好 + 群状态摘要，输出指令要求 JSON 数组
     */
    private String assembleInput(List<TriggerDigest> batchDigests,
                                 List<String> recentMessages, String personaPreference,
                                 String groupStateDigest) {
        StringBuilder input = new StringBuilder("【本批待评估触发器】\n");
        for (TriggerDigest digest : batchDigests) {
            input.append("- dedupKey=").append(digest.dedupKey())
                    .append(" 类型=").append(digest.eventType())
                    .append(" 摘要=").append(digest.digest()).append('\n');
        }
        input.append("\n【近期群消息】\n");
        for (String line : recentMessages) {
            input.append(line).append('\n');
        }
        if (personaPreference != null && !personaPreference.isBlank()) {
            input.append("\n【注意力偏好】\n").append(personaPreference).append('\n');
        }
        if (groupStateDigest != null && !groupStateDigest.isBlank()) {
            input.append("\n【群状态】\n").append(groupStateDigest).append('\n');
        }
        input.append("\n对每条触发器输出判断，格式为 JSON 数组（只输出 JSON，不要多余文字）：\n")
                .append("[{\"dedupKey\":\"...\",\"decision\":\"accept 或 ignore\",\"reason\":\"简短理由\"}]");
        return input.toString();
    }

    /**
     * 输出解析：JSON 数组逐项映射——非法 JSON 抛异常（保守 ignore 由 L1BatchAssessor 承担）
     */
    private List<AssessedDecision> parse(String text) throws Exception {
        JsonNode root = JsonMappers.shared().readTree(text);
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalStateException("L1 输出不是非空 JSON 数组");
        }
        List<AssessedDecision> decisions = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            String dedupKey = item.path("dedupKey").asText(null);
            if (dedupKey == null || dedupKey.isEmpty()) {
                continue;
            }
            decisions.add(new AssessedDecision(dedupKey,
                    item.path("decision").asText("ignore"),
                    item.path("reason").asText("")));
        }
        return decisions;
    }
}
