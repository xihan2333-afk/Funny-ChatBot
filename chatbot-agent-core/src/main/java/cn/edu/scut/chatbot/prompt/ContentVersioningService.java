package cn.edu.scut.chatbot.prompt;

import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.skill.SkillDO;
import cn.edu.scut.chatbot.skill.SkillMapper;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一版本组件（T085）：五类实体共用「草稿-发布-留痕-回滚」。
 *
 * <ul>
 *   <li>草稿编辑不影响 Run（Run 只读 published）</li>
 *   <li>发布：全量快照写 content_version + 版本号递增 + 主表置 published</li>
 *   <li>回滚：旧快照写回主表再走一次发布（版本号只增不减、留痕）</li>
 *   <li>保存时静态校验模板变量声明（拼错拦在发布前）与 description 超长</li>
 *   <li>builtin_locked 片段不可删改</li>
 * </ul>
 */
@Service
public class ContentVersioningService {

    private static final Logger log = LoggerFactory.getLogger(ContentVersioningService.class);

    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)}}");

    /**
     * skill.description 长度上限（目录成本控制——DDL 列宽同值）
     */
    private static final int SKILL_DESCRIPTION_MAX = 200;

    private final PromptFragmentMapper fragmentMapper;
    private final SkillMapper skillMapper;
    private final ToolRegistry toolRegistry;

    public ContentVersioningService(PromptFragmentMapper fragmentMapper,
                                    SkillMapper skillMapper,
                                    @Lazy ToolRegistry toolRegistry) {
        this.fragmentMapper = fragmentMapper;
        this.skillMapper = skillMapper;
        this.toolRegistry = toolRegistry;
    }

    // =====================================================================
    // 片段
    // =====================================================================

    /**
     * 保存片段（草稿态）：静态校验模板变量声明——模板引用了 variables 未声明的变量即拒绝
     */
    public void saveFragment(PromptFragmentDO fragment) {
        validateTemplateVariables(fragment.getContent(), fragment.getVariables());
        PromptFragmentDO existing = fragmentMapper.selectByKey(fragment.getFragmentKey());
        if (existing != null && existing.getBuiltinLocked() != null && existing.getBuiltinLocked() == 1) {
            throw new CommandRejectedException("内置锁定片段不可删改：" + fragment.getFragmentKey());
        }
        if (existing == null) {
            fragment.setStatus("draft");
            fragment.setVersionNo(1);
            fragment.setBuiltinLocked(0);
            fragmentMapper.insertFragment(fragment);
        } else {
            // 编辑保持草稿态：不影响 Run（published 内容不变）
            fragment.setStatus("draft");
            fragment.setVersionNo(existing.getVersionNo());
            fragment.setBuiltinLocked(existing.getBuiltinLocked());
            fragmentMapper.updateFragment(fragment);
        }
    }

    /**
     * 发布片段：快照留痕 + 版本递增 + 主表置 published（新 Run 生效）
     */
    @Transactional
    public void publishFragment(String fragmentKey) {
        PromptFragmentDO fragment = fragmentMapper.selectByKey(fragmentKey);
        if (fragment == null) {
            throw new CommandRejectedException("片段不存在：" + fragmentKey);
        }
        int nextVersion = fragment.getVersionNo() + 1;
        publishSnapshot(ContentVersionDO.KIND_FRAGMENT, fragment.getId(), nextVersion, fragment);
        fragment.setVersionNo(nextVersion);
        fragment.setStatus("published");
        fragmentMapper.updateFragment(fragment);
        toolRegistry.refresh("片段发布：" + fragmentKey);   // 触发依赖缓存刷新（新 Run 生效）
    }

    /**
     * 回滚片段：旧快照写回主表再走一次发布（版本号递增留痕）
     */
    @Transactional
    public void rollbackFragment(String fragmentKey, int targetVersion) {
        PromptFragmentDO fragment = fragmentMapper.selectByKey(fragmentKey);
        if (fragment == null) {
            throw new CommandRejectedException("片段不存在：" + fragmentKey);
        }
        ContentVersionDO snapshot = fragmentMapper.selectVersion(
                ContentVersionDO.KIND_FRAGMENT, fragment.getId(), targetVersion);
        if (snapshot == null) {
            throw new CommandRejectedException("版本不存在：" + fragmentKey + " v" + targetVersion);
        }
        PromptFragmentDO old = readSnapshot(snapshot, PromptFragmentDO.class);
        // 旧内容写回主表（保留锁定标记与键名）
        old.setFragmentKey(fragmentKey);
        old.setBuiltinLocked(fragment.getBuiltinLocked());
        old.setId(fragment.getId());
        old.setStatus("draft");
        old.setVersionNo(fragment.getVersionNo());
        fragmentMapper.updateFragment(old);
        publishFragment(fragmentKey);
    }

    public void deleteFragment(String fragmentKey) {
        PromptFragmentDO existing = fragmentMapper.selectByKey(fragmentKey);
        if (existing == null) {
            return;
        }
        if (existing.getBuiltinLocked() != null && existing.getBuiltinLocked() == 1) {
            throw new CommandRejectedException("内置锁定片段不可删改：" + fragmentKey);
        }
        fragmentMapper.deleteByKey(fragmentKey);
    }

    public List<ContentVersionDO> fragmentVersions(String fragmentKey) {
        PromptFragmentDO fragment = fragmentMapper.selectByKey(fragmentKey);
        if (fragment == null) {
            return List.of();
        }
        return fragmentMapper.selectVersions(ContentVersionDO.KIND_FRAGMENT, fragment.getId());
    }

    // =====================================================================
    // Skill
    // =====================================================================

    public void saveSkill(SkillDO skill) {
        if (skill.getDescription() != null && skill.getDescription().length() > SKILL_DESCRIPTION_MAX) {
            throw new CommandRejectedException("Skill 描述超长（上限 " + SKILL_DESCRIPTION_MAX
                    + " 字符，当前 " + skill.getDescription().length() + "）——目录成本控制");
        }
        SkillDO existing = skillMapper.selectByName(skill.getName());
        if (existing == null) {
            skill.setStatus("draft");
            skill.setVersionNo(1);
            skillMapper.insertSkill(skill);
        } else {
            skill.setStatus("draft");
            skill.setVersionNo(existing.getVersionNo());
            skillMapper.updateSkill(skill);
        }
    }

    @Transactional
    public void publishSkill(String name) {
        SkillDO skill = skillMapper.selectByName(name);
        if (skill == null) {
            throw new CommandRejectedException("Skill 不存在：" + name);
        }
        int nextVersion = skill.getVersionNo() + 1;
        publishSnapshot(ContentVersionDO.KIND_SKILL, skill.getId(), nextVersion, skill);
        skill.setVersionNo(nextVersion);
        skill.setStatus("published");
        skillMapper.updateSkill(skill);
    }

    // =====================================================================
    // 校验与留痕工具
    // =====================================================================

    /**
     * 模板变量静态校验：content 中 {{var}} 引用的变量必须在 variables 声明清单内
     * （拼错拦在保存前——spec US6 验收场景 4）
     */
    void validateTemplateVariables(String content, String declaredJson) {
        if (content == null || content.isBlank()) {
            return;
        }
        List<String> declared;
        try {
            declared = JsonMappers.shared().readValue(
                    declaredJson == null ? "[]" : declaredJson,
                    JsonMappers.shared().getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new CommandRejectedException("variables 不是合法的 JSON 字符串数组");
        }
        Matcher matcher = VARIABLE_REFERENCE.matcher(content);
        while (matcher.find()) {
            String referenced = matcher.group(1);
            // 支持路径前缀：persona.name 命中声明 persona.* 或精确 persona.name
            boolean declaredOk = declared.stream().anyMatch(decl -> decl.equals(referenced)
                    || referenced.startsWith(decl + ".")
                    || (decl.endsWith(".*") && referenced.startsWith(decl.substring(0, decl.length() - 1))));
            if (!declaredOk) {
                throw new CommandRejectedException("模板引用了未声明的变量 {{" + referenced
                        + "}}（variables 清单：" + declared + "）——拼错拦在保存前");
            }
        }
    }

    private void publishSnapshot(String entityKind, long entityId, int versionNo, Object entity) {
        String snapshotJson;
        try {
            snapshotJson = JsonMappers.shared().writeValueAsString(entity);
        } catch (Exception e) {
            throw new IllegalStateException("快照序列化失败：" + e.getMessage(), e);
        }
        ContentVersionDO version = new ContentVersionDO();
        version.setEntityKind(entityKind);
        version.setEntityId(entityId);
        version.setVersionNo(versionNo);
        version.setSnapshot(snapshotJson);
        fragmentMapper.insertVersion(version);
        log.info("版本留痕 {} #{} v{}", entityKind, entityId, versionNo);
    }

    private <T> T readSnapshot(ContentVersionDO version, Class<T> type) {
        try {
            return JsonMappers.shared().readValue(version.getSnapshot(), type);
        } catch (Exception e) {
            throw new IllegalStateException("快照反序列化失败：" + e.getMessage(), e);
        }
    }
}
