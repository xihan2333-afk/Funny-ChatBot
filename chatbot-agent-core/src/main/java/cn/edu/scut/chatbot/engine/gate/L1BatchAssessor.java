package cn.edu.scut.chatbot.engine.gate;

import cn.edu.scut.chatbot.engine.EngineErrorCodes;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.exception.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * L1 批量注意力评估器（spec US2）：同群全部 pending 触发器一个事务合并成批、
 * 一次模型调用批量判定；失败指数退避重试，重试耗尽保守 ignore 且 decision_layer=degrade
 * （评估失败不放大为系统故障——降级是保守安全侧）。
 *
 * <p>有界上下文三路取数（research R6）：近期消息投影、本批触发器、人格注意力偏好摘要。
 * 前者与人格经取数接口注入（transcript 与 persona 的 mapper 分别在 US3/US6 就位后挂接，
 * 期间为空实现——评估仍可运行，上下文少一路数据而已）。装配见 EngineConfiguration。
 */
public class L1BatchAssessor {

    private static final Logger log = LoggerFactory.getLogger(L1BatchAssessor.class);

    /**
     * 近期消息窗口大小（research R6：默认最近 40 条投影）
     */
    private static final int RECENT_WINDOW = 40;

    /**
     * L1 评估提示词取数接口（T088 挂接 prompt_fragment 的 standalone 层；
     * 默认实现返回内置模板）
     */
    @FunctionalInterface
    public interface AssessmentPromptSupplier {

        String supply(String personaName);
    }

    /**
     * 近期群消息投影取数接口（US3 TranscriptEntryMapper 就位后挂接倒序投影）
     */
    @FunctionalInterface
    public interface RecentTranscriptSupplier {

        List<String> recentLines(String platform, String botId, String groupId, int limit);
    }

    /**
     * 人格注意力偏好摘要取数接口（US6 PersonaResolver 就位后挂接 persona 行直取）
     */
    @FunctionalInterface
    public interface PersonaPreferenceSupplier {

        String preferenceText(String platform, String botId, String groupId);
    }

    /**
     * 群状态摘要取数接口（US8 GroupStateService.renderDigest 挂接——评估时现渲染）
     */
    @FunctionalInterface
    public interface GroupStateDigestSupplier {

        String groupStateText(String platform, String botId, String groupId);
    }

    /**
     * 发言人态度档位取数接口（US4 AffinityService 挂接——返回 null 表示无感不标注）
     */
    @FunctionalInterface
    public interface AttitudeAnnotator {

        String tierLabel(String platform, String botId, String groupId, String senderUserId);
    }

    /**
     * 当前人格名取数接口（T027 修复 promptSupplier「小趣」硬编码——
     * 经 PersonaResolver 解析当前人格，解析失败回默认人格是 Resolver 既有语义）
     */
    @FunctionalInterface
    public interface PersonaNameSupplier {

        String personaName(String platform, String botId, String groupId);
    }

    private final TriggerEventMapper triggerEventMapper;
    private final L1AssessmentModel model;
    private final AssessmentPromptSupplier promptSupplier;
    private final RecentTranscriptSupplier recentTranscriptSupplier;
    private final PersonaPreferenceSupplier personaPreferenceSupplier;
    private final GroupStateDigestSupplier groupStateDigestSupplier;
    private final AttitudeAnnotator attitudeAnnotator;
    private final PersonaNameSupplier personaNameSupplier;
    private final Clock clock;
    private final int maxRetry;
    private final long backoffBaseMillis;

    public L1BatchAssessor(TriggerEventMapper triggerEventMapper,
                           L1AssessmentModel model,
                           AssessmentPromptSupplier promptSupplier,
                           RecentTranscriptSupplier recentTranscriptSupplier,
                           PersonaPreferenceSupplier personaPreferenceSupplier,
                           GroupStateDigestSupplier groupStateDigestSupplier,
                           AttitudeAnnotator attitudeAnnotator,
                           PersonaNameSupplier personaNameSupplier,
                           Clock clock,
                           int maxRetry,
                           long backoffBaseMillis) {
        this.triggerEventMapper = triggerEventMapper;
        this.model = model;
        this.promptSupplier = promptSupplier;
        this.recentTranscriptSupplier = recentTranscriptSupplier;
        this.personaPreferenceSupplier = personaPreferenceSupplier;
        this.groupStateDigestSupplier = groupStateDigestSupplier;
        this.attitudeAnnotator = attitudeAnnotator;
        this.personaNameSupplier = personaNameSupplier;
        this.clock = clock;
        this.maxRetry = maxRetry;
        this.backoffBaseMillis = backoffBaseMillis;
    }

    /**
     * 执行该群的 L1 批量评估（assessment Effect 的动作体）。
     * 评估失败在本方法内退避重试并最终降级——调用方（Effects 执行器）拿到的一律是完成。
     */
    public void assessGroup(String platform, String botId, String groupId) {
        List<TriggerEventDO> pending = triggerEventMapper.selectPendingByGroup(platform, botId, groupId);
        if (pending.isEmpty()) {
            return;   // 已被其他批次消化——Effects 同 key 合并的正常结局
        }

        List<L1AssessmentModel.TriggerDigest> digests = pending.stream()
                .map(trigger -> new L1AssessmentModel.TriggerDigest(
                        trigger.getDedupKey(), trigger.getEventType(), trigger.getDigest()))
                .toList();
        List<String> recentLines = annotateAttitudes(
                recentTranscriptSupplier.recentLines(platform, botId, groupId, RECENT_WINDOW),
                platform, botId, groupId);
        String preference = personaPreferenceSupplier.preferenceText(platform, botId, groupId);
        String groupStateDigest = groupStateDigestSupplier.groupStateText(platform, botId, groupId);
        String prompt = promptSupplier.supply(personaNameSupplier.personaName(platform, botId, groupId));

        List<L1AssessmentModel.AssessedDecision> decisions = callWithRetry(
                prompt, digests, recentLines, preference, groupStateDigest);

        if (decisions == null) {
            // 重试耗尽：保守 ignore + degrade 层标记（评估失败不放大为系统故障）
            degradeAll(pending);
            return;
        }

        String batchDigest = buildBatchDigest(decisions);
        for (L1AssessmentModel.AssessedDecision decision : decisions) {
            TriggerEventDO matched = pending.stream()
                    .filter(trigger -> trigger.getDedupKey().equals(decision.dedupKey()))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                continue;   // 模型幻觉出的 key：忽略不落档
            }
            String normalized = "accept".equals(decision.decision()) ? "accept" : "ignore";
            triggerEventMapper.updateDecision(matched.getId(), normalized, TriggerEventDO.LAYER_L1,
                    decision.reason(), batchDigest, java.time.LocalDateTime.now(clock));
        }
    }

    /**
     * 指数退避重试：失败重试至 maxRetry 次，全部失败返回 null（降级路径）
     */
    private List<L1AssessmentModel.AssessedDecision> callWithRetry(
            String prompt, List<L1AssessmentModel.TriggerDigest> digests,
            List<String> recentLines, String preference, String groupStateDigest) {
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                return model.assess(prompt, digests, recentLines, preference, groupStateDigest);
            } catch (Exception e) {
                if (attempt == maxRetry) {
                    log.error("L1 评估重试耗尽（保守 ignore 降级） errorCode={} "
                                    + "triggerCount={} attempts={}", EngineErrorCodes.GATE_L1_CALL_FAILED,
                            digests.size(), attempt + 1, e);
                    return null;
                }
                long backoff = backoffBaseMillis * (1L << attempt);
                log.warn("L1 评估失败退避重试（第 {} 次，等 {}ms） errorCode={}",
                        attempt + 1, backoff, EngineErrorCodes.GATE_L1_CALL_FAILED);
                sleepQuietly(backoff);
            }
        }
        return null;
    }

    /**
     * 档位标注：近期消息行「userId：文本」的显著态度发言人行首注入「[档位]」——
     * 无感（annotator 返回 null）不标注；调制语义（挚友更易触发、厌恶者非 @ 更易沉默）
     * 由模型消费，L0 强制回应不被豁免。
     */
    private List<String> annotateAttitudes(List<String> recentLines,
                                           String platform, String botId, String groupId) {
        List<String> annotated = new ArrayList<>(recentLines.size());
        for (String line : recentLines) {
            int separator = line.indexOf('：');
            if (separator <= 0) {
                annotated.add(line);
                continue;
            }
            String sender = line.substring(0, separator);
            String tierLabel;
            try {
                tierLabel = attitudeAnnotator.tierLabel(platform, botId, groupId, sender);
            } catch (Exception e) {
                tierLabel = null;   // 画像取数失败不拦评估
            }
            annotated.add(tierLabel == null ? line : "[" + tierLabel + "]" + line);
        }
        return annotated;
    }

    private void degradeAll(List<TriggerEventDO> pending) {
        for (TriggerEventDO trigger : pending) {
            triggerEventMapper.updateDecision(trigger.getId(), TriggerEventDO.DECISION_IGNORE,
                    TriggerEventDO.LAYER_DEGRADE, "评估失败，保守忽略（宁可不说也不错说）",
                    "degraded-batch-" + pending.size(), java.time.LocalDateTime.now(clock));
        }
    }

    /**
     * 批次摘要：一次评估合并的全部触发器概览（数据飞轮原料——观测哪类触发常被接受）
     */
    private String buildBatchDigest(List<L1AssessmentModel.AssessedDecision> decisions) {
        StringBuilder builder = new StringBuilder("{\"total\":").append(decisions.size())
                .append(",\"accept\":");
        List<String> accepted = new ArrayList<>();
        for (L1AssessmentModel.AssessedDecision decision : decisions) {
            if ("accept".equals(decision.decision())) {
                accepted.add(decision.dedupKey());
            }
        }
        builder.append(accepted.size()).append(",\"acceptedKeys\":").append(accepted).append('}');
        return builder.toString();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException(EngineErrorCodes.GATE_L1_CALL_FAILED, "L1 退避等待被中断", e);
        }
    }
}
