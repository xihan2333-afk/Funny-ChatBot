package cn.edu.scut.chatbot.skill;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.management.PageResult;
import cn.edu.scut.chatbot.observability.flywheel.PublishGateService;
import cn.edu.scut.chatbot.prompt.ContentVersioningService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Skill 管理服务（A4）：引用群数统计、200 字描述校验、目录+全文渲染预览、发布回滚、启停删除。
 */
@Service
public class SkillAdminService {

    private static final int DESCRIPTION_MAX = 200;

    private final SkillMapper skillMapper;
    private final SkillService skillService;
    private final ContentVersioningService versioningService;
    private final PublishGateService publishGateService;
    private final GroupMapper groupMapper;
    private final GroupConfigMapper groupConfigMapper;

    public SkillAdminService(SkillMapper skillMapper,
                             SkillService skillService,
                             ContentVersioningService versioningService,
                             PublishGateService publishGateService,
                             GroupMapper groupMapper,
                             GroupConfigMapper groupConfigMapper) {
        this.skillMapper = skillMapper;
        this.skillService = skillService;
        this.versioningService = versioningService;
        this.publishGateService = publishGateService;
        this.groupMapper = groupMapper;
        this.groupConfigMapper = groupConfigMapper;
    }

    public PageResult<SkillSummaryView> listSkills(int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        List<SkillDO> all = skillMapper.selectAll();
        List<GroupDO> inServiceGroups = groupMapper.selectAllInService();

        List<SkillSummaryView> items = all.stream()
                .skip(PageResult.offset(p, ps))
                .limit(ps)
                .map(s -> new SkillSummaryView(
                        s.getId(), s.getName(), s.getDescription(),
                        s.getEnabled() != null && s.getEnabled() == 1,
                        s.getStatus(), s.getVersionNo() != null ? s.getVersionNo() : 1,
                        countReferencedGroups(s.getName(), inServiceGroups),
                        s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null))
                .toList();
        return PageResult.of(items, all.size(), p, ps);
    }

    public SkillDetailView getSkill(String name) {
        SkillDO s = skillMapper.selectByName(name);
        if (s == null) {
            throw new ChatBotException("mgmt.not_found", "Skill 不存在：" + name);
        }
        return new SkillDetailView(
                s.getId(), s.getName(), s.getDescription(), s.getContent(),
                s.getEnabled() != null && s.getEnabled() == 1,
                s.getStatus(), s.getVersionNo() != null ? s.getVersionNo() : 1,
                s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
    }

    public DraftResult saveDraft(String name, SkillUpsertRequest request, String operator) {
        String skillName = (name != null && !name.isBlank()) ? name : request.name();
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("Skill name 不能为空");
        }
        if (request.description() != null && request.description().length() > DESCRIPTION_MAX) {
            throw new ChatBotException("mgmt.skill_description_too_long",
                    "Skill 描述长度不能超过 " + DESCRIPTION_MAX + " 字符（当前 " + request.description().length() + "）");
        }
        SkillDO skill = new SkillDO();
        skill.setName(skillName.trim());
        skill.setDescription(request.description() != null ? request.description() : "");
        skill.setContent(request.content() != null ? request.content() : "");
        skill.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        skillService.saveSkill(operator != null ? operator : "admin", skill);
        SkillDO saved = skillMapper.selectByName(skill.getName());
        return new DraftResult(saved.getId(), saved.getStatus(), saved.getVersionNo());
    }

    public PreviewView preview(String name) {
        SkillDO s = skillMapper.selectByName(name);
        if (s == null) {
            throw new ChatBotException("mgmt.not_found", "Skill 不存在：" + name);
        }
        String directoryLine = "- 技能【" + s.getName() + "】" + s.getDescription() + "（用 readSkill 读全文）";
        return new PreviewView(directoryLine, s.getContent());
    }

    public void publish(String name, String overrideReason) {
        publishGateService.publish("skill:" + name, overrideReason);
    }

    public void rollback(String name, int targetVersion) {
        versioningService.rollbackSkill(name, targetVersion);
    }

    public List<VersionView> versions(String name) {
        return versioningService.skillVersions(name).stream()
                .map(v -> new VersionView(v.getId(), v.getVersionNo(), v.getSnapshot(),
                        v.getCreatedAt() != null ? v.getCreatedAt().toString() : null))
                .toList();
    }

    public void setEnabled(String name, boolean enabled, String operator) {
        skillService.setEnabled(operator != null ? operator : "admin", name, enabled);
    }

    public void delete(String name, String operator) {
        SkillDO existing = skillMapper.selectByName(name);
        if (existing == null) {
            throw new ChatBotException("mgmt.not_found", "Skill 不存在：" + name);
        }
        if (existing.getEnabled() != null && existing.getEnabled() == 1) {
            throw new CommandRejectedException("请先停用该 Skill 后再删除");
        }
        skillService.deleteSkill(operator != null ? operator : "admin", name);
    }

    private int countReferencedGroups(String skillName, List<GroupDO> inServiceGroups) {
        int count = 0;
        for (GroupDO g : inServiceGroups) {
            GroupConfigDO cfg = groupConfigMapper.selectByGroup(g.getPlatform(), g.getBotId(), g.getGroupId());
            if (cfg == null || cfg.getDisabledSkills() == null || cfg.getDisabledSkills().isBlank()) {
                count++;
                continue;
            }
            try {
                List<String> disabled = JsonMappers.shared().readValue(cfg.getDisabledSkills(), new TypeReference<>() {
                });
                if (!disabled.contains(skillName)) {
                    count++;
                }
            } catch (Exception e) {
                count++;
            }
        }
        return count;
    }

    public record SkillSummaryView(Long id, String name, String description, boolean enabled,
                                   String status, int versionNo, int referencedGroupCount, String updatedAt) {
    }

    public record SkillDetailView(Long id, String name, String description, String content, boolean enabled,
                                  String status, int versionNo, String updatedAt) {
    }

    public record SkillUpsertRequest(String name, String description, String content, Boolean enabled) {
    }

    public record DraftResult(Long id, String status, int versionNo) {
    }

    public record PreviewView(String directoryLine, String fullContent) {
    }

    public record VersionView(long id, int versionNo, String snapshotJson, String createdAt) {
    }
}
