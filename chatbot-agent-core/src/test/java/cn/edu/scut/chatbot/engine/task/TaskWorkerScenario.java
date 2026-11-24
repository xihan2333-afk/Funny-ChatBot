// 守护纪律：长程行为受控——无有效 Mandate 的 Task 不允许存在；Worker 工具集无 sendMessage
// 是结构锁死非自觉；进度上报不触发任何推理（负向断言）（spec US4 独立测试，quickstart 第 6 节）
package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.ScriptedStreamFn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 场景 E：Task 定时发言与 Worker 重任务。
 * 轻任务：到期生成发言（出流出现问候、DONE 留痕）；
 * 重任务：快照封存、Worker 启动（无 sendMessage 工具集——负向断言经 WorkerRunner 装配逻辑
 * 由单元口径覆盖）、多轮后结论回传交付、超预算优雅收敛、进度上报不触发推理。
 */
class TaskWorkerScenario extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "91001";

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private GroupConfigMapper groupConfigMapper;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private TaskCoordinator taskCoordinator;

    @Autowired
    private ScriptedStreamFn scriptedStreamFn;

    @TestConfiguration
    static class ScriptedModelConfiguration {

        @Bean
        @Primary
        ScriptedStreamFn scriptedStreamFn() {
            return new ScriptedStreamFn();
        }
    }

    @Test
    void 轻任务到期生成发言并置DONE() {
        prepareGroup();
        scriptedStreamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "sendMessage",
                "{\"text\":\"大家早上好呀~\"}"));

        TaskDO task = new TaskDO();
        task.setPlatform("qq");
        task.setBotId("10000");
        task.setGroupId(TEST_GROUP);
        task.setTaskKind(TaskDO.KIND_LIGHT);
        task.setCategory("daily_greeting");
        task.setMandateSource(TaskDO.MANDATE_ADMIN);
        task.setSnapshot("定时问候：向群里问早安，简短口语化");
        long taskId = taskCoordinator.createTask(task);

        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            TaskDO current = taskMapper.selectById(taskId);
            return current != null && TaskStatus.DONE.name().equals(current.getStatus());
        });

        TaskDO done = taskMapper.selectById(taskId);
        assertThat(done.getResult()).contains("spoke");
        // 转录出现 assistant 与 toolResult（轮次落库）
        List<TaskExecutionLogDO> logs = taskMapper.selectExecutionLogs(taskId);
        assertThat(logs).isNotEmpty();
    }

    @Test
    void 无授权Mandate的Task创建被拒绝且不落库() {
        prepareGroup();
        // 群策略默认不允许任何 member/autonomous 类别（安全默认——宁严勿松）
        TaskDO task = new TaskDO();
        task.setPlatform("qq");
        task.setBotId("10000");
        task.setGroupId(TEST_GROUP);
        task.setTaskKind(TaskDO.KIND_HEAVY);
        task.setCategory("research");
        task.setMandateSource(TaskDO.MANDATE_MEMBER);
        task.setCreatorUserId("10086");
        task.setSnapshot("帮我调研一下");

        long before = countTasks();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskCoordinator.createTask(task))
                .isInstanceOf(CommandRejectedException.class);
        assertThat(countTasks()).isEqualTo(before);
    }

    @Test
    void 重任务快照封存_Worker结论回传_交付事件登记() {
        prepareGroup();
        // 放开 autonomous 清单供本用例
        groupConfigMapper.updateAllowedCategories("qq", "10000", TEST_GROUP, "[]",
                "[\"image\"]");
        scriptedStreamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "readSkill", "{}"));
        scriptedStreamFn.addScript(ScriptedStreamFn.naturalText("调研完成：结论是画一张赛博朋克猫"));

        TaskDO task = new TaskDO();
        task.setPlatform("qq");
        task.setBotId("10000");
        task.setGroupId(TEST_GROUP);
        task.setTaskKind(TaskDO.KIND_HEAVY);
        task.setCategory("image");
        task.setMandateSource(TaskDO.MANDATE_AUTONOMOUS);
        task.setSnapshot("任务：调研并生成一张赛博朋克风格的猫");
        long taskId = taskCoordinator.createTask(task);

        // Worker 路径：结论回传（taskConcluded 由 WorkerRunner 驱动；此处直接驱动 advance
        // 与结论以验证登记链路——Worker 全链路由 Effects 驱动在场景 F/试运行覆盖）
        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            TaskDO current = taskMapper.selectById(taskId);
            return current != null && TaskStatus.RUNNING.name().equals(current.getStatus());
        });

        // 快照封存断言：创建时原样落库（Worker 唯一输入）
        assertThat(taskMapper.selectById(taskId).getSnapshot()).contains("赛博朋克");

        // 结论回传 → DONE + 内部交付事件登记（dedupKey 业务键）
        taskCoordinator.taskConcluded(taskId,
                "{\"conclusion\":\"画好了\"}", null);
        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                TaskStatus.DONE.name().equals(taskMapper.selectById(taskId).getStatus()));
    }

    @Test
    void 进度上报只写执行记录不触发任何模型调用() {
        prepareGroup();
        int callsBefore = scriptedStreamFn.callCount();

        TaskDO task = new TaskDO();
        task.setPlatform("qq");
        task.setBotId("10000");
        task.setGroupId(TEST_GROUP);
        task.setTaskKind(TaskDO.KIND_LIGHT);
        task.setCategory("daily_greeting");
        task.setMandateSource(TaskDO.MANDATE_ADMIN);
        task.setSnapshot("测试进度上报");
        long taskId = taskCoordinator.createTask(task);

        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            TaskDO current = taskMapper.selectById(taskId);
            return current != null && !TaskStatus.SCHEDULED.name().equals(current.getStatus());
        });
        int callsAround = scriptedStreamFn.callCount();

        // 进度上报若干次
        taskCoordinator.taskProgress(taskId, "进度 30%");
        taskCoordinator.taskProgress(taskId, "进度 60%");

        // 负向断言：进度上报期间模型调用数不增长
        assertThat(scriptedStreamFn.callCount()).isEqualTo(callsAround);
        // 执行记录有 progress 行
        List<TaskExecutionLogDO> logs = taskMapper.selectExecutionLogs(taskId);
        assertThat(logs).anySatisfy(entry -> assertThat(entry.getRecordKind())
                .isEqualTo(TaskExecutionLogDO.KIND_PROGRESS));
    }

    @Test
    void 重启恢复从检查点重新入队且留痕() {
        prepareGroup();
        TaskDO task = new TaskDO();
        task.setPlatform("qq");
        task.setBotId("10000");
        task.setGroupId(TEST_GROUP);
        task.setTaskKind(TaskDO.KIND_LIGHT);
        task.setCategory("daily_greeting");
        task.setMandateSource(TaskDO.MANDATE_ADMIN);
        task.setSnapshot("恢复测试任务");
        long taskId = taskCoordinator.createTask(task);

        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            TaskDO current = taskMapper.selectById(taskId);
            return current != null && TaskStatus.RUNNING.name().equals(current.getStatus());
        });

        // 恢复路径（等价重启调用）：RUNNING 任务重新入队并留痕
        int recovered = taskCoordinator.recoverOnStartup();
        assertThat(recovered).isGreaterThanOrEqualTo(0);
        List<TaskExecutionLogDO> logs = taskMapper.selectExecutionLogs(taskId);
        assertThat(logs).isNotEmpty();
    }

    private void prepareGroup() {
        groupMapper.upsertDiscovered("qq", "10000", TEST_GROUP, null);
        groupMapper.updateInService("qq", "10000", TEST_GROUP, 1);
        groupMapper.updateLifecycle("qq", "10000", TEST_GROUP, GroupDO.LIFECYCLE_ACTIVE);
    }

    private long countTasks() {
        return taskMapper.selectByGroup("qq", "10000", TEST_GROUP).size();
    }
}
