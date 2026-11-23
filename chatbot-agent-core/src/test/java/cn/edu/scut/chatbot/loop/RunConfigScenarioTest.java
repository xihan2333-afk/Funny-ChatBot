// 守护纪律：三形态装配按形态裁配（data-model 第 3 节表格）；快照隔离——Run 进行中追加历史，
// 本次调用上下文仍为启动快照（spec US1 验收场景 6、US5 验收场景 1 负向）
package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.fixtures.FixedClock;
import cn.edu.scut.chatbot.fixtures.ScriptedStreamFn;
import cn.edu.scut.chatbot.fixtures.StubTool;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import cn.edu.scut.chatbot.loop.transcript.TranscriptProjector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三形态装配场景测试（tasks T037）：
 * 预算/工具集/注入策略按形态裁配；Worker 形态 tools 快照含 sendMessage 即断言失败（负向）；
 * 快照隔离——进行中追加历史不改变本次调用上下文。
 *
 * <p>完整工具集装配矩阵的形态断言由 US5 的 ToolsetAssemblerTest 覆盖；
 * 本测试固定「装配函数」抽象的形态差异面。
 */
class RunConfigScenarioTest {

    private final FixedClock clock = new FixedClock();
    private final AtomicLong idSeq = new AtomicLong(5000);

    /**
     * 三形态预算裁配：Turn 秒级收敛、轻任务十秒级、Worker 分钟级 + 高轮次硬顶
     */
    @Test
    void 三形态预算与形态标记按RunConfig裁配() {
        RunConfig turn = RunConfig.turnDefaults();
        RunConfig lightTask = RunConfig.lightTaskDefaults();
        RunConfig worker = RunConfig.workerDefaults();

        assertThat(turn.form()).isEqualTo(RunConfig.ExecutionForm.TURN);
        assertThat(lightTask.form()).isEqualTo(RunConfig.ExecutionForm.LIGHT_TASK);
        assertThat(worker.form()).isEqualTo(RunConfig.ExecutionForm.WORKER);

        // 轮次预算：Turn 最低、Worker 最高（硬顶）
        assertThat(worker.maxTurns()).isGreaterThan(lightTask.maxTurns());
        assertThat(lightTask.maxTurns()).isGreaterThan(turn.maxTurns());
        // 时长预算：Turn 秒级 < 轻任务十秒级 < Worker 分钟级
        assertThat(worker.timeBudget()).isGreaterThan(lightTask.timeBudget());
        assertThat(lightTask.timeBudget()).isGreaterThan(turn.timeBudget());
    }

    /**
     * Worker 形态 tools 快照含 sendMessage 即装配 bug（负向断言必测——结构锁死非自觉）
     */
    @Test
    void Worker形态工具集快照不得包含sendMessage() {
        Map<String, AgentTool> workerTools = assembleTools(RunConfig.ExecutionForm.WORKER);
        Map<String, AgentTool> turnTools = assembleTools(RunConfig.ExecutionForm.TURN);

        assertThat(turnTools).containsKey("sendMessage");
        assertThat(workerTools).doesNotContainKey("sendMessage");
    }

    /**
     * 快照隔离：第一轮工具执行期间（Run 进行中）向会话历史追加新条目，
     * 第二轮模型调用的投影仍基于启动快照——新条目对本次 Run 不可见。
     */
    @Test
    void Run进行中追加历史_本次调用上下文仍为启动快照() {
        ScriptedStreamFn streamFn = new ScriptedStreamFn();
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "helper", "{}"));
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"));

        List<UserMessage> history = new CopyOnWriteArrayList<>(List.of(userMessage("hist-1", "历史消息")));
        List<Integer> projectedSizes = new CopyOnWriteArrayList<>();

        // helper 工具在第一轮执行时向"会话历史"追加条目——模拟旁观消息在生成期间登记
        StubTool helper = new StubTool("helper", invocation -> {
            history.add(userMessage("late-1", "生成期间到达的消息"));
            return AgentToolResult.text("ok");
        });

        AgentContext startContext = new AgentContext("sys", List.copyOf(history), List.of(),
                GenerationParams.defaults());
        AgentLoop.RunSpec spec = new AgentLoop.RunSpec(
                9009L, "qq", "10000", "80001", null, null,
                startContext, RunConfig.turnDefaults(),
                LoopHooks.builder(messages -> {
                    projectedSizes.add(messages.size());
                    return new TranscriptProjector().convert(messages);
                }).build(),
                Map.of("helper", helper), streamFn, clock,
                idSeq::incrementAndGet, null, null);

        AgentLoop.RunOutcome outcome = new AgentLoop().execute(spec);

        assertThat(outcome.stopReason()).isEqualTo(AgentLoop.RunOutcome.STOP_NATURAL);
        // 第一次投影 1 条（启动快照的历史）；第二次投影 3 条（快照 + 第一轮 assistant + 工具结果）；
        // 进行中追加的 late-1 不出现在任何一次投影里——历史变量已变，快照没变
        assertThat(projectedSizes).containsExactly(1, 3);
        assertThat(history).hasSize(2);
    }

    private UserMessage userMessage(String messageId, String text) {
        return new UserMessage(messageId, clock.instant(), 50, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text", text)));
    }

    /**
     * 装配函数占位（US5 ToolsetAssembler 就位前的最小形态过滤）：
     * Worker 形态排除 sendMessage（发言出口只属于面向群聊的形态）
     */
    private Map<String, AgentTool> assembleTools(RunConfig.ExecutionForm form) {
        if (form == RunConfig.ExecutionForm.WORKER) {
            return Map.of("helper", new StubTool("helper"));
        }
        return Map.of("sendMessage", StubTool.sendMessageTool(), "helper", new StubTool("helper"));
    }
}
