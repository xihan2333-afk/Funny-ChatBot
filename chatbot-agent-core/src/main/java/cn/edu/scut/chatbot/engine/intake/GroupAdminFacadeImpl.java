package cn.edu.scut.chatbot.engine.intake;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.engine.task.TaskStatus;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.infrastructure.eventbus.ConfigRefreshedEvent;
import cn.edu.scut.chatbot.management.EngineCommandFacades;
import cn.edu.scut.chatbot.management.EngineViews;
import cn.edu.scut.chatbot.management.PageResult;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 群管理与六块群配置门面实现（位于 engine.intake，满足 ArchUnit management 收口要求）。
 */
@Service
public class GroupAdminFacadeImpl implements EngineCommandFacades.EngineGroupAdminFacade {

    private final GroupMapper groupMapper;
    private final GroupConfigMapper groupConfigMapper;
    private final GroupPersonaHistoryMapper personaHistoryMapper;
    private final TaskMapper taskMapper;
    private final TaskCoordinator taskCoordinator;
    private final AgentSessionMapper sessionMapper;
    private final PersonaMapper personaMapper;
    private final PersonaResolver personaResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public GroupAdminFacadeImpl(GroupMapper groupMapper,
                                GroupConfigMapper groupConfigMapper,
                                GroupPersonaHistoryMapper personaHistoryMapper,
                                TaskMapper taskMapper,
                                TaskCoordinator taskCoordinator,
                                AgentSessionMapper sessionMapper,
                                PersonaMapper personaMapper,
                                PersonaResolver personaResolver,
                                ApplicationEventPublisher eventPublisher,
                                Clock clock) {
        this.groupMapper = groupMapper;
        this.groupConfigMapper = groupConfigMapper;
        this.personaHistoryMapper = personaHistoryMapper;
        this.taskMapper = taskMapper;
        this.taskCoordinator = taskCoordinator;
        this.sessionMapper = sessionMapper;
        this.personaMapper = personaMapper;
        this.personaResolver = personaResolver;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    public PageResult<EngineViews.GroupListItemView> listGroups(
            String platform, String botId, String lifecycle, Boolean inService, String keyword, int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);

        Map<Long, String> personaNames = loadPersonaNames();
        List<EngineViews.GroupListItemView> filtered = new ArrayList<>();
        for (GroupDO g : groupMapper.selectAll()) {
            if (platform != null && !platform.isBlank() && !platform.equals(g.getPlatform())) continue;
            if (botId != null && !botId.isBlank() && !botId.equals(g.getBotId())) continue;
            if (lifecycle != null && !lifecycle.isBlank() && !lifecycle.equals(g.getLifecycle())) continue;
            boolean gInService = g.getInService() != null && g.getInService() == 1;
            if (inService != null && inService != gInService) continue;
            if (keyword != null && !keyword.isBlank()) {
                String kw = keyword.trim().toLowerCase(Locale.ROOT);
                String name = g.getGroupName() != null ? g.getGroupName().toLowerCase(Locale.ROOT) : "";
                String gid = g.getGroupId() != null ? g.getGroupId().toLowerCase(Locale.ROOT) : "";
                if (!name.contains(kw) && !gid.contains(kw)) continue;
            }

            long inFlight = countInFlightTasks(g.getPlatform(), g.getBotId(), g.getGroupId());
            GroupConfigDO cfg = groupConfigMapper.selectByGroup(g.getPlatform(), g.getBotId(), g.getGroupId());
            Long pid = cfg != null ? cfg.getPersonaId() : null;
            String pName = pid != null ? personaNames.getOrDefault(pid, "#" + pid) : null;

            filtered.add(new EngineViews.GroupListItemView(
                    g.getPlatform(), g.getBotId(), g.getGroupId(), g.getGroupName(),
                    g.getLifecycle(), gInService, inFlight, pid, pName,
                    g.getUpdatedAt() != null ? g.getUpdatedAt().toString() : null
            ));
        }

        int total = filtered.size();
        int fromIdx = Math.min(PageResult.offset(p, ps), total);
        int toIdx = Math.min(fromIdx + ps, total);
        return PageResult.of(filtered.subList(fromIdx, toIdx), total, p, ps);
    }

    @Override
    public void setInService(String platform, String botId, String groupId, boolean inService) {
        GroupDO existing = requireGroup(platform, botId, groupId);
        groupMapper.updateInService(existing.getPlatform(), existing.getBotId(), existing.getGroupId(), inService ? 1 : 0);
        eventPublisher.publishEvent(new ConfigRefreshedEvent("group", "切换服务名单 " + groupId + " -> " + inService));
    }

    @Override
    public EngineViews.GroupLifecyclePrecheckView precheckLifecycle(String platform, String botId, String groupId) {
        requireGroup(platform, botId, groupId);
        List<TaskDO> tasks = taskMapper.selectByGroup(platform, botId, groupId);
        long inFlight = 0;
        long activeWorkers = 0;
        for (TaskDO t : tasks) {
            TaskStatus status = TaskStatus.valueOf(t.getStatus());
            if (!status.isTerminal()) {
                inFlight++;
                if (status == TaskStatus.RUNNING) {
                    activeWorkers++;
                }
            }
        }
        AgentSessionDO session = sessionMapper.selectByGroup(platform, botId, groupId);
        GroupConfigDO cfg = groupConfigMapper.selectByGroup(platform, botId, groupId);
        String policy = cfg != null && cfg.getTranscriptRetainPolicy() != null
                ? cfg.getTranscriptRetainPolicy() : "keep";
        return new EngineViews.GroupLifecyclePrecheckView(inFlight, activeWorkers, session != null, policy);
    }

    @Override
    public void updateTranscriptRetainPolicy(String platform, String botId, String groupId, boolean retainTranscriptToMemory) {
        requireGroup(platform, botId, groupId);
        groupConfigMapper.upsertDefault(platform, botId, groupId);
        groupConfigMapper.updateTranscriptRetainPolicy(platform, botId, groupId, retainTranscriptToMemory ? "keep" : "discard");
    }

    @Override
    public EngineViews.GroupConfigView getGroupConfig(String platform, String botId, String groupId) {
        GroupDO group = requireGroup(platform, botId, groupId);
        groupConfigMapper.upsertDefault(platform, botId, groupId);
        GroupConfigDO cfg = groupConfigMapper.selectByGroup(platform, botId, groupId);

        Map<Long, String> personaNames = loadPersonaNames();
        Long pid = cfg.getPersonaId();
        String pName = pid != null ? personaNames.getOrDefault(pid, "#" + pid) : null;

        List<EngineViews.GroupPersonaHistoryView> recentHistory = personaHistoryMapper
                .selectByGroup(platform, botId, groupId, 0, 10).stream()
                .map(h -> toHistoryView(h, personaNames))
                .toList();

        return new EngineViews.GroupConfigView(
                platform, botId, groupId, group.getGroupName(),
                pid, pName,
                readStringList(cfg.getAllowedMemberTaskCategories()),
                readStringList(cfg.getAllowedAutonomousCategories()),
                readStringList(cfg.getDisabledTools()),
                readToolParamOverrides(cfg.getToolParamOverrides()),
                readStringList(cfg.getDisabledSkills()),
                readProactiveParams(cfg.getProactiveParams()),
                readStringList(cfg.getUserBlacklist()),
                cfg.getGroupRules(),
                cfg.getTranscriptRetainPolicy() != null ? cfg.getTranscriptRetainPolicy() : "keep",
                recentHistory
        );
    }

    @Override
    public EngineViews.MandatePrecheckResult precheckMandate(
            String platform, String botId, String groupId, EngineViews.GroupConfigUpdateRequest request) {
        requireGroup(platform, botId, groupId);
        Set<String> allowedMember = new HashSet<>(safeList(request.allowedMemberTaskCategories()));
        Set<String> allowedAuto = new HashSet<>(safeList(request.allowedAutonomousCategories()));
        Set<String> blacklist = new HashSet<>(safeList(request.userBlacklist()));

        List<EngineViews.AffectedTaskSummary> affected = new ArrayList<>();
        for (TaskDO task : taskMapper.selectByGroup(platform, botId, groupId)) {
            if (TaskStatus.valueOf(task.getStatus()).isTerminal()) {
                continue;
            }
            boolean disqualified = false;
            if (TaskDO.MANDATE_MEMBER.equalsIgnoreCase(task.getMandateSource())) {
                disqualified = !allowedMember.contains(task.getCategory());
            } else if (TaskDO.MANDATE_AUTONOMOUS.equalsIgnoreCase(task.getMandateSource())) {
                disqualified = !allowedAuto.contains(task.getCategory());
            }
            if (!disqualified && task.getCreatorUserId() != null && blacklist.contains(task.getCreatorUserId())) {
                disqualified = true;
            }
            if (disqualified) {
                affected.add(new EngineViews.AffectedTaskSummary(
                        task.getId(), task.getTaskKind(), task.getCategory(), task.getMandateSource(), task.getStatus()));
            }
        }
        return new EngineViews.MandatePrecheckResult(affected.size(), affected);
    }

    @Override
    @Transactional
    public EngineViews.GroupConfigSaveResult saveGroupConfig(
            String platform, String botId, String groupId, EngineViews.GroupConfigUpdateRequest request, String operator) {
        requireGroup(platform, botId, groupId);
        groupConfigMapper.upsertDefault(platform, botId, groupId);
        GroupConfigDO current = groupConfigMapper.selectByGroup(platform, botId, groupId);

        Long oldPersonaId = current.getPersonaId();
        Long newPersonaId = request.personaId();

        GroupConfigDO updated = new GroupConfigDO();
        updated.setPlatform(platform);
        updated.setBotId(botId);
        updated.setGroupId(groupId);
        updated.setPersonaId(newPersonaId);
        updated.setAllowedMemberTaskCategories(toJson(safeList(request.allowedMemberTaskCategories())));
        updated.setAllowedAutonomousCategories(toJson(safeList(request.allowedAutonomousCategories())));
        updated.setDisabledTools(toJson(safeList(request.disabledTools())));
        updated.setToolParamOverrides(request.toolParamOverrides() != null ? toJson(request.toolParamOverrides()) : "{}");
        updated.setDisabledSkills(toJson(safeList(request.disabledSkills())));
        updated.setProactiveParams(request.proactiveParams() != null ? toJson(request.proactiveParams()) : null);
        updated.setUserBlacklist(toJson(safeList(request.userBlacklist())));
        updated.setGroupRules(request.groupRules());
        updated.setTranscriptRetainPolicy(
                "discard".equalsIgnoreCase(request.transcriptRetainPolicy()) ? "discard" : "keep");
        groupConfigMapper.updateFullConfig(updated);

        // 人格绑定双源同步与切换历史
        if (newPersonaId != null && !Objects.equals(oldPersonaId, newPersonaId)) {
            GroupPersonaHistoryDO history = new GroupPersonaHistoryDO();
            history.setPlatform(platform);
            history.setBotId(botId);
            history.setGroupId(groupId);
            history.setFromPersonaId(oldPersonaId);
            history.setToPersonaId(newPersonaId);
            history.setOperator(operator != null ? operator : "admin");
            history.setReason(request.personaSwitchReason());
            history.setSwitchedAt(LocalDateTime.now(clock));
            personaHistoryMapper.insert(history);

            AgentSessionDO session = sessionMapper.selectByGroup(platform, botId, groupId);
            if (session != null) {
                personaResolver.switchPersona(new GroupKey(platform, botId, groupId), newPersonaId);
            }
        }

        int cancelledCount = taskCoordinator.revalidateMandates(platform, botId, groupId);
        eventPublisher.publishEvent(new ConfigRefreshedEvent("group_config", "更新群配置 " + groupId));
        return new EngineViews.GroupConfigSaveResult(true, cancelledCount, getGroupConfig(platform, botId, groupId));
    }

    @Override
    public PageResult<EngineViews.GroupPersonaHistoryView> listPersonaHistory(
            String platform, String botId, String groupId, int page, int pageSize) {
        requireGroup(platform, botId, groupId);
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        Map<Long, String> personaNames = loadPersonaNames();
        long total = personaHistoryMapper.countByGroup(platform, botId, groupId);
        List<EngineViews.GroupPersonaHistoryView> items = personaHistoryMapper
                .selectByGroup(platform, botId, groupId, PageResult.offset(p, ps), ps).stream()
                .map(h -> toHistoryView(h, personaNames))
                .toList();
        return PageResult.of(items, total, p, ps);
    }

    private GroupDO requireGroup(String platform, String botId, String groupId) {
        GroupDO group = groupMapper.selectByGroup(platform, botId, groupId);
        if (group == null) {
            throw new ChatBotException("mgmt.not_found",
                    "群聊不存在：" + platform + "/" + botId + "/" + groupId);
        }
        return group;
    }

    private long countInFlightTasks(String platform, String botId, String groupId) {
        long count = 0;
        for (TaskDO t : taskMapper.selectByGroup(platform, botId, groupId)) {
            if (!TaskStatus.valueOf(t.getStatus()).isTerminal()) {
                count++;
            }
        }
        return count;
    }

    private Map<Long, String> loadPersonaNames() {
        Map<Long, String> map = new HashMap<>();
        for (PersonaDO p : personaMapper.selectAllPersonas()) {
            map.put(p.getId(), p.getName());
        }
        return map;
    }

    private static EngineViews.GroupPersonaHistoryView toHistoryView(
            GroupPersonaHistoryDO h, Map<Long, String> personaNames) {
        String fromName = h.getFromPersonaId() != null
                ? personaNames.getOrDefault(h.getFromPersonaId(), "#" + h.getFromPersonaId()) : null;
        String toName = personaNames.getOrDefault(h.getToPersonaId(), "#" + h.getToPersonaId());
        return new EngineViews.GroupPersonaHistoryView(
                h.getId(), h.getFromPersonaId(), fromName,
                h.getToPersonaId(), toName,
                h.getOperator(), h.getReason(),
                h.getSwitchedAt() != null ? h.getSwitchedAt().toString() : null
        );
    }

    private static List<String> safeList(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static String toJson(Object value) {
        String s = JsonMappers.valueToStringOrNull(value);
        return s != null ? s : "[]";
    }

    private static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JsonMappers.shared().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Map<String, Object>> readToolParamOverrides(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonMappers.shared().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static EngineViews.ProactiveParamsView readProactiveParams(String json) {
        if (json == null || json.isBlank()) {
            return new EngineViews.ProactiveParamsView(null, null, null, null);
        }
        try {
            return JsonMappers.shared().readValue(json, EngineViews.ProactiveParamsView.class);
        } catch (Exception e) {
            return new EngineViews.ProactiveParamsView(null, null, null, null);
        }
    }
}
