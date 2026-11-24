package cn.edu.scut.chatbot.skill;

import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.prompt.ContentVersioningService;
import cn.edu.scut.chatbot.tool.builtin.ReadSkillTool;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Skill 服务（T087）：创建/修改/启停/删除即时生效（下一个 Run——目录与 readSkill 缓存刷新）。
 *
 * <p>仅管理员可创建编辑：角色控制预留门面（assertOperator——2.7 管理面挂认证后传入真实
 * 角色判定；当前默认放行并留痕调用者标识）。
 */
@Service
public class SkillService {

    private final SkillMapper skillMapper;
    private final ContentVersioningService versioningService;
    private final ReadSkillTool readSkillTool;

    public SkillService(SkillMapper skillMapper,
                        ContentVersioningService versioningService,
                        ReadSkillTool readSkillTool) {
        this.skillMapper = skillMapper;
        this.versioningService = versioningService;
        this.readSkillTool = readSkillTool;
    }

    /**
     * 操作者角色断言门面（预留：2.7 挂认证后接真实角色；当前记录调用者）
     */
    public void assertOperator(String operatorId) {
        // 预留门面：管理面 Controller 挂认证时在此校验 admin 角色
    }

    /**
     * 创建或修改（草稿态，发布后新 Run 生效）
     */
    public void saveSkill(String operatorId, SkillDO skill) {
        assertOperator(operatorId);
        versioningService.saveSkill(skill);
    }

    /**
     * 发布（目录出现/readSkill 可读——下一个 Run）
     */
    public void publishSkill(String operatorId, String name) {
        assertOperator(operatorId);
        versioningService.publishSkill(name);
        refreshCaches();
    }

    /**
     * 启停（即时生效：禁用后下个 Run 目录消失）
     */
    public void setEnabled(String operatorId, String name, boolean enabled) {
        assertOperator(operatorId);
        SkillDO skill = skillMapper.selectByName(name);
        if (skill == null) {
            throw new CommandRejectedException("Skill 不存在：" + name);
        }
        skill.setEnabled(enabled ? 1 : 0);
        skillMapper.updateSkill(skill);
        refreshCaches();
    }

    /**
     * 删除（即时生效：下个 Run 目录消失）
     */
    public void deleteSkill(String operatorId, String name) {
        assertOperator(operatorId);
        skillMapper.deleteByName(name);
        refreshCaches();
    }

    public List<SkillDO> listAll() {
        return skillMapper.selectAll();
    }

    /**
     * 缓存刷新（readSkill 同 Run 幂等缓存失效——新 Run 重新读库）
     */
    private void refreshCaches() {
        readSkillTool.invalidateCache();
    }
}
