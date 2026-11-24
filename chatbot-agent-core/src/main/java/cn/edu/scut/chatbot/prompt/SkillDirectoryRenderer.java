package cn.edu.scut.chatbot.prompt;

import cn.edu.scut.chatbot.skill.SkillDO;
import cn.edu.scut.chatbot.skill.SkillMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能目录渲染器（三级渐进披露第一级：目录常驻 systemPrompt 一行一条）。
 * 目录只列「全局启用且该群未禁用」。
 */
@Component
public class SkillDirectoryRenderer {

    private final SkillMapper skillMapper;

    public SkillDirectoryRenderer(SkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    /**
     * 渲染技能目录（一行一条：名字 + 描述；无技能返回空串——层内不出现空目录头）
     *
     * @param skillContext 群级禁用过滤上下文（"skill1,skill2" 逗号分隔；null=不过滤）——
     *                     按名精确匹配（子串包含会把 readSkillPro 误杀成 readSkill）
     */
    public String renderDirectory(String skillContext) {
        List<String> disabled = parseDisabled(skillContext);
        List<SkillDO> skills = skillMapper.selectAll();
        StringBuilder directory = new StringBuilder();
        for (SkillDO skill : skills) {
            if (skill.getEnabled() == null || skill.getEnabled() != 1
                    || !"published".equals(skill.getStatus())) {
                continue;   // 只列启用且已发布
            }
            if (disabled.contains(skill.getName())) {
                continue;   // 该群禁用
            }
            directory.append("- 技能【").append(skill.getName()).append("】")
                    .append(skill.getDescription()).append("（用 readSkill 读全文）\n");
        }
        if (directory.length() == 0) {
            return "";
        }
        return "【可用技能】（要看用法就调 readSkill）\n" + directory;
    }

    private List<String> parseDisabled(String skillContext) {
        if (skillContext == null || skillContext.isBlank()) {
            return List.of();
        }
        List<String> disabled = new java.util.ArrayList<>();
        for (String name : skillContext.split(",")) {
            if (!name.isBlank()) {
                disabled.add(name.trim());
            }
        }
        return disabled;
    }
}
