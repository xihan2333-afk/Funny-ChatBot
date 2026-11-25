package cn.edu.scut.chatbot.engine;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.in.BotEventPayload;
import cn.edu.scut.chatbot.engine.gate.L1AssessmentModel;
import cn.edu.scut.chatbot.engine.gate.L1BatchAssessor;
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
        return (prompt, digests, recent, preference, groupStateDigest) -> {
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
     * 压缩摘要端口默认实现（确定性拼装）：ModelSummarizer（summarize 槽位）就位后退位
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            cn.edu.scut.chatbot.context.compaction.SummaryGenerator.class)
    public cn.edu.scut.chatbot.context.compaction.SummaryGenerator deterministicSummaryGenerator() {
        return new cn.edu.scut.chatbot.context.compaction.DeterministicSummaryGenerator();
    }

    /**
     * 视觉描述端口默认桩（返回 null——投「描述未就绪」占位行）：
     * GatewayVisionDescriber（vision_desc 槽位）就位后退位
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            cn.edu.scut.chatbot.context.media.VisionDescriber.class)
    public cn.edu.scut.chatbot.context.media.VisionDescriber unavailableVisionDescriber() {
        return mediaRef -> null;
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
     * 其余子类写入转录（Bot 自身事件对人格可见）——完整 Bot 事件处理归后续 feature）
     */
    @Bean
    public IntakeService.LifecycleHook botEventLifecycleHook(
            cn.edu.scut.chatbot.engine.lifecycle.GroupLifecycleManager lifecycleManager,
            cn.edu.scut.chatbot.engine.session.TranscriptIntakeWriter transcriptIntakeWriter) {
        return new IntakeService.LifecycleHook() {
            @Override
            public void onBotEvent(EventEnvelope envelope, BotEventPayload payload) {
                if (cn.edu.scut.chatbot.contract.in.BotEventPayload.SUB_BOT_KICKED.equals(payload.subType())
                        && envelope.groupId() != null) {
                    lifecycleManager.onBotKicked(envelope.platform(), envelope.botId(), envelope.groupId());
                    return;
                }
                log.info("BotEvent 到达 subType={}（除被踢外无生命周期联动）", payload.subType());
                transcriptIntakeWriter.writeBotEvent(envelope, payload);
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
                                           cn.edu.scut.chatbot.memory.persona.PersonaResolver personaResolver,
                                           cn.edu.scut.chatbot.engine.session.AgentSessionMapper sessionMapper,
                                           cn.edu.scut.chatbot.engine.session.TranscriptCodec transcriptCodec,
                                           cn.edu.scut.chatbot.memory.social.GroupStateService groupStateService,
                                           cn.edu.scut.chatbot.memory.profile.AffinityService affinityService,
                                           cn.edu.scut.chatbot.memory.persona.PersonaPreferenceDigestRenderer preferenceRenderer,
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
                    // 近期群消息投影（transcript 倒序 → 正序呈现；user 条目译为可读行而非 payload JSON）
                    var session = sessionMapper.selectByGroup(platform, botId, groupId);
                    if (session == null) {
                        return List.of();
                    }
                    var recent = sessionMapper.selectRecent(session.getId(), limit);
                    List<String> lines = new java.util.ArrayList<>();
                    for (int i = recent.size() - 1; i >= 0; i--) {
                        var entry = recent.get(i);
                        if (!"user".equals(entry.getKind())) {
                            continue;
                        }
                        try {
                            var message = transcriptCodec.decode(entry.getPayload(), entry.getKind());
                            if (message instanceof cn.edu.scut.chatbot.loop.message.UserMessage userMessage) {
                                lines.add(userLine(userMessage));
                            }
                        } catch (Exception e) {
                            log.warn("L1 近期窗口条目解码失败跳过 entryId={}", entry.getId(), e);
                        }
                    }
                    return lines;
                },
                // 人格偏好摘要（T026 渲染器——健谈度等数值 + 当前情绪，评估时现渲染）
                (platform, botId, groupId) -> preferenceRenderer.render(
                        new cn.edu.scut.chatbot.bootstrap.GroupKey(platform, botId, groupId)),
                // 群状态摘要：评估时现渲染（纯渲染零模型）
                (platform, botId, groupId) -> {
                    var key = new cn.edu.scut.chatbot.bootstrap.GroupKey(platform, botId, groupId);
                    return groupStateService.renderDigest(groupStateService.read(key));
                },
                // 发言人态度档位：无感返回 null（不标注）
                (platform, botId, groupId, sender) -> {
                    var tier = affinityService.view(
                            new cn.edu.scut.chatbot.bootstrap.GroupKey(platform, botId, groupId), sender).tier();
                    return tier.significant() ? tier.label() : null;
                },
                // 人格名解析：替换硬编码「小趣」（resolve 失败回默认人格是 Resolver 既有语义）
                (platform, botId, groupId) -> personaResolver
                        .resolve(platform, botId, groupId).getName(),
                clock,
                systemParamService.getInt("gate.l1_max_retry", 3),
                systemParamService.getLong("gate.l1_backoff_base_millis", 500));
    }

    /**
     * 群友消息 → L1 评估用可读行（发言人 + 文本/提及；图片等非文本段以占位符呈现）
     */
    private static String userLine(cn.edu.scut.chatbot.loop.message.UserMessage message) {
        StringBuilder text = new StringBuilder();
        for (var part : message.parts()) {
            if (part instanceof cn.edu.scut.chatbot.contract.message.TextPart textPart) {
                text.append(textPart.text());
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.AtPart atPart) {
                text.append("@").append(atPart.targetUserId());
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.ImagePart) {
                text.append("[图片]");
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.AudioPart) {
                text.append("[语音]");
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.VideoPart) {
                text.append("[视频]");
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.FacePart) {
                text.append("[表情]");
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.QuotePart) {
                text.append("[引用消息]");
            }
        }
        String sender = message.senderUserId() != null ? message.senderUserId() : "匿名";
        return sender + "：" + (text.isEmpty() ? "[非文本消息]" : text);
    }
}
