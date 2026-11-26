package cn.edu.scut.chatbot.observability.flywheel;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 提案管线实现（eval-and-flywheel 契约第 5 节、T058）。状态机迁移校验：
 * pending →(attribute)→ attributed →(accept，仅 agent)→ accepted →(markLanded)→ landed；
 * pending/attributed 可 → rejected。归因三分支同 eval 口径——只有 agent 进 accepted
 * （caseMiss / rubricError 分别回补评测集与 Rubric，不产生 Agent 改动）。
 *
 * <p>三纪律：只认统计信号与证据（问题卡 evidence 必填）、人工确认才生效（本服务
 * 不改任何配置——落点编辑走既有 content_version 机制）、同证据去重（dedup_key
 * 先查后拒）。
 */
@Service
public class ProposalServiceImpl implements ProposalService {

    private static final Set<String> VALID_TARGETS = Set.of(
            "promptFragment", "personaParam", "skill", "l1Prompt", "toolConfig");

    private final ImprovementProposalMapper proposalMapper;
    private final IdGenerator idGenerator;

    public ProposalServiceImpl(ImprovementProposalMapper proposalMapper, IdGenerator idGenerator) {
        this.proposalMapper = proposalMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public long createFromMining(ProblemCard card) {
        if (card == null || isBlank(card.description()) || isBlank(card.suggestTarget())
                || !VALID_TARGETS.contains(card.suggestTarget())) {
            throw new IllegalArgumentException("问题卡必须给齐 description 与五类落点之一的 suggestTarget");
        }
        if (card.dedupKey() != null && proposalMapper.selectByDedupKey(card.dedupKey()) != null) {
            throw new IllegalArgumentException("同证据提案已存在（dedupKey=" + card.dedupKey() + "）——去重拒绝");
        }
        ImprovementProposalDO row = new ImprovementProposalDO();
        row.setId(idGenerator.nextId());
        row.setSourceKind(ImprovementProposalDO.SOURCE_MANUAL);
        var cardJson = JsonMappers.shared().createObjectNode();
        cardJson.put("description", card.description());
        cardJson.set("evidence", JsonMappers.shared().valueToTree(
                card.evidence() == null ? List.of() : card.evidence()));
        cardJson.put("suggestTarget", card.suggestTarget());
        cardJson.put("expectedEffect", card.expectedEffect() == null ? "" : card.expectedEffect());
        row.setProblemCard(cardJson.toString());
        row.setTarget(card.suggestTarget());
        row.setStatus(ImprovementProposalDO.STATUS_PENDING);
        row.setDedupKey(card.dedupKey());
        proposalMapper.insert(row);
        return row.getId();
    }

    @Override
    public void attribute(long proposalId, String attribution) {
        if (!ImprovementProposalDO.ATTRIBUTION_AGENT.equals(attribution)
                && !ImprovementProposalDO.ATTRIBUTION_CASE_MISS.equals(attribution)
                && !ImprovementProposalDO.ATTRIBUTION_RUBRIC_ERROR.equals(attribution)) {
            throw new IllegalArgumentException("归因必须是 agent / caseMiss / rubricError：" + attribution);
        }
        ImprovementProposalDO row = require(proposalId);
        requireStatus(row, ImprovementProposalDO.STATUS_PENDING, "归因");
        proposalMapper.updateAttribution(proposalId, ImprovementProposalDO.STATUS_ATTRIBUTED, attribution);
    }

    @Override
    public void accept(long proposalId) {
        ImprovementProposalDO row = require(proposalId);
        requireStatus(row, ImprovementProposalDO.STATUS_ATTRIBUTED, "接受");
        if (!ImprovementProposalDO.ATTRIBUTION_AGENT.equals(row.getAttribution())) {
            // 只走第一条会把 Agent 优化成迎合评分标准——caseMiss/rubricError 不进 Agent 改动
            throw new IllegalArgumentException("只有归因为 agent 的提案可接受（当前 "
                    + row.getAttribution() + "——caseMiss 回补评测集、rubricError 修订 Rubric）");
        }
        proposalMapper.updateStatus(proposalId, ImprovementProposalDO.STATUS_ACCEPTED, null);
    }

    @Override
    public void reject(long proposalId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("拒绝必须留理由（留痕纪律）");
        }
        ImprovementProposalDO row = require(proposalId);
        if (!ImprovementProposalDO.STATUS_PENDING.equals(row.getStatus())
                && !ImprovementProposalDO.STATUS_ATTRIBUTED.equals(row.getStatus())) {
            throw new IllegalArgumentException("状态 " + row.getStatus() + " 不可拒绝（仅 pending / attributed）");
        }
        proposalMapper.updateStatus(proposalId, ImprovementProposalDO.STATUS_REJECTED, reason);
    }

    @Override
    public void markLanded(long proposalId, long contentVersionId) {
        ImprovementProposalDO row = require(proposalId);
        requireStatus(row, ImprovementProposalDO.STATUS_ACCEPTED, "落地");
        proposalMapper.updateStatus(proposalId, ImprovementProposalDO.STATUS_LANDED,
                "contentVersion#" + contentVersionId);
    }

    @Override
    public ProposalView getProposal(long proposalId) {
        return ProposalView.from(require(proposalId));
    }

    @Override
    public List<ProposalView> listProposals(String status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        List<ProposalView> views = new ArrayList<>();
        for (ImprovementProposalDO row : proposalMapper.selectByFilter(status, safePage * safeSize, safeSize)) {
            views.add(ProposalView.from(row));
        }
        return views;
    }

    private ImprovementProposalDO require(long proposalId) {
        ImprovementProposalDO row = proposalMapper.selectById(proposalId);
        if (row == null) {
            throw new IllegalArgumentException("提案不存在：" + proposalId);
        }
        return row;
    }

    private static void requireStatus(ImprovementProposalDO row, String expected, String action) {
        if (!expected.equals(row.getStatus())) {
            throw new IllegalArgumentException("状态 " + row.getStatus() + " 不可" + action
                    + "（要求 " + expected + "）——状态机迁移校验");
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
