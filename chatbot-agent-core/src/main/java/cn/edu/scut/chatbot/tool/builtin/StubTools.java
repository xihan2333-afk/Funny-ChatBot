package cn.edu.scut.chatbot.tool.builtin;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 桩面工具（research R10：机制真实、数据面最小——2.4/2.3 落地时换实现不动契约）：
 * searchMemory（空检索非 isError）、queryGroupInfo（群概况"暂无"）、recallMessage、
 * setReaction、updateEmotion（写 agent_session.emotion + 幅度钳制）。
 * readSkill 见 SkillService（US6 T087 完整实现）。
 */
public final class StubTools {

    private StubTools() {
    }

    @Component
    static class SearchMemoryTool implements AgentTool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "searchMemory",
                    "检索与查询相关的历史记忆（跨会话的事实与偏好）。",
                    SchemaBuilder.create().string("query", "检索词").require("query").build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.PURE_QUERY,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            // 桩（R10）：空检索是合法业务结果（非 isError）——2.4 记忆系统落地后替换实现
            return AgentToolResult.text("记忆检索暂无结果（记忆系统未接入，2.4 落地后可用）");
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
    static class UpdateEmotionTool implements AgentTool {

        private final AgentSessionMapper sessionMapper;
        private final cn.edu.scut.chatbot.bootstrap.SystemParamService systemParamService;

        UpdateEmotionTool(AgentSessionMapper sessionMapper,
                          cn.edu.scut.chatbot.bootstrap.SystemParamService systemParamService) {
            this.sessionMapper = sessionMapper;
            this.systemParamService = systemParamService;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "updateEmotion",
                    "更新你当前的情绪状态（只调制说话方式，不改变你能做什么）。",
                    SchemaBuilder.create()
                            .string("label", "情绪标签（如 开心/无语/兴奋）").require("label")
                            .integer("intensity", "强度 1~100").require("intensity")
                            .string("cause", "原因（一句话）").build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.SELF_STATE,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            try {
                AgentSessionDO session = sessionMapper.selectByGroup(
                        invocation.platform(), invocation.botId(), invocation.groupId());
                if (session == null) {
                    return AgentToolResult.error("会话尚未建立（群内先说句话再更新情绪）");
                }
                JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
                int maxIntensity = systemParamService.getInt("emotion.max_intensity", 100);
                int intensity = Math.max(1, Math.min(arguments.path("intensity").asInt(50), maxIntensity));
                // 数值永不解锁权限（宪法第六条）：emotion 只影响表达，字段本身无任何权限语义
                String emotionJson = "{\"label\":\"" + arguments.path("label").asText("平静")
                        + "\",\"intensity\":" + intensity
                        + ",\"cause\":\"" + arguments.path("cause").asText("") + "\"}";
                sessionMapper.updateEmotion(session.getId(), emotionJson);
                return AgentToolResult.text("情绪已更新（" + arguments.path("label").asText() + "，强度钳制后 " + intensity + "）");
            } catch (Exception e) {
                return AgentToolResult.error("情绪更新失败：" + e.getMessage());
            }
        }
    }
}
