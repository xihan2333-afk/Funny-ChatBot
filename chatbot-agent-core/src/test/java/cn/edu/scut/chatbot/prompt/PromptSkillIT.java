// 守护纪律：运营数据驱动行为——种子片段组装出合法 systemPrompt；Skill 目录过滤与幂等（spec US6）
package cn.edu.scut.chatbot.prompt;

import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import cn.edu.scut.chatbot.skill.SkillDO;
import cn.edu.scut.chatbot.skill.SkillMapper;
import cn.edu.scut.chatbot.skill.SkillService;
import cn.edu.scut.chatbot.tool.builtin.ReadSkillTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库种子集成：V4 种子组装快照断言、Skill 目录过滤、readSkill 幂等、禁用后目录消失、
 * 版本回滚链路（quickstart US6 独立测试）。
 */
class PromptSkillIT extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "93001";

    @Autowired
    private PromptAssembler promptAssembler;

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private SkillService skillService;

    @Autowired
    private SkillMapper skillMapper;

    @Autowired
    private ReadSkillTool readSkillTool;

    @Autowired
    private PromptFragmentMapper fragmentMapper;

    @Autowired
    private ContentVersioningService versioningService;

    @Test
    void 真库种子组装出合法systemPrompt_含骨架总则与发言协议() {
        groupMapper.upsertDiscovered("qq", "10000", TEST_GROUP, null);
        groupMapper.updateInService("qq", "10000", TEST_GROUP, 1);
        groupMapper.updateLifecycle("qq", "10000", TEST_GROUP, GroupDO.LIFECYCLE_ACTIVE);

        String prompt = promptAssembler.assemble(RunConfig.ExecutionForm.TURN,
                "qq", "10000", TEST_GROUP, null);

        // V4 种子的内置锁定片段进入组装结果（骨架总则、发言协议、工具使用总则）
        assertThat(prompt).contains("群聊");
        assertThat(prompt).contains("sendMessage");
        // 人格名渲染（默认人格小趣）
        assertThat(prompt).contains("小趣");
    }

    @Test
    void Skill目录渲染与禁用后消失_readSkill幂等() {
        savePublishedSkill("weather-talk", "天气话术指南", true);
        savePublishedSkill("meme-guide", "玩梗指南", true);
        savePublishedSkill("secret", "内部测试技能", false);

        SkillDirectoryRenderer renderer = new SkillDirectoryRenderer(skillMapper);
        String directory = renderer.renderDirectory(null);

        assertThat(directory).contains("weather-talk");
        assertThat(directory).contains("meme-guide");
        assertThat(directory).doesNotContain("secret");   // 禁用不列

        // readSkill 幂等：同 Run 重复调用返回同一正文
        ToolInvocation invocation = new ToolInvocation("{\"name\":\"meme-guide\"}",
                "qq", "10000", TEST_GROUP, RunConfig.ExecutionForm.TURN, null, null);
        AgentToolResult first = readSkillTool.execute(invocation);
        AgentToolResult second = readSkillTool.execute(invocation);
        assertThat(first.content()).isEqualTo(second.content());

        // 禁用后下个 Run 目录消失
        skillService.setEnabled("operator", "meme-guide", false);
        assertThat(renderer.renderDirectory(null)).doesNotContain("meme-guide");
    }

    @Test
    void 版本回滚链路_旧快照写回再发布版本递增() {
        PromptFragmentDO fragment = new PromptFragmentDO();
        fragment.setFragmentKey("it.rollback.target");
        fragment.setLayer("identity");
        fragment.setFormFit("all");
        fragment.setTitle("回滚测试");
        fragment.setContent("第一版内容");
        fragment.setVariables("[]");
        versioningService.saveFragment(fragment);
        versioningService.publishFragment("it.rollback.target");

        int publishedVersion = fragmentMapper.selectByKey("it.rollback.target").getVersionNo();
        assertThat(publishedVersion).isGreaterThanOrEqualTo(1);

        // 编辑再发布（版本 +1）
        PromptFragmentDO edited = new PromptFragmentDO();
        edited.setFragmentKey("it.rollback.target");
        edited.setLayer("identity");
        edited.setFormFit("all");
        edited.setTitle("回滚测试");
        edited.setContent("第二版内容");
        edited.setVariables("[]");
        versioningService.saveFragment(edited);
        versioningService.publishFragment("it.rollback.target");
        assertThat(fragmentMapper.selectByKey("it.rollback.target").getVersionNo())
                .isEqualTo(publishedVersion + 1);

        // 回滚到第一版：内容写回且版本再 +1（只增不减）
        versioningService.rollbackFragment("it.rollback.target", publishedVersion);
        PromptFragmentDO rolled = fragmentMapper.selectByKey("it.rollback.target");
        assertThat(rolled.getContent()).contains("第一版内容");
        assertThat(rolled.getVersionNo()).isEqualTo(publishedVersion + 2);
    }

    private void savePublishedSkill(String name, String description, boolean enabled) {
        SkillDO skill = new SkillDO();
        skill.setName(name);
        skill.setDescription(description);
        skill.setContent("## " + name + "\n使用要点……");
        skill.setEnabled(1);
        skillService.saveSkill("operator", skill);
        skillService.publishSkill("operator", name);
        if (!enabled) {
            skillService.setEnabled("operator", name, false);
        }
    }
}
