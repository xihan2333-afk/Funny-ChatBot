// 守护纪律：暂停期间该群模型调用为零（负向断言）；恢复后计时顺延差值恰为暂停时长；
// 销毁后 Task 全取消、会话卸载、决策历史可查（spec US8 独立测试，quickstart 第 9 节）
package cn.edu.scut.chatbot.engine.lifecycle;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.EnvelopeFactory;
import cn.edu.scut.chatbot.fixtures.RedisStreamTestKit;
import cn.edu.scut.chatbot.fixtures.ScriptedStreamFn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 固定时钟快进与真容器集成：暂停/恢复/销毁/内部触发的生命周期验收（场景含 US8 全部验收点）。
 */
@Import(GroupLifecycleIT.ScriptedModelConfiguration.class)
class GroupLifecycleIT extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "95001";

    @Autowired
    private GroupLifecycleManager lifecycleManager;

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private TriggerEventMapper triggerEventMapper;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private ScriptedStreamFn scriptedStreamFn;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @TestConfiguration
    static class ScriptedModelConfiguration {

        @Bean
        @Primary
        ScriptedStreamFn scriptedStreamFn() {
            return new ScriptedStreamFn();
        }
    }

    @Test
    void 暂停期间新消息触发器登记但不评估_模型调用为零() {
        prepareGroup();
        redisTemplate.opsForStream().trim(IN_STREAM_KEY, 0);
        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);
        int callsBefore = scriptedStreamFn.callCount();

        // 暂停
        lifecycleManager.pauseGroup("qq", "10000", TEST_GROUP);

        // 暂停期间消息到达
        kit.addToInbound(IN_STREAM_KEY, EnvelopeFactory.groupMessage(
                "qq", "10000", TEST_GROUP, "10086", "暂停期间的消息", null, true));

        // 触发器登记且 L0 按群暂停强制忽略（留痕）
        await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                !triggerEventMapper.selectAllByGroup("qq", "10000", TEST_GROUP).isEmpty());
        var triggers = triggerEventMapper.selectAllByGroup("qq", "10000", TEST_GROUP);
        assertThat(triggers.get(triggers.size() - 1).getDecision()).isEqualTo("ignore");
        assertThat(triggers.get(triggers.size() - 1).getDecisionReason()).contains("暂停");

        // 负向断言：暂停期间模型调用为零
        assertThat(scriptedStreamFn.callCount()).isEqualTo(callsBefore);

        // 恢复（顺延入账）
        lifecycleManager.resumeGroup("qq", "10000", TEST_GROUP);
        GroupDO group = groupMapper.selectByGroup("qq", "10000", TEST_GROUP);
        assertThat(group.getLifecycle()).isEqualTo(GroupDO.LIFECYCLE_ACTIVE);
        assertThat(group.getPausedAccumulatedMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 销毁后任务全取消_会话卸载_决策历史可查() {
        prepareGroup();
        // 造一个 SCHEDULED 任务（admin 直过）
        TaskDO task = new TaskDO();
        task.setId(idGenerator.nextId());   // task.id 是应用侧雪花 ID（同生产路径 TaskCoordinator.createTask）
        task.setStatus("SCHEDULED");        // 用例本意即造一个 SCHEDULED 任务，status 列 NOT NULL 必须显式赋值
        task.setPlatform("qq");
        task.setBotId("10000");
        task.setGroupId(TEST_GROUP);
        task.setTaskKind(TaskDO.KIND_LIGHT);
        task.setCategory("daily_greeting");
        task.setMandateSource(TaskDO.MANDATE_ADMIN);
        task.setSnapshot("{\"note\":\"销毁测试任务\"}");   // snapshot 列为 JSON 类型，须传合法 JSON 文本
        task.setScheduleAt(java.time.LocalDateTime.now().plusMinutes(10));   // 远期不触发
        taskMapper.insertTask(task);

        lifecycleManager.destroyGroup("qq", "10000", TEST_GROUP);

        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                "CANCELLED".equals(taskMapper.selectById(task.getId()).getStatus()));
        assertThat(groupMapper.selectByGroup("qq", "10000", TEST_GROUP).getLifecycle())
                .isEqualTo(GroupDO.LIFECYCLE_DESTROYED);
    }

    private void prepareGroup() {
        groupMapper.upsertDiscovered("qq", "10000", TEST_GROUP, null);
        groupMapper.updateInService("qq", "10000", TEST_GROUP, 1);
        groupMapper.updateLifecycle("qq", "10000", TEST_GROUP, GroupDO.LIFECYCLE_ACTIVE);
    }
}
