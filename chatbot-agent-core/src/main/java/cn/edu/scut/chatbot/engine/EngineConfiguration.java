package cn.edu.scut.chatbot.engine;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.in.BotEventPayload;
import cn.edu.scut.chatbot.engine.gate.L1AssessmentModel;
import cn.edu.scut.chatbot.engine.gate.L1BatchAssessor;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.IntakeService;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * engine 域装配：邻接 feature 尚未落地的挂点在此给默认桩实现（research R10——
 * 机制真实、数据面最小，后续 feature 替换实现而不动契约）。
 *
 * <p>被桩化的挂点：L1 评估模型（2.3 落地前调用即失败，走降级纪律）、回执结算
 * （US3 OutboxWriter 就位后替换）、BotEvent 生命周期联动（US8 就位后替换）、
 * L1 上下文取数（US3/US6 的 mapper 就位后替换）。
 */
@Configuration
public class EngineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EngineConfiguration.class);

    /**
     * L1 评估模型默认桩：llm 包（2.3）未落地前调用即失败——L1BatchAssessor 按退避重试
     * 耗尽走保守 ignore 降级（评估失败不放大为系统故障，降级是安全侧）
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(L1AssessmentModel.class)
    public L1AssessmentModel unavailableL1Model() {
        return (prompt, digests, recent, preference) -> {
            throw new IllegalStateException("L1 评估模型未接入（2.3 feature 落地前不可用）");
        };
    }

    /**
     * StreamFn 默认桩（契约 adjacent-hooks.md：生产装配缺实现时 generation Effect 以
     * engine.* 错误结算）：调用即 Failed(llm.unavailable)——Run 以 error 终止、Turn FAILED。
     * 测试域注入 ScriptedStreamFn（@Primary）替代。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(cn.edu.scut.chatbot.loop.StreamFn.class)
    public cn.edu.scut.chatbot.loop.StreamFn unavailableStreamFn() {
        return (request, consumer, signal) ->
                consumer.onEvent(new cn.edu.scut.chatbot.loop.ModelStreamEvent.Failed(
                        "llm.unavailable", "推理模型未接入（2.3 feature 落地前不可用）"));
    }

    /**
     * 会话管理器（推理权闸的交付优先提升阈值从运行层参数读——热变更生效）
     */
    @Bean
    public cn.edu.scut.chatbot.engine.session.SessionManager sessionManager(
            cn.edu.scut.chatbot.engine.session.AgentSessionMapper sessionMapper,
            cn.edu.scut.chatbot.engine.session.TranscriptCodec transcriptCodec,
            cn.edu.scut.chatbot.bootstrap.IdGenerator idGenerator,
            SystemParamService systemParamService,
            Clock clock) {
        return new cn.edu.scut.chatbot.engine.session.SessionManager(
                sessionMapper, transcriptCodec, idGenerator, clock,
                systemParamService.getLong("worker.delivery_wait_boost_millis", 30000));
    }

    /**
     * 循环内核本体（无状态组件——每次 Run 的全部依赖经 RunSpec 传入）
     */
    @Bean
    public cn.edu.scut.chatbot.loop.AgentLoop agentLoop() {
        return new cn.edu.scut.chatbot.loop.AgentLoop();
    }

    /**
     * 窗口组装器（水位双档预算从运行层参数读——热变更生效）
     */
    @Bean
    public cn.edu.scut.chatbot.context.window.WindowAssembler windowAssembler(SystemParamService systemParamService) {
        return new cn.edu.scut.chatbot.context.window.WindowAssembler(
                systemParamService.getInt("context.window_active_tokens", 24576),
                systemParamService.getInt("context.window_quiet_tokens", 8192));
    }

    /**
     * 回执结算（US3 OutboxWriter 就位后的真实现：回执载荷 → 按 outboxId 结算并推进 Turn）
     */
    @Bean
    public IntakeService.DeliverySettlementSink outboxDeliverySink(
            cn.edu.scut.chatbot.engine.outbox.OutboxWriter outboxWriter) {
        return envelope -> {
            cn.edu.scut.chatbot.contract.in.DeliveryReportPayload report =
                    cn.edu.scut.chatbot.engine.intake.IntakeService.asDeliveryReport(envelope);
            if (report == null) {
                log.warn("回执信封载荷形态异常（跳过） type={}", envelope.type());
                return;
            }
            outboxWriter.settleDelivery(report);
        };
    }

    /**
     * BotEvent 生命周期联动（US8 就位后的真实现：被踢出群触发销毁联动；
     * 其余子类记录日志——完整 Bot 事件处理归后续 feature）
     */
    @Bean
    public IntakeService.LifecycleHook botEventLifecycleHook(
            cn.edu.scut.chatbot.engine.lifecycle.GroupLifecycleManager lifecycleManager) {
        return new IntakeService.LifecycleHook() {
            @Override
            public void onBotEvent(EventEnvelope envelope, BotEventPayload payload) {
                if (cn.edu.scut.chatbot.contract.in.BotEventPayload.SUB_BOT_KICKED.equals(payload.subType())
                        && envelope.groupId() != null) {
                    lifecycleManager.onBotKicked(envelope.platform(), envelope.botId(), envelope.groupId());
                    return;
                }
                log.info("BotEvent 到达 subType={}（除被踢外无生命周期联动）", payload.subType());
            }
        };
    }

    /**
     * L1 批量评估器装配（T088 挂接：评估提示词读 standalone 层种子片段（V4），
     * 近期消息取 transcript 投影，人格偏好经 PersonaResolver 直取 definition）
     */
    @Bean
    public L1BatchAssessor l1BatchAssessor(TriggerEventMapper triggerEventMapper,
                                           L1AssessmentModel model,
                                           SystemParamService systemParamService,
                                           cn.edu.scut.chatbot.prompt.PromptFragmentMapper fragmentMapper,
                                           cn.edu.scut.chatbot.prompt.PersonaResolver personaResolver,
                                           cn.edu.scut.chatbot.engine.session.AgentSessionMapper sessionMapper,
                                           cn.edu.scut.chatbot.engine.session.TranscriptCodec transcriptCodec,
                                           Clock clock) {
        return new L1BatchAssessor(
                triggerEventMapper,
                model,
                personaName -> {
                    // standalone 层评估提示词（V4 种子 builtin.l1_assessment；{{persona.name}} 已渲染）
                    var fragments = fragmentMapper.selectPublishedByLayer(
                            cn.edu.scut.chatbot.prompt.PromptFragmentDO.LAYER_STANDALONE);
                    return fragments.isEmpty()
                            ? "你是群聊注意力评估器（accept/ignore），拿不准一律 ignore。"
                            : fragments.get(0).getContent().replace("{{persona.name}}", personaName);
                },
                (platform, botId, groupId, limit) -> {
                    // 近期群消息投影（transcript 倒序 → 正序呈现，只取 user 形态文本行）
                    var session = sessionMapper.selectByGroup(platform, botId, groupId);
                    if (session == null) {
                        return List.of();
                    }
                    var recent = sessionMapper.selectRecent(session.getId(), limit);
                    List<String> lines = new java.util.ArrayList<>();
                    for (int i = recent.size() - 1; i >= 0; i--) {
                        var entry = recent.get(i);
                        if ("user".equals(entry.getKind())) {
                            lines.add(entry.getPayload());
                        }
                    }
                    return lines;
                },
                personaResolver::preferenceText,
                clock,
                systemParamService.getInt("gate.l1_max_retry", 3),
                systemParamService.getLong("gate.l1_backoff_base_millis", 500));
    }
}
