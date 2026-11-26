package cn.edu.scut.chatbot.observability.replay;

import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import cn.edu.scut.chatbot.observability.eval.EvalCaseDO;
import cn.edu.scut.chatbot.observability.eval.ScenarioCard;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 重放隔离基建（eval-and-flywheel 契约第 4 节、R10/R11、T053）：按场景卡在
 * <b>派生三元组</b>下准备重放环境——真实群状态 / 记忆 / 画像零接触（隔离不靠
 * 清理真实数据，靠把重放放进另一个群）。
 *
 * <p>派生规则：platform 加 {@code replay::} 前缀 + groupId 加 case 后缀
 * （种子题三元组可空——用 default/case 兜底）。群配置按默认值 upsert（黑名单空、
 * 不允许 member/autonomous——宁严勿松的既有默认），人格按场景卡 preloadedState 绑定，
 * 前情脚本构造为 transcript_entry 种子（source_ref=replay-seed——经既有转录投影
 * 进窗口，上下文形态与线上一致；Pass@k 每次执行前清掉非种子转录保 k 次独立）。
 */
@Component
public class ReplayGroupProvisioner {

    /**
     * 前情种子的 source_ref 标记（清场时保留）
     */
    public static final String SEED_SOURCE_REF = "replay-seed";

    private final GroupMapper groupMapper;
    private final GroupConfigMapper groupConfigMapper;
    private final SessionManager sessionManager;
    private final AgentSessionMapper sessionMapper;
    private final Clock clock;

    public ReplayGroupProvisioner(GroupMapper groupMapper,
                                  GroupConfigMapper groupConfigMapper,
                                  SessionManager sessionManager,
                                  AgentSessionMapper sessionMapper,
                                  Clock clock) {
        this.groupMapper = groupMapper;
        this.groupConfigMapper = groupConfigMapper;
        this.sessionManager = sessionManager;
        this.sessionMapper = sessionMapper;
        this.clock = clock;
    }

    /**
     * 派生三元组
     */
    public record ReplayTriple(String platform, String botId, String groupId) {
    }

    /**
     * 派生三元组（platform 加 replay:: 前缀、groupId 加 case 后缀）
     */
    public static ReplayTriple deriveTriple(EvalCaseDO caze) {
        String platform = "replay::" + (caze.getPlatform() != null ? caze.getPlatform() : "default");
        String botId = caze.getBotId() != null ? caze.getBotId() : "replay";
        String groupBase = caze.getGroupId() != null ? caze.getGroupId() : "case";
        return new ReplayTriple(platform, botId, groupBase + "-eval-" + caze.getId());
    }

    /**
     * 准备重放环境：建派生群与配置 → 建会话 → 绑人格 → 种前情脚本。
     * 幂等：前情已种（存在 run_id 为空的种子条目）则直接复用。
     */
    public PreparedReplay provision(EvalCaseDO caze, ScenarioCard scenario) {
        ReplayTriple triple = deriveTriple(caze);

        groupMapper.upsertDiscovered(triple.platform(), triple.botId(), triple.groupId(), null);
        groupMapper.updateInService(triple.platform(), triple.botId(), triple.groupId(), 1);
        groupMapper.updateLifecycle(triple.platform(), triple.botId(), triple.groupId(),
                GroupDO.LIFECYCLE_ACTIVE);
        groupConfigMapper.upsertDefault(triple.platform(), triple.botId(), triple.groupId());

        AgentSessionDO session = sessionManager.getOrCreateSession(
                triple.platform(), triple.botId(), triple.groupId());
        if (scenario.preloadedState() != null && scenario.preloadedState().personaId() != null) {
            sessionMapper.updatePersona(session.getId(), scenario.preloadedState().personaId());
            session.setPersonaId(scenario.preloadedState().personaId());
        }

        // 前情种子经 appendTranscript(runId=null) 落库——run_id 为空即种子（幂等判定）
        boolean seeded = sessionMapper.selectOrdered(session.getId()).stream()
                .anyMatch(entry -> entry.getRunId() == null);
        if (!seeded) {
            sessionManager.appendTranscript(session, seedMessages(scenario), null);
        }
        return new PreparedReplay(triple, session);
    }

    /**
     * Pass@k 每次执行前清场：删非种子转录（上次重放的发言与工具结果），前情保留
     */
    public void resetTransient(AgentSessionDO session) {
        sessionMapper.deleteReplayTransient(session.getId());
    }

    /**
     * 前情脚本 → 转录消息（speaker=agent 译 assistant，其余译 user；occurredAt
     * 递增保序，sourceRef 统一 replay-seed 供清场识别）
     */
    private List<AgentMessage> seedMessages(ScenarioCard scenario) {
        List<AgentMessage> messages = new ArrayList<>();
        Instant base = clock.instant().minusSeconds(3600);
        int index = 0;
        for (ScenarioCard.ScriptLine line : scenario.priorScript()) {
            Instant occurredAt = base.plusSeconds(index++);
            if ("agent".equals(line.speaker())) {
                messages.add(new AssistantMessage("seed-" + index, occurredAt, 60,
                        SEED_SOURCE_REF, line.text(), List.of(), "stop", null));
            } else {
                messages.add(new UserMessage("seed-" + index, occurredAt, 50,
                        SEED_SOURCE_REF, line.speaker(),
                        List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                                cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT, line.text()))));
            }
        }
        return messages;
    }

    /**
     * 准备产物：派生三元组 + 就绪会话
     */
    public record PreparedReplay(ReplayTriple triple, AgentSessionDO session) {
    }
}
