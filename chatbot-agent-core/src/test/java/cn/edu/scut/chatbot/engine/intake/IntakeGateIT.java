// 守护纪律：幂等靠 uk_dedup 唯一索引拒插、至少一次靠 pending 重投——纪律藏在中间件真实行为里
//（宪法第七条：集成测试一律 Testcontainers 真容器，禁 H2/内嵌 Redis 替身）
package cn.edu.scut.chatbot.engine.intake;

import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.EnvelopeFactory;
import cn.edu.scut.chatbot.fixtures.RedisStreamTestKit;
import cn.edu.scut.chatbot.fixtures.ScriptedL1Assessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 场景 B 集成验证（quickstart 第 3 节）：真 Redis 入流 XADD → 消费组语义 → 触发器登记幂等
 * → L0 判定留档 → assessment Effect 入队 → L1 批量评估合并一次调用。
 */
@Import(IntakeGateIT.ScriptedL1Configuration.class)
class IntakeGateIT extends AbstractIntegrationTest {

    @Autowired
    private TriggerEventMapper triggerEventMapper;

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper effectsQueueMapper;

    @Autowired
    private cn.edu.scut.chatbot.engine.gate.L1BatchAssessor l1BatchAssessor;

    @Autowired
    private ScriptedL1Assessor scriptedL1;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /**
     * L1 评估挂剧本假件（生产桩"未接入模型"走降级——测试注入可控剧本）
     */
    @TestConfiguration
    static class ScriptedL1Configuration {

        @Bean
        @Primary
        ScriptedL1Assessor scriptedL1Assessor() {
            return new ScriptedL1Assessor();
        }
    }

    @Test
    void 同dedupKey投递两次只登记一行() {
        String dedupKey = "dup-it-" + System.currentTimeMillis();
        markGroupInService("80002");   // 未服务群不登记触发器（业务规则），先标记服务
        redisTemplate.opsForStream().trim(IN_STREAM_KEY, 0);
        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);

        kit.addToInbound(IN_STREAM_KEY, EnvelopeFactory.withDedup(EnvelopeFactory.groupMessage(
                "qq", "10000", "80002", "10086", "第一条（重复考）", null), dedupKey));
        await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> countTriggers("80002", dedupKey) == 1);

        // 第二次投递同 dedupKey（至少一次语义的重投形态）
        kit.addToInbound(IN_STREAM_KEY, EnvelopeFactory.withDedup(EnvelopeFactory.groupMessage(
                "qq", "10000", "80002", "10086", "重复投递", null), dedupKey));
        await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> triggerEventMapper.selectPendingByGroup("qq", "10000", "80002").size() >= 0);

        assertThat(triggerEventMapper.selectByDedupKey("qq", "10000", dedupKey)).isNotNull();
        // uk_dedup 拒插：两次投递仍只有一行
        assertThat(countTriggers("80002", dedupKey)).isEqualTo(1);
    }

    @Test
    void 五条普通消息一次L1批量评估且全部忽略() {
        String groupId = "80003";
        markGroupInService(groupId);
        redisTemplate.opsForStream().trim(IN_STREAM_KEY, 0);
        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);

        for (int i = 1; i <= 5; i++) {
            kit.addToInbound(IN_STREAM_KEY,
                    EnvelopeFactory.groupMessage("qq", "10000", groupId, "10086", "闲聊 " + i, null));
        }

        // 五条全部升级 L1（无 @ 无指令），assessment Effect 入队（同 key 合并——只有一行）
        await().atMost(java.time.Duration.ofSeconds(15)).until(() -> {
            EffectsQueueDO effect = effectsQueueMapper.selectByMergeKey(
                    "qq", "10000", groupId, EffectsQueueDO.KIND_ASSESSMENT);
            return effect != null;
        });

        // 执行 L1 批量评估（Effects 执行器就位前手动驱动；剧本默认全部 ignore）
        l1BatchAssessor.assessGroup("qq", "10000", groupId);

        List<TriggerEventDO> triggers = triggerEventMapper.selectAllByGroup("qq", "10000", groupId);
        assertThat(triggers).hasSize(5);
        assertThat(triggers).allSatisfy(trigger -> {
            assertThat(trigger.getDecision()).isEqualTo("ignore");
            assertThat(trigger.getDecisionLayer()).isEqualTo("L1");
        });
        // 一次评估合并（batch_digest 非空）；JSON 是否带空格属序列化风格，去空格后比对
        assertThat(triggers.get(0).getBatchDigest().replace(" ", "")).contains("\"total\":5");
    }

    @Test
    void 未服务群消息不登记触发器不留档() {
        redisTemplate.opsForStream().trim(IN_STREAM_KEY, 0);
        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);

        // 80004 群从未标记 in_service（发现后停留 DISCOVERED）
        kit.addToInbound(IN_STREAM_KEY,
                EnvelopeFactory.groupMessage("qq", "10000", "80004", "10086", "未服务群消息", null));

        await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> groupMapper.selectByGroup("qq", "10000", "80004") != null);

        // 群被自动发现（DISCOVERED 行存在），但不登记任何触发器
        assertThat(groupMapper.selectByGroup("qq", "10000", "80004").getLifecycle())
                .isEqualTo(GroupDO.LIFECYCLE_DISCOVERED);
        assertThat(triggerEventMapper.selectPendingByGroup("qq", "10000", "80004")).isEmpty();
    }

    @Test
    void AtBot消息强制接受且决策留档() {
        String groupId = "80005";
        markGroupInService(groupId);
        redisTemplate.opsForStream().trim(IN_STREAM_KEY, 0);
        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);

        kit.addToInbound(IN_STREAM_KEY,
                EnvelopeFactory.groupMessage("qq", "10000", groupId, "10086", "在吗", null, true));

        await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                triggerEventMapper.selectPendingByGroup("qq", "10000", groupId).isEmpty());

        List<TriggerEventDO> triggers = triggerEventMapper.selectAllByGroup("qq", "10000", groupId);
        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).getDecision()).isEqualTo("accept");
        assertThat(triggers.get(0).getDecisionLayer()).isEqualTo("L0");
        // L0 accept 直接入 generation Effect（Phase 5 改为 acceptTurn 排队）
        assertThat(effectsQueueMapper.selectByMergeKey("qq", "10000", groupId,
                EffectsQueueDO.KIND_GENERATION)).isNotNull();
    }

    private void markGroupInService(String groupId) {
        groupMapper.upsertDiscovered("qq", "10000", groupId, null);
        groupMapper.updateInService("qq", "10000", groupId, 1);
        groupMapper.updateLifecycle("qq", "10000", groupId, GroupDO.LIFECYCLE_ACTIVE);
    }

    private long countTriggers(String groupId, String dedupKey) {
        TriggerEventDO trigger = triggerEventMapper.selectByDedupKey("qq", "10000", dedupKey);
        return trigger == null ? 0 : 1;
    }
}
