package cn.edu.scut.chatbot.security.gatepolicy;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamDO;
import cn.edu.scut.chatbot.bootstrap.SystemParamMapper;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link GatePolicyAdminService} 实现：策略表与 system_param 写入后同步刷新参数缓存、
 * 发布 ConfigRefreshEvent（新 Run 生效）并记 security_event(adminOperate)；新增黑名单
 * 时触发存量 Task 重验证（T078 策略收紧存量取消审计）。
 */
@Service
public class GatePolicyAdminServiceImpl implements GatePolicyAdminService {

    private static final Set<String> ALLOWED_THRESHOLD_KEYS = Set.of(
            GatePolicyServiceImpl.PARAM_HEAVY_PER_USER,
            GatePolicyServiceImpl.PARAM_HEAVY_PER_GROUP,
            GatePolicyServiceImpl.PARAM_RUNCODE_PER_GROUP,
            GatePolicyServiceImpl.PARAM_MANDATE_DENY_PER_HOUR,
            OutboundScreenServiceImpl.PARAM_FINGERPRINT_THRESHOLD,
            OutboundScreenServiceImpl.PARAM_FINGERPRINT_ENABLED,
            CostBreakerServiceImpl.PARAM_GLOBAL_DAILY_LIMIT,
            CostBreakerServiceImpl.PARAM_GROUP_DAILY_LIMIT
    );

    private final BlacklistMapper blacklistMapper;
    private final ContentFilterWordMapper wordMapper;
    private final SystemParamMapper paramMapper;
    private final SystemParamService systemParams;
    private final SecurityEventRecorder recorder;
    private final ApplicationEventPublisher eventPublisher;

    private TaskCoordinator taskCoordinator;
    private GroupMapper groupMapper;

    public GatePolicyAdminServiceImpl(BlacklistMapper blacklistMapper,
                                      ContentFilterWordMapper wordMapper,
                                      SystemParamMapper paramMapper,
                                      SystemParamService systemParams,
                                      SecurityEventRecorder recorder,
                                      ApplicationEventPublisher eventPublisher) {
        this.blacklistMapper = blacklistMapper;
        this.wordMapper = wordMapper;
        this.paramMapper = paramMapper;
        this.systemParams = systemParams;
        this.recorder = recorder;
        this.eventPublisher = eventPublisher;
    }

    @Autowired(required = false)
    void setTaskCoordinator(TaskCoordinator taskCoordinator) {
        this.taskCoordinator = taskCoordinator;
    }

    @Autowired(required = false)
    void setGroupMapper(GroupMapper groupMapper) {
        this.groupMapper = groupMapper;
    }

    @Override
    public List<BlacklistView> listBlacklist() {
        return blacklistMapper.selectAll().stream()
                .map(row -> new BlacklistView(row.getId(), row.getScope(), row.getPlatform(),
                        row.getBotId(), row.getGroupId(), row.getUserId(),
                        row.getReason(), row.getCreatedAt()))
                .toList();
    }

    @Override
    public long addBlacklist(BlacklistCreateRequest request, String operator) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String scope = (request.scope() == null || request.scope().isBlank())
                ? BlacklistDO.SCOPE_GROUP : request.scope();
        if (!BlacklistDO.SCOPE_GLOBAL.equals(scope) && !BlacklistDO.SCOPE_GROUP.equals(scope)) {
            throw new IllegalArgumentException("scope 仅支持 global 或 group：" + scope);
        }
        if (BlacklistDO.SCOPE_GROUP.equals(scope)
                && (request.platform() == null || request.botId() == null || request.groupId() == null)) {
            throw new IllegalArgumentException("群级黑名单必须填写 platform / botId / groupId");
        }
        BlacklistDO row = new BlacklistDO();
        row.setScope(scope);
        row.setPlatform(BlacklistDO.SCOPE_GROUP.equals(scope) ? request.platform() : null);
        row.setBotId(BlacklistDO.SCOPE_GROUP.equals(scope) ? request.botId() : null);
        row.setGroupId(BlacklistDO.SCOPE_GROUP.equals(scope) ? request.groupId() : null);
        row.setUserId(request.userId().trim());
        row.setReason(request.reason());
        blacklistMapper.insert(row);

        publishRefresh();
        SecurityDims dims = BlacklistDO.SCOPE_GROUP.equals(scope)
                ? SecurityDims.ofUser(row.getPlatform(), row.getBotId(), row.getGroupId(), row.getUserId())
                : new SecurityDims(null, null, null, row.getUserId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "blacklistAdd");
        detail.put("scope", scope);
        detail.put("userId", row.getUserId());
        detail.put("reason", row.getReason());
        detail.put("operator", operatorOrDefault(operator));
        recorder.record(SecurityEventKind.ADMIN_OPERATE, dims, detail, null);

        // 策略收紧（T078）：黑名单新增后对受影响群的存量 Task 触发重验证取消并留审计
        revalidateAfterBlacklist(row);
        return row.getId();
    }

    @Override
    public void removeBlacklist(long id, String operator) {
        blacklistMapper.deleteById(id);
        publishRefresh();
        recorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(),
                Map.of("action", "blacklistRemove", "id", id, "operator", operatorOrDefault(operator)),
                null);
    }

    @Override
    public List<ContentFilterWordView> listFilterWords() {
        return wordMapper.selectAll().stream()
                .map(row -> new ContentFilterWordView(row.getId(), row.getWord(), row.getLevel(),
                        row.getSource(), row.getEnabled() != null && row.getEnabled() == 1))
                .toList();
    }

    @Override
    public long addFilterWord(FilterWordCreateRequest request, String operator) {
        if (request == null || request.word() == null || request.word().isBlank()) {
            throw new IllegalArgumentException("过滤词不能为空");
        }
        String level = (request.level() == null || request.level().isBlank())
                ? ContentFilterWordDO.LEVEL_BLOCK : request.level();
        if (!ContentFilterWordDO.LEVEL_BLOCK.equals(level) && !ContentFilterWordDO.LEVEL_REVIEW.equals(level)) {
            throw new IllegalArgumentException("level 仅支持 block 或 review：" + level);
        }
        String source = (request.source() == null || request.source().isBlank())
                ? ContentFilterWordDO.SOURCE_MANUAL : request.source();
        if (!ContentFilterWordDO.SOURCE_MANUAL.equals(source)
                && !ContentFilterWordDO.SOURCE_CONVERTED.equals(source)
                && !ContentFilterWordDO.SOURCE_BUILTIN.equals(source)) {
            throw new IllegalArgumentException("source 仅支持 manual / converted / builtin：" + source);
        }
        ContentFilterWordDO row = new ContentFilterWordDO();
        row.setWord(request.word().trim());
        row.setLevel(level);
        row.setSource(source);
        row.setEnabled(1);
        wordMapper.insert(row);
        publishRefresh();
        recorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(),
                Map.of("action", "filterWordAdd", "word", row.getWord(),
                        "level", level, "source", source, "operator", operatorOrDefault(operator)),
                null);
        return row.getId();
    }

    @Override
    public void setFilterWordEnabled(long id, boolean enabled, String operator) {
        wordMapper.updateEnabled(id, enabled ? 1 : 0);
        publishRefresh();
        recorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(),
                Map.of("action", "filterWordToggle", "id", id,
                        "enabled", enabled, "operator", operatorOrDefault(operator)),
                null);
    }

    @Override
    public void removeFilterWord(long id, String operator) {
        wordMapper.deleteById(id);
        publishRefresh();
        recorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(),
                Map.of("action", "filterWordRemove", "id", id, "operator", operatorOrDefault(operator)),
                null);
    }

    @Override
    public List<String> getDomainBlacklist() {
        String raw = systemParams.getString(GatePolicyServiceImpl.PARAM_DOMAIN_BLACKLIST, "[]");
        try {
            List<String> parsed = JsonMappers.shared().readValue(raw, new TypeReference<List<String>>() {
            });
            return parsed != null ? parsed : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void updateDomainBlacklist(List<String> domains, String operator) {
        List<String> clean = domains == null ? List.of() : domains.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> d.trim().toLowerCase())
                .distinct()
                .toList();
        String json = JsonMappers.valueToStringOrNull(clean);
        upsertParam(GatePolicyServiceImpl.PARAM_DOMAIN_BLACKLIST, json != null ? json : "[]",
                "链接抓取域名黑名单（JSON 数组）");
        systemParams.refresh();
        publishRefresh();
        recorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(),
                Map.of("action", "domainBlacklistUpdate", "count", clean.size(),
                        "operator", operatorOrDefault(operator)),
                null);
    }

    @Override
    public Map<String, String> getThresholds() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : ALLOWED_THRESHOLD_KEYS) {
            result.put(key, systemParams.getString(key, ""));
        }
        return result;
    }

    @Override
    public void updateThresholds(Map<String, String> updates, String operator) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            if (!ALLOWED_THRESHOLD_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("不支持的策略阈值键：" + entry.getKey());
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("策略阈值不能为空：" + entry.getKey());
            }
            upsertParam(entry.getKey(), entry.getValue().trim(), "安全闸策略参数");
        }
        systemParams.refresh();
        publishRefresh();
        recorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(),
                Map.of("action", "thresholdsUpdate", "keys", updates.keySet(),
                        "operator", operatorOrDefault(operator)),
                null);
    }

    private void revalidateAfterBlacklist(BlacklistDO row) {
        if (taskCoordinator == null) {
            return;
        }
        if (BlacklistDO.SCOPE_GROUP.equals(row.getScope())) {
            taskCoordinator.revalidateMandates(row.getPlatform(), row.getBotId(), row.getGroupId());
        } else if (groupMapper != null) {
            for (GroupDO group : groupMapper.selectAllInService()) {
                taskCoordinator.revalidateMandates(group.getPlatform(), group.getBotId(), group.getGroupId());
            }
        }
    }

    private void upsertParam(String key, String value, String desc) {
        SystemParamDO param = new SystemParamDO();
        param.setParamKey(key);
        param.setParamValue(value);
        param.setDescription(desc);
        paramMapper.upsertParam(param);
    }

    private void publishRefresh() {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ToolRegistry.ConfigRefreshEvent("gatepolicy"));
        }
    }

    private static String operatorOrDefault(String operator) {
        return (operator == null || operator.isBlank()) ? "admin" : operator;
    }
}
