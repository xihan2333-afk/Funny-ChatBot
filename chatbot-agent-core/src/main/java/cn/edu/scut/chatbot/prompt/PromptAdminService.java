package cn.edu.scut.chatbot.prompt;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.observability.flywheel.PublishGateService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 片段管理服务（A4）：四层 + standalone 分组、内置锁定校验、变量声明静态校验与版本化发布回滚。
 */
@Service
public class PromptAdminService {

    private final PromptFragmentMapper fragmentMapper;
    private final ContentVersioningService versioningService;
    private final PublishGateService publishGateService;

    public PromptAdminService(PromptFragmentMapper fragmentMapper,
                              ContentVersioningService versioningService,
                              PublishGateService publishGateService) {
        this.fragmentMapper = fragmentMapper;
        this.versioningService = versioningService;
        this.publishGateService = publishGateService;
    }

    public Map<String, List<FragmentView>> listGrouped() {
        Map<String, List<FragmentView>> grouped = new LinkedHashMap<>();
        grouped.put("identity", new java.util.ArrayList<>());
        grouped.put("group_context", new java.util.ArrayList<>());
        grouped.put("protocol", new java.util.ArrayList<>());
        grouped.put("task", new java.util.ArrayList<>());
        grouped.put("standalone", new java.util.ArrayList<>());

        for (PromptFragmentDO f : fragmentMapper.selectAll()) {
            String layer = f.getLayer() != null ? f.getLayer() : "standalone";
            grouped.computeIfAbsent(layer, k -> new java.util.ArrayList<>()).add(toView(f));
        }
        return grouped;
    }

    public FragmentView getByKey(String fragmentKey) {
        PromptFragmentDO f = fragmentMapper.selectByKey(fragmentKey);
        if (f == null) {
            throw new ChatBotException("mgmt.not_found", "Prompt 片段不存在：" + fragmentKey);
        }
        return toView(f);
    }

    public DraftResult saveDraft(String fragmentKey, FragmentUpsertRequest request) {
        String key = (fragmentKey != null && !fragmentKey.isBlank()) ? fragmentKey : request.fragmentKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("fragmentKey 不能为空");
        }
        PromptFragmentDO existing = fragmentMapper.selectByKey(key);
        if (existing != null && existing.getBuiltinLocked() != null && existing.getBuiltinLocked() == 1) {
            throw new ChatBotException("mgmt.fragment_locked", "内置锁定片段不可修改：" + key);
        }

        PromptFragmentDO row = new PromptFragmentDO();
        row.setFragmentKey(key.trim());
        row.setLayer(request.layer() != null ? request.layer() : "standalone");
        row.setFormFit(request.formFit() != null ? request.formFit() : "all");
        row.setTitle(request.title() != null ? request.title() : key);
        row.setContent(request.content() != null ? request.content() : "");
        row.setVariables(JsonMappers.valueToStringOrNull(
                request.variables() != null ? request.variables() : List.of()));

        try {
            versioningService.saveFragment(row);
        } catch (CommandRejectedException e) {
            String msg = e.getReason() != null ? e.getReason() : e.getMessage();
            if (msg != null && msg.contains("未声明的变量")) {
                throw new ChatBotException("mgmt.undeclared_variable", msg);
            }
            if (msg != null && msg.contains("内置锁定")) {
                throw new ChatBotException("mgmt.fragment_locked", msg);
            }
            throw e;
        }
        PromptFragmentDO saved = fragmentMapper.selectByKey(key);
        return new DraftResult(saved.getId(), saved.getStatus(), saved.getVersionNo());
    }

    public void publish(String fragmentKey, String overrideReason) {
        publishGateService.publish("fragment:" + fragmentKey, overrideReason);
    }

    public void rollback(String fragmentKey, int targetVersion) {
        versioningService.rollbackFragment(fragmentKey, targetVersion);
    }

    public List<VersionView> versions(String fragmentKey) {
        return versioningService.fragmentVersions(fragmentKey).stream()
                .map(v -> new VersionView(v.getId(), v.getVersionNo(), v.getSnapshot(),
                        v.getCreatedAt() != null ? v.getCreatedAt().toString() : null))
                .toList();
    }

    public void delete(String fragmentKey) {
        PromptFragmentDO existing = fragmentMapper.selectByKey(fragmentKey);
        if (existing == null) {
            throw new ChatBotException("mgmt.not_found", "Prompt 片段不存在：" + fragmentKey);
        }
        if (existing.getBuiltinLocked() != null && existing.getBuiltinLocked() == 1) {
            throw new ChatBotException("mgmt.fragment_locked", "内置锁定片段不可删除：" + fragmentKey);
        }
        versioningService.deleteFragment(fragmentKey);
    }

    private static FragmentView toView(PromptFragmentDO f) {
        boolean locked = f.getBuiltinLocked() != null && f.getBuiltinLocked() == 1;
        List<String> vars = List.of();
        if (f.getVariables() != null && !f.getVariables().isBlank()) {
            try {
                vars = JsonMappers.shared().readValue(f.getVariables(), new TypeReference<>() {
                });
            } catch (Exception ignored) {
            }
        }
        return new FragmentView(
                f.getId(), f.getFragmentKey(), f.getLayer(), f.getFormFit(),
                f.getTitle(), f.getContent(), vars, locked,
                locked ? "系统内置骨架或安全协议片段，禁止修改与删除" : null,
                f.getStatus(), f.getVersionNo() != null ? f.getVersionNo() : 1,
                f.getUpdatedAt() != null ? f.getUpdatedAt().toString() : null
        );
    }

    public record FragmentView(Long id, String fragmentKey, String layer, String formFit,
                               String title, String content, List<String> variables,
                               boolean builtinLocked, String lockReason, String status,
                               int versionNo, String updatedAt) {
    }

    public record FragmentUpsertRequest(String fragmentKey, String layer, String formFit,
                                        String title, String content, List<String> variables) {
    }

    public record DraftResult(Long id, String status, int versionNo) {
    }

    public record VersionView(long id, int versionNo, String snapshotJson, String createdAt) {
    }
}
