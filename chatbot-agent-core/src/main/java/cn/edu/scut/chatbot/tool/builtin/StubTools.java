package cn.edu.scut.chatbot.tool.builtin;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 内置工具装配面：searchMemory 已换生产实现（004 T024——内核在 memory/retrieve，
 * 契约不动模型无感知）；queryGroupInfo（群概况"暂无"成员段待画像系统）、recallMessage、
 * setReaction、updateEmotion（写 agent_session.emotion + 幅度钳制）仍是桩
 * （research R10：机制真实、数据面最小——后续 feature 换实现不动契约）。
 * readSkill 见 SkillService（US6 T087 完整实现）。
 */
public final class StubTools {

    private StubTools() {
    }

    @Component
    public static class SearchMemoryTool implements AgentTool {

        private final cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever retriever;

        public SearchMemoryTool(cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever retriever) {
            this.retriever = retriever;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "searchMemory",
                    "检索与查询相关的历史记忆（跨会话的事实与偏好）。",
                    SchemaBuilder.create()
                            .string("query", "检索词（自然语言）").require("query")
                            .string("memoryType", "记忆类型过滤，五类之一：episode/fact/topic/preference/relationClue（可选）")
                            .string("userId", "只查关于某位成员的记忆（可选）")
                            .integer("withinDays", "只查近 N 天内发生的记忆（可选）")
                            .build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.PURE_QUERY,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            try {
                JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
                String queryText = arguments.path("query").asText("");
                if (queryText.isBlank()) {
                    return AgentToolResult.error("query 参数不能为空");
                }
                String memoryType = blankToNull(arguments.path("memoryType").asText(null));
                String userId = blankToNull(arguments.path("userId").asText(null));
                Integer withinDays = arguments.path("withinDays").isNumber()
                        ? arguments.path("withinDays").asInt() : null;
                cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever.MemorySearchResult result =
                        retriever.search(
                                new cn.edu.scut.chatbot.bootstrap.GroupKey(
                                        invocation.platform(), invocation.botId(), invocation.groupId()),
                                new cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever.MemorySearchQuery(
                                        queryText, memoryType, userId, withinDays));
                // 检索失败是 isError（不杀循环）；空召回是正常业务文本非 isError
                return result.error()
                        ? AgentToolResult.error(result.content())
                        : AgentToolResult.text(result.content());
            } catch (Exception e) {
                return AgentToolResult.error("记忆检索失败：" + e.getMessage());
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    @Component
    static class QueryGroupInfoTool implements AgentTool {

        private final GroupMapper groupMapper;
        private final GroupConfigMapper groupConfigMapper;

        QueryGroupInfoTool(GroupMapper groupMapper, GroupConfigMapper groupConfigMapper) {
            this.groupMapper = groupMapper;
            this.groupConfigMapper = groupConfigMapper;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "queryGroupInfo",
                    "查询当前群的基本信息（群名、群规、功能开关概况）。",
                    SchemaBuilder.create().build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.PURE_QUERY,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            GroupDO group = groupMapper.selectByGroup(invocation.platform(), invocation.botId(), invocation.groupId());
            GroupConfigDO config = groupConfigMapper.selectByGroup(invocation.platform(), invocation.botId(), invocation.groupId());
            StringBuilder info = new StringBuilder("群信息：");
            info.append("群名=").append(group != null && group.getGroupName() != null ? group.getGroupName() : "未知")
                    .append("；服务状态=").append(group != null ? group.getLifecycle() : "未知")
                    .append("；群规=").append(config != null && config.getGroupRules() != null ? config.getGroupRules() : "未设置")
                    .append("；成员概况=暂无（成员数据源是画像系统，2.4 落地后可用）");
            return AgentToolResult.text(info.toString());
        }
    }

    @Component
    static class RecallMessageTool implements AgentTool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "recallMessage",
                    "撤回自己刚发出的一条消息。",
                    SchemaBuilder.create().string("platformMessageId", "要撤回的平台消息 ID").require("platformMessageId").build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            // 桩（R10）：第一版不支持主动撤回（isError 业务分支——不发即忘之外的出事件
            // 走 outbox，recall 原语出事件形态由 2.7 运营需求定型）
            return AgentToolResult.error("消息撤回暂不可用（第一版未开放）");
        }
    }

    @Component
    static class SetReactionTool implements AgentTool {

        private final cn.edu.scut.chatbot.engine.outbox.OutboxWriter outboxWriter;

        SetReactionTool(cn.edu.scut.chatbot.engine.outbox.OutboxWriter outboxWriter) {
            this.outboxWriter = outboxWriter;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "setReaction",
                    "给一条消息加表情回应。",
                    SchemaBuilder.create()
                            .string("platformMessageId", "目标消息 ID").require("platformMessageId")
                            .string("emoji", "表情（如 👍）").require("emoji").build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            try {
                JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
                String payloadJson = JsonMappers.valueToStringOrNull(
                        new cn.edu.scut.chatbot.contract.out.SetReactionPayload(
                                arguments.path("platformMessageId").asText(),
                                arguments.path("emoji").asText(),
                                false));
                long outboxId = outboxWriter.enqueueOutbound(invocation.platform(), invocation.botId(),
                        invocation.groupId(), cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes.SET_REACTION,
                        payloadJson, invocation.turnId(), invocation.taskId());
                return AgentToolResult.text("已提交表情回应（outboxId=" + outboxId + "）");
            } catch (Exception e) {
                return AgentToolResult.error("表情回应提交失败：" + e.getMessage());
            }
        }
    }

    @Component
    public static class UpdateEmotionTool implements AgentTool {

        private final cn.edu.scut.chatbot.memory.emotion.EmotionService emotionService;

        public UpdateEmotionTool(cn.edu.scut.chatbot.memory.emotion.EmotionService emotionService) {
            this.emotionService = emotionService;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "updateEmotion",
                    "更新你当前的情绪状态（只调制说话方式，不改变你能做什么）。",
                    SchemaBuilder.create()
                            .string("moodLabel", "情绪标签（须在人格情绪词汇集内，默认集：开心/平静/兴奋/好奇/不爽/委屈/无聊/感动）").require("moodLabel")
                            .integer("intensityDelta", "强度变化（正负值，有单次与每日上限）").require("intensityDelta")
                            .string("cause", "原因（一句话）").build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.PURE_QUERY,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            try {
                JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
                String moodLabel = arguments.path("moodLabel").asText("");
                int intensityDelta = arguments.path("intensityDelta").asInt(0);
                String cause = arguments.path("cause").asText(null);
                // 内核在 memory/emotion（T044 生产实现）：词汇集校验、双通道钳制、
                // emotion_log 落账；形态裁配——Worker 不装配（无人格天然无情绪）
                boolean applied = emotionService.applySelfReport(
                        new cn.edu.scut.chatbot.bootstrap.GroupKey(
                                invocation.platform(), invocation.botId(), invocation.groupId()),
                        moodLabel, intensityDelta, cause);
                if (!applied) {
                    return AgentToolResult.error("今日情绪变化已达上限，明天再更新吧");
                }
                return AgentToolResult.text("情绪已更新（" + moodLabel + "）");
            } catch (IllegalArgumentException e) {
                // 词汇集外标签：不应用、不进发言、不泄漏到群
                return AgentToolResult.error(e.getMessage());
            } catch (Exception e) {
                return AgentToolResult.error("情绪更新失败：" + e.getMessage());
            }
        }
    }
}
