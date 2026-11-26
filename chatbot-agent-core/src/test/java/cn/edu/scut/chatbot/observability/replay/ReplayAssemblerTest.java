// 守护纪律：重放隔离与影子化（T061 单元面）——三元组派生规则（replay:: 前缀 +
// case 后缀、空三元组兜底）、影子工具替换装配（sendMessage/createTask 被替换、
// 其余工具原样）、影子 sendMessage 收集发言零 Outbox（SC-004 前置）、版本分组键
package cn.edu.scut.chatbot.observability.replay;

import cn.edu.scut.chatbot.engine.outbox.OutboxWriter;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.loop.*;
import cn.edu.scut.chatbot.observability.eval.EvalCaseDO;
import cn.edu.scut.chatbot.tool.assembler.ToolsetAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayAssemblerTest {

    private ToolsetAssembler toolsetAssembler;
    private TaskCoordinator taskCoordinator;
    private ReplayToolAssembler replayAssembler;

    @BeforeEach
    void setUp() {
        toolsetAssembler = mock(ToolsetAssembler.class);
        taskCoordinator = mock(TaskCoordinator.class);
        replayAssembler = new ReplayToolAssembler(toolsetAssembler, taskCoordinator);
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "测试工具 " + name, "{}",
                        ToolDescriptor.ToolSpeed.FAST,
                        ToolDescriptor.EffectKind.PURE_QUERY,
                        ToolDescriptor.ToolSource.BUILTIN, false);
            }

            @Override
            public AgentToolResult execute(ToolInvocation invocation) {
                return AgentToolResult.text("ok");
            }
        };
    }

    private static EvalCaseDO caze(Long id, String platform, String botId, String groupId) {
        EvalCaseDO row = new EvalCaseDO();
        row.setId(id);
        row.setPlatform(platform);
        row.setBotId(botId);
        row.setGroupId(groupId);
        return row;
    }

    @Test
    void 三元组派生_platform加前缀_groupId加case后缀() {
        ReplayGroupProvisioner.ReplayTriple triple =
                ReplayGroupProvisioner.deriveTriple(caze(7L, "qq", "bot01", "80001"));

        assertThat(triple.platform()).isEqualTo("replay::qq");
        assertThat(triple.botId()).isEqualTo("bot01");
        assertThat(triple.groupId()).isEqualTo("80001-eval-7");
    }

    @Test
    void 三元组派生_种子题空三元组兜底() {
        ReplayGroupProvisioner.ReplayTriple triple =
                ReplayGroupProvisioner.deriveTriple(caze(9L, null, null, null));

        assertThat(triple.platform()).isEqualTo("replay::default");
        assertThat(triple.botId()).isEqualTo("replay");
        assertThat(triple.groupId()).isEqualTo("case-eval-9");
    }

    @Test
    void 三元组派生_真实群与派生群互不可见() {
        ReplayGroupProvisioner.ReplayTriple triple =
                ReplayGroupProvisioner.deriveTriple(caze(1L, "qq", "bot01", "80001"));

        assertThat(triple.platform()).isNotEqualTo("qq");
        assertThat(triple.groupId()).isNotEqualTo("80001");
        // 前缀隔离：任何真实群的 platform / groupId 都不带 replay:: 前缀（群表字典面）
        assertThat(triple.platform().startsWith("replay::")).isTrue();
    }

    @Test
    void 影子装配_sendMessage与createTask被替换_其余工具原样() {
        when(toolsetAssembler.assemble(org.mockito.ArgumentMatchers.eq(RunConfig.ExecutionForm.TURN),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(tool("sendMessage"), tool("createTask"),
                        tool("fetchWeb"), tool("readSkill")));

        ReplayToolAssembler.ShadowToolset toolset = replayAssembler.assemble(
                "replay::qq", "bot01", "80001-eval-1");

        assertThat(toolset.tools()).hasSize(4);
        Map<String, AgentTool> byName = new java.util.HashMap<>();
        for (AgentTool tool : toolset.tools()) {
            byName.put(tool.descriptor().name(), tool);
        }
        assertThat(byName.get("sendMessage")).isInstanceOf(ShadowSendMessage.class);
        assertThat(byName.get("createTask")).isInstanceOf(ShadowCreateTask.class);
        assertThat(byName.get("fetchWeb")).isNotInstanceOf(ShadowSendMessage.class);
        assertThat(byName.get("readSkill")).isNotInstanceOf(ShadowCreateTask.class);
        assertThat(toolset.sendShadow()).isNotNull();
    }

    @Test
    void 影子装配_群禁用致sendMessage缺席_兜底空壳不炸() {
        when(toolsetAssembler.assemble(org.mockito.ArgumentMatchers.eq(RunConfig.ExecutionForm.TURN),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(tool("fetchWeb")));

        ReplayToolAssembler.ShadowToolset toolset = replayAssembler.assemble(
                "replay::qq", "bot01", "80001-eval-1");

        assertThat(toolset.sendShadow().speeches()).isEmpty();
    }

    @Test
    void 影子sendMessage_收集发言_零Outbox写入() {
        // 影子工具不持有 OutboxWriter——「不落 Outbox」是结构保证（无依赖即无调用）
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        cn.edu.scut.chatbot.tool.builtin.SendMessageTool real =
                new cn.edu.scut.chatbot.tool.builtin.SendMessageTool(outboxWriter);
        ShadowSendMessage shadow = new ShadowSendMessage(real);

        AgentToolResult result = shadow.execute(new ToolInvocation(
                "{\"text\":\"大家好呀\",\"replyToMessageId\":\"m9\"}",
                "replay::qq", "bot01", "g1", RunConfig.ExecutionForm.TURN, 1L, null, "sendMessage"));

        assertThat(result.isError()).isFalse();
        assertThat(shadow.speeches()).hasSize(1);
        assertThat(shadow.speeches().get(0).text()).isEqualTo("大家好呀");
        assertThat(shadow.speeches().get(0).replyToMessageId()).isEqualTo("m9");
        org.mockito.Mockito.verifyNoInteractions(outboxWriter);
    }

    @Test
    void 影子sendMessage_空文本走错误形态() {
        ShadowSendMessage shadow = new ShadowSendMessage(tool("sendMessage"));

        AgentToolResult result = shadow.execute(new ToolInvocation(
                "{\"text\":\"\"}", "p", "b", "g", RunConfig.ExecutionForm.TURN, null, null, "sendMessage"));

        assertThat(result.isError()).isTrue();
        assertThat(shadow.speeches()).isEmpty();
    }

    @Test
    void 版本分组键_三键拼接() {
        String key = ReplayService.versionGroupOf(
                new RunConfig.VersionKeys(3L, 12L, 1700000000000L));

        assertThat(key).isEqualTo("3/12/1700000000000");
        assertThat(ReplayService.versionGroupOf(null)).isEqualTo("unknown");
    }
}
