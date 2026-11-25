package cn.edu.scut.chatbot.memory.emotion;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import cn.edu.scut.chatbot.memory.persona.PersonaRuntime;
import cn.edu.scut.chatbot.memory.profile.AffinityService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 情绪服务实现。纪律落位：读是纯函数（拨时钟可测、零定时器）；写双通道共用钳制
 * （单次与日累计——防操纵）；规则通道双落点（情绪 + 主体好感）；情绪永不进权限判定。
 */
@Component
public class EmotionServiceImpl implements EmotionService {

    private static final Logger log = LoggerFactory.getLogger(EmotionServiceImpl.class);

    private final AgentSessionMapper sessionMapper;
    private final GroupMapper groupMapper;
    private final PersonaResolver personaResolver;
    private final EmotionLogMapper emotionLogMapper;
    private final AffinityService affinityService;
    private final SystemParamService systemParams;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public EmotionServiceImpl(AgentSessionMapper sessionMapper,
                              GroupMapper groupMapper,
                              PersonaResolver personaResolver,
                              EmotionLogMapper emotionLogMapper,
                              AffinityService affinityService,
                              SystemParamService systemParams,
                              IdGenerator idGenerator,
                              Clock clock) {
        this.sessionMapper = sessionMapper;
        this.groupMapper = groupMapper;
        this.personaResolver = personaResolver;
        this.emotionLogMapper = emotionLogMapper;
        this.affinityService = affinityService;
        this.systemParams = systemParams;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public EmotionState read(GroupKey group) {
        EmotionParams params = EmotionParams.load(systemParams);
        PersonaRuntime persona = currentPersona(group);
        EmotionState state = loadState(group, persona);
        double effective = decayedIntensity(state, pausedAdjustedMillis(group), params);
        String baseLabel = baseLabelOf(state, persona);
        if (effective < params.baselineThreshold()) {
            // 低于阈值视为基线情绪（label 回归、强度保留有效值——预算减半等判定读它）
            return new EmotionState(baseLabel, (int) Math.round(effective), null,
                    state.updatedAt(), baseLabel);
        }
        return new EmotionState(state.label(), (int) Math.round(effective), state.cause(),
                state.updatedAt(), baseLabel);
    }

    @Override
    public boolean applyRuleDelta(GroupKey group, String eventKind, String subjectUserId) {
        PersonaRuntime persona = currentPersona(group);
        Integer override = persona.emotion().structuredEventOverrides().get(eventKind);
        Integer defaultDelta = ruleTable().get(eventKind);
        Integer ruleDelta = override != null ? override : defaultDelta;
        if (ruleDelta == null || ruleDelta == 0) {
            return true;   // 无此事件类型（或人格覆盖为零——钝感）零成本返回
        }
        // delta × 人格敏感度系数（系数为零规则通道全归零——钝感人格）
        int rawDelta = (int) Math.round(ruleDelta * persona.emotion().sensitivity());
        boolean applied = applyDelta(group, persona, rawDelta, null,
                EmotionLogDO.SOURCE_RULE, null);
        if (applied && subjectUserId != null && !subjectUserId.isBlank()) {
            // 一次事件双落点：对主体的好感 delta 顺带落画像（熟悉度只增：负面事件不减）
            int magnitude = Math.min(3, Math.abs(ruleDelta));
            affinityService.applyEventDelta(group, subjectUserId,
                    Math.max(0, ruleDelta > 0 ? magnitude : 0), ruleDelta);
        }
        return applied;
    }

    @Override
    public boolean applySelfReport(GroupKey group, String moodLabel, int intensityDelta, String cause) {
        PersonaRuntime persona = currentPersona(group);
        // 词汇集校验：人格情绪词汇集（空则内置默认八标签兜底）之外拒绝——
        // 不应用、不进发言、不泄漏到群（工具层把异常转为错误结果）
        java.util.List<String> vocabularies = persona.emotion().vocabularies().isEmpty()
                ? EmotionParams.DEFAULT_VOCABULARIES : persona.emotion().vocabularies();
        if (moodLabel == null || !vocabularies.contains(moodLabel)) {
            throw new IllegalArgumentException(
                    "情绪标签不在词汇集内（可用：" + String.join("、", vocabularies) + "）");
        }
        return applyDelta(group, persona, intensityDelta, moodLabel,
                EmotionLogDO.SOURCE_SELF_REPORT, cause);
    }

    @Override
    public EmotionState view(GroupKey group) {
        return read(group);
    }

    @Override
    public void resetToBaseline(GroupKey group) {
        PersonaRuntime persona = currentPersona(group);
        LocalDateTime now = LocalDateTime.now(clock);
        EmotionState baseline = EmotionState.baseline(baseLabelOf(null, persona), now);
        persist(group, baseline, 0, EmotionLogDO.SOURCE_SELF_REPORT, "管理面重置回基线");
    }

    // ===== 共用底座 =====

    /**
     * 双通道共用的写路径：单次钳制 + 日累计封顶 + 落库 + emotion_log 顺记
     */
    private boolean applyDelta(GroupKey group, PersonaRuntime persona, int rawDelta,
                               String newLabel, String source, String cause) {
        EmotionParams params = EmotionParams.load(systemParams);
        int delta = Math.max(-params.singleDeltaMax(), Math.min(params.singleDeltaMax(), rawDelta));
        if (delta == 0) {
            return true;
        }
        // 防操纵：当日累计封顶——群友轮番刷情绪也只能推动有限幅度
        long todayAbs = emotionLogMapper.sumAbsDeltaSince(group.platform(), group.botId(),
                group.groupId(), LocalDateTime.now(clock).toLocalDate().atStartOfDay());
        if (todayAbs + Math.abs(delta) > params.dailyAccumulateMax()) {
            log.warn("情绪日累计封顶拒绝应用 group={} 当日累计={} delta={}",
                    group.groupId(), todayAbs, delta);
            return false;
        }
        EmotionState current = read(group);
        String baseLabel = baseLabelOf(current, persona);
        int newIntensity = (int) Math.max(0, Math.min(100,
                Math.round(current.intensity()) + delta));
        String label = newLabel != null ? newLabel
                : (newIntensity < params.baselineThreshold() ? baseLabel : current.label());
        EmotionState next = new EmotionState(label, newIntensity,
                cause != null ? cause : current.cause(), LocalDateTime.now(clock), baseLabel);
        persist(group, next, delta, source, cause);
        return true;
    }

    private void persist(GroupKey group, EmotionState state, int delta,
                         String source, String cause) {
        AgentSessionDO session = sessionMapper.selectByGroup(
                group.platform(), group.botId(), group.groupId());
        if (session == null) {
            log.warn("会话不存在，情绪变更丢弃 group={}", group.groupId());
            return;
        }
        PersonaRuntime persona = currentPersona(group);
        sessionMapper.updateEmotion(session.getId(), state.toJson());
        EmotionLogDO logRow = new EmotionLogDO();
        logRow.setId(idGenerator.nextId());
        logRow.setPlatform(group.platform());
        logRow.setBotId(group.botId());
        logRow.setGroupId(group.groupId());
        logRow.setPersonaId(persona.personaId());
        logRow.setLabel(state.label());
        logRow.setIntensityAfter(state.intensity());
        logRow.setDelta(delta);
        logRow.setSource(source);
        logRow.setCause(cause);
        logRow.setOccurredAt(LocalDateTime.now(clock));
        emotionLogMapper.insertLog(logRow);
    }

    private EmotionState loadState(GroupKey group, PersonaRuntime persona) {
        AgentSessionDO session = sessionMapper.selectByGroup(
                group.platform(), group.botId(), group.groupId());
        EmotionState stored = session == null ? null : EmotionState.fromJson(session.getEmotion());
        if (stored == null) {
            // 无记录（新会话 / 旧数据坏 / 崩溃恢复）——惰性初始化为人格基线
            return EmotionState.baseline(baseLabelOf(null, persona), LocalDateTime.now(clock));
        }
        String baseLabel = stored.baseLabel() != null ? stored.baseLabel()
                : baseLabelOf(null, persona);
        return new EmotionState(stored.label(), stored.intensity(), stored.cause(),
                stored.updatedAt(), baseLabel);
    }

    /**
     * 懒衰减纯函数：effective = intensity × 0.5^(顺延后小时数 / 半衰期)。
     * updatedAt 为空视为很久以前（重启后心情平复）；暂停时长不计入衰减（计时顺延）
     */
    private double decayedIntensity(EmotionState state, long pausedMillis, EmotionParams params) {
        if (state.updatedAt() == null) {
            return 0;   // 无基准——自然回基线
        }
        Duration elapsed = Duration.between(state.updatedAt(), LocalDateTime.now(clock));
        long adjustedMillis = Math.max(0, elapsed.toMillis() - pausedMillis);
        double hours = adjustedMillis / (3600.0 * 1000);
        return state.intensity() * Math.pow(0.5, hours / params.halfLifeHours());
    }

    /**
     * 群暂停顺延：已累计暂停时长 + 当前进行中的暂停段（一切计时以业务时钟为准）
     */
    private long pausedAdjustedMillis(GroupKey group) {
        GroupDO groupRow = groupMapper.selectByGroup(
                group.platform(), group.botId(), group.groupId());
        if (groupRow == null) {
            return 0;
        }
        long accumulated = groupRow.getPausedAccumulatedMillis() == null
                ? 0 : groupRow.getPausedAccumulatedMillis();
        if (groupRow.getPausedAt() != null) {
            accumulated += Math.max(0, Duration.between(
                    groupRow.getPausedAt(), LocalDateTime.now(clock)).toMillis());
        }
        return accumulated;
    }

    private PersonaRuntime currentPersona(GroupKey group) {
        PersonaDO persona = personaResolver.resolve(
                group.platform(), group.botId(), group.groupId());
        return PersonaRuntime.parse(persona);
    }

    private String baseLabelOf(EmotionState state, PersonaRuntime persona) {
        if (state != null && state.baseLabel() != null) {
            return state.baseLabel();
        }
        return persona.emotion().baseLabel() != null ? persona.emotion().baseLabel()
                : EmotionParams.DEFAULT_BASELINE_LABEL;
    }

    /**
     * 默认规则表（system_param memory.emotion.rules，{eventKind: delta} 扁平形态；
     * 人格覆盖在 PersonaRuntime.structuredEventOverrides——按事件类型覆盖或追加）
     */
    private Map<String, Integer> ruleTable() {
        String raw = systemParams.getString("memory.emotion.rules", null);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(raw);
            java.util.Map<String, Integer> rules = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                    rules.put(entry.getKey(), entry.getValue().asInt(0)));
            return rules;
        } catch (Exception e) {
            log.warn("memory.emotion.rules 解析失败按空表处理：{}", e.getMessage());
            return Map.of();
        }
    }
}
