package cn.edu.scut.chatbot.memory.persona;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.*;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.management.OverviewController;
import cn.edu.scut.chatbot.observability.flywheel.PublishGateService;
import cn.edu.scut.chatbot.prompt.ContentVersionDO;
import cn.edu.scut.chatbot.prompt.PromptFragmentMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 人格库与演进提案管理服务（A3）：
 * 七组字段管理、发布与回滚门禁、绑定群与切换历史、演进提案审阅（accept/edit_and_accept/reject）与人工反馈信号录入。
 */
@Service
public class PersonaAdminService implements OverviewController.OverviewExtensionStats {

    private final PersonaMapper personaMapper;
    private final PersonaVersioningService versioningService;
    private final PersonaEvolutionProposalMapper proposalMapper;
    private final PersonaAdminFeedbackMapper feedbackMapper;
    private final EvolutionPipeline evolutionPipeline;
    private final PublishGateService publishGateService;
    private final PromptFragmentMapper fragmentMapper;
    private final GroupMapper groupMapper;
    private final GroupConfigMapper groupConfigMapper;
    private final GroupPersonaHistoryMapper historyMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public PersonaAdminService(PersonaMapper personaMapper,
                               PersonaVersioningService versioningService,
                               PersonaEvolutionProposalMapper proposalMapper,
                               PersonaAdminFeedbackMapper feedbackMapper,
                               EvolutionPipeline evolutionPipeline,
                               PublishGateService publishGateService,
                               PromptFragmentMapper fragmentMapper,
                               GroupMapper groupMapper,
                               GroupConfigMapper groupConfigMapper,
                               GroupPersonaHistoryMapper historyMapper,
                               IdGenerator idGenerator,
                               Clock clock) {
        this.personaMapper = personaMapper;
        this.versioningService = versioningService;
        this.proposalMapper = proposalMapper;
        this.feedbackMapper = feedbackMapper;
        this.evolutionPipeline = evolutionPipeline;
        this.publishGateService = publishGateService;
        this.fragmentMapper = fragmentMapper;
        this.groupMapper = groupMapper;
        this.groupConfigMapper = groupConfigMapper;
        this.historyMapper = historyMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public Integer countPendingPersonaProposals() {
        int count = 0;
        for (PersonaDO p : personaMapper.selectAllPersonas()) {
            for (var prop : proposalMapper.selectByPersona(p.getId())) {
                if (PersonaEvolutionProposalDO.STATUS_PENDING_REVIEW.equals(prop.getStatus())) {
                    count++;
                }
            }
        }
        return count;
    }

    public List<PersonaSummaryView> listPersonas() {
        List<GroupDO> groups = groupMapper.selectAll();
        Map<Long, Integer> boundCounts = new HashMap<>();
        for (GroupDO g : groups) {
            GroupConfigDO cfg = groupConfigMapper.selectByGroup(g.getPlatform(), g.getBotId(), g.getGroupId());
            if (cfg != null && cfg.getPersonaId() != null) {
                boundCounts.merge(cfg.getPersonaId(), 1, Integer::sum);
            }
        }
        return personaMapper.selectAllPersonas().stream()
                .map(p -> new PersonaSummaryView(
                        p.getId(), p.getName(),
                        p.getIsDefault() != null && p.getIsDefault() == 1,
                        p.getStatus(),
                        p.getVersionNo() != null ? p.getVersionNo() : 1,
                        boundCounts.getOrDefault(p.getId(), 0),
                        p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null))
                .toList();
    }

    public PersonaDetailView getPersona(long id) {
        PersonaDO p = requirePersona(id);
        return new PersonaDetailView(
                p.getId(), p.getName(), p.getDefinition(),
                p.getIsDefault() != null && p.getIsDefault() == 1,
                p.getStatus(), p.getVersionNo() != null ? p.getVersionNo() : 1,
                p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);
    }

    @Transactional
    public DraftResult saveDraft(Long id, PersonaUpsertRequest req) {
        validateDefinitionJson(req.definitionJson());
        if (id == null) {
            if (req.name() == null || req.name().isBlank()) {
                throw new IllegalArgumentException("人格名称不能为空");
            }
            PersonaDO row = new PersonaDO();
            row.setName(req.name().trim());
            row.setDefinition(req.definitionJson());
            row.setIsDefault(Boolean.TRUE.equals(req.isDefault()) ? 1 : 0);
            row.setStatus("draft");
            row.setVersionNo(1);
            personaMapper.insertPersona(row);
            return new DraftResult(row.getId(), row.getStatus(), row.getVersionNo());
        }
        PersonaDO existing = requirePersona(id);
        if (req.name() != null && !req.name().isBlank()) {
            existing.setName(req.name().trim());
        }
        existing.setDefinition(req.definitionJson());
        if (req.isDefault() != null) {
            existing.setIsDefault(req.isDefault() ? 1 : 0);
        }
        existing.setStatus("draft");
        personaMapper.updatePersona(existing);
        return new DraftResult(existing.getId(), existing.getStatus(),
                existing.getVersionNo() != null ? existing.getVersionNo() : 1);
    }

    public void publish(long id, String overrideReason) {
        requirePersona(id);
        publishGateService.publish("persona:" + id, overrideReason);
    }

    public void rollback(long id, int targetVersion) {
        versioningService.rollbackPersona(id, targetVersion);
    }

    public List<VersionView> versions(long id) {
        requirePersona(id);
        return fragmentMapper.selectVersions(ContentVersionDO.KIND_PERSONA, id).stream()
                .map(v -> new VersionView(v.getId(), v.getVersionNo(), v.getSnapshot(),
                        v.getCreatedAt() != null ? v.getCreatedAt().toString() : null))
                .toList();
    }

    public BindingsView bindings(long id) {
        requirePersona(id);
        List<BoundGroupView> boundGroups = new ArrayList<>();
        List<HistoryItemView> histories = new ArrayList<>();
        for (GroupDO g : groupMapper.selectAll()) {
            GroupConfigDO cfg = groupConfigMapper.selectByGroup(g.getPlatform(), g.getBotId(), g.getGroupId());
            if (cfg != null && Objects.equals(id, cfg.getPersonaId())) {
                boundGroups.add(new BoundGroupView(
                        g.getPlatform(), g.getBotId(), g.getGroupId(), g.getGroupName(), g.getLifecycle()));
            }
            for (GroupPersonaHistoryDO h : historyMapper.selectByGroup(g.getPlatform(), g.getBotId(), g.getGroupId(), 0, 20)) {
                if (Objects.equals(id, h.getToPersonaId()) || Objects.equals(id, h.getFromPersonaId())) {
                    histories.add(new HistoryItemView(
                            h.getId(), h.getPlatform(), h.getBotId(), h.getGroupId(),
                            h.getFromPersonaId(), h.getToPersonaId(), h.getOperator(), h.getReason(),
                            h.getSwitchedAt() != null ? h.getSwitchedAt().toString() : null));
                }
            }
        }
        return new BindingsView(boundGroups, histories);
    }

    public List<ProposalView> listProposals(long personaId, String status) {
        requirePersona(personaId);
        return proposalMapper.selectByPersona(personaId).stream()
                .filter(p -> status == null || status.isBlank() || status.equals(p.getStatus()))
                .map(p -> new ProposalView(
                        p.getId(), p.getPersonaId(), p.getStatus(),
                        p.getPeriodStart() != null ? p.getPeriodStart().toString() : null,
                        p.getPeriodEnd() != null ? p.getPeriodEnd().toString() : null,
                        p.getSignalsDigest(), p.getProposalItems(),
                        p.getReviewer(), p.getRemark(),
                        null,
                        p.getCreatedAt() != null ? p.getCreatedAt().toString() : null))
                .toList();
    }

    public void triggerEvolution(long personaId) {
        requirePersona(personaId);
        evolutionPipeline.runPeriodicProposal(personaId);
    }

    @Transactional
    public void reviewProposal(long proposalId, ReviewRequest request, String operator) {
        String reviewer = operator != null ? operator : "admin";
        String decision = request.decision();
        if ("accept".equals(decision)) {
            evolutionPipeline.acceptProposal(proposalId, reviewer, request.remark());
        } else if ("edit_and_accept".equals(decision)) {
            if (request.editedItemsJson() != null && !request.editedItemsJson().isBlank()) {
                evolutionPipeline.editProposalItems(proposalId, request.editedItemsJson());
            }
            evolutionPipeline.acceptProposal(proposalId, reviewer, request.remark());
        } else if ("reject".equals(decision)) {
            evolutionPipeline.rejectProposal(proposalId, reviewer, request.remark());
        } else {
            throw new IllegalArgumentException("不支持的审阅动作：" + decision);
        }
    }

    @Transactional
    public FeedbackView addFeedback(long personaId, FeedbackRequest request, String operator) {
        requirePersona(personaId);
        PersonaAdminFeedbackDO row = new PersonaAdminFeedbackDO();
        row.setId(idGenerator.nextId());
        row.setPersonaId(personaId);
        row.setPlatform(request.platform());
        row.setBotId(request.botId());
        row.setGroupId(request.groupId());
        row.setTag(request.tag());
        row.setComment(request.comment());
        row.setTraceId(request.traceId());
        row.setOperator(operator != null ? operator : "admin");
        row.setCreatedAt(LocalDateTime.now(clock));
        feedbackMapper.insert(row);
        return new FeedbackView(row.getId(), row.getPersonaId(), row.getTag(), row.getComment(),
                row.getTraceId(), row.getOperator(), row.getCreatedAt().toString());
    }

    public List<FeedbackView> listFeedbacks(long personaId) {
        requirePersona(personaId);
        return feedbackMapper.selectByPersona(personaId).stream()
                .map(f -> new FeedbackView(f.getId(), f.getPersonaId(), f.getTag(), f.getComment(),
                        f.getTraceId(), f.getOperator(),
                        f.getCreatedAt() != null ? f.getCreatedAt().toString() : null))
                .toList();
    }

    private PersonaDO requirePersona(long id) {
        for (PersonaDO p : personaMapper.selectAllPersonas()) {
            if (Objects.equals(p.getId(), id)) {
                return p;
            }
        }
        throw new ChatBotException("mgmt.not_found", "人格不存在 id=" + id);
    }

    private static void validateDefinitionJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("人格 definition 不能为空");
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("人格 definition 必须是合法 JSON 对象");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("人格 definition JSON 解析失败：" + e.getMessage());
        }
    }

    public record PersonaSummaryView(Long id, String name, boolean isDefault, String status,
                                     int versionNo, int boundGroupCount, String updatedAt) {
    }

    public record PersonaDetailView(Long id, String name, String definitionJson, boolean isDefault,
                                    String status, int versionNo, String updatedAt) {
    }

    public record PersonaUpsertRequest(String name, String definitionJson, Boolean isDefault) {
    }

    public record DraftResult(Long id, String status, int versionNo) {
    }

    public record VersionView(long id, int versionNo, String snapshotJson, String createdAt) {
    }

    public record BoundGroupView(String platform, String botId, String groupId, String groupName, String lifecycle) {
    }

    public record HistoryItemView(long id, String platform, String botId, String groupId,
                                  Long fromPersonaId, long toPersonaId, String operator,
                                  String reason, String switchedAt) {
    }

    public record BindingsView(List<BoundGroupView> boundGroups, List<HistoryItemView> histories) {
    }

    public record ProposalView(long id, long personaId, String status, String periodStart, String periodEnd,
                               String signalsDigestJson, String proposalItemsJson, String reviewer,
                               String reviewRemark, String reviewedAt, String createdAt) {
    }

    public record ReviewRequest(String decision, String editedItemsJson, String remark) {
    }

    public record FeedbackRequest(String platform, String botId, String groupId,
                                  String tag, String comment, String traceId) {
    }

    public record FeedbackView(long id, long personaId, String tag, String comment,
                               String traceId, String operator, String createdAt) {
    }
}
