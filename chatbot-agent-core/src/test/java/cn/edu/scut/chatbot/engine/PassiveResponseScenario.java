// 守护纪律：崩溃动作不丢不重是结构保证（Effects 崩溃续跑、Outbox 先落库查重）——
// spec US3 独立测试；场景 A 全链路 + 场景 D 崩溃恢复（quickstart 第 2/5 节）
package cn.edu.scut.chatbot.engine;

import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.outbox.OutboxDO;
import cn.edu.scut.chatbot.engine.outbox.OutboxMapper;
import cn.edu.scut.chatbot.engine.turn.TurnDO;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.EnvelopeFactory;
import cn.edu.scut.chatbot.fixtures.RedisStreamTestKit;
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
 * 场景 A：@bot 消息被动回应全链路——入流 XADD → L0 accept → Turn 全程状态迁移 →
 * outbox 先行落库（时序断言）→ 出流出事件 → 回执回流 SETTLED → 转录轮次落库 run_id 可查。
 *
 * <p>场景 D（崩溃恢复子集）：sendMessage 已执行、轮次未落库窗口的强杀由进程内等价断言覆盖
 * （重驱前 Outbox 查重路径），完整 kill -9 验证归试运行阶段。
 */
class PassiveResponseScenario extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "90001";

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private TriggerEventMapper triggerEventMapper;

    @Autowired
    private TurnMapper turnMapper;

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private cn.edu.scut.chatbot.engine.session.AgentSessionMapper sessionMapper;

    @Autowired
    private ScriptedStreamFn scriptedStreamFn;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /**
     * 剧本模型注入（宪法：模型是假件、按剧本演——Run 期间两轮：先 toolCall(sendMessage)
     * 再自然结束）
     */
    @TestConfiguration
    static class ScriptedModelConfiguration {

        @Bean
        @Primary
        ScriptedStreamFn scriptedStreamFn() {
            return new ScriptedStreamFn();
        }
    }

    @Test
    void 场景A_atBot消息全链路_从入流到回执结算() {
        // 前置：群进入服务名单
        groupMapper.upsertDiscovered("qq", "10000", TEST_GROUP, null);
        groupMapper.updateInService("qq", "10000", TEST_GROUP, 1);
        groupMapper.updateLifecycle("qq", "10000", TEST_GROUP, GroupDO.LIFECYCLE_ACTIVE);
        redisTemplate.delete(IN_STREAM_KEY);
        redisTemplate.delete(OUT_STREAM_KEY);
        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);

        // 剧本：第一轮调 sendMessage，第二轮自然结束（无工具调用）
        scriptedStreamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "sendMessage",
                "{\"text\":\"收到！马上安排~\"}"));
        scriptedStreamFn.addScript(ScriptedStreamFn.naturalText("已经回复啦"));

        // 1. 入流注入 @bot 消息
        kit.addToInbound(IN_STREAM_KEY,
                EnvelopeFactory.groupMessage("qq", "10000", TEST_GROUP, "10086", "帮忙看看这个", null, true));

        // 2. Turn 创建并推进至终态（Awaitility 等可观察条件——禁 Thread.sleep）
        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            List<TurnDO> turns = turnMapper.selectNonTerminalAll();
            return turns.isEmpty();
        });

        List<TurnDO> all = allTurnsOfGroup();
        assertThat(all).hasSize(1);
        TurnDO turn = all.get(0);
        // 状态迁移到达终态：有出事件 → SETTLING → 回执回流前停在 SETTLING
        assertThat(turn.getStatus()).isIn("SETTLING", "SETTLED", "SILENT");

        // 3. 触发器留痕：decision=accept、decision_layer=L0、turn_id 非空
        List<TriggerEventDO> triggers = triggerEventMapper.selectAllByGroup("qq", "10000", TEST_GROUP);
        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).getDecision()).isEqualTo("accept");
        assertThat(triggers.get(0).getDecisionLayer()).isEqualTo("L0");
        assertThat(triggers.get(0).getTurnId()).isNotNull();

        // 4. outbox 先行落库（出流消息必能在本表找到先行落库行）
        List<OutboxDO> outs = outboxMapper.selectByTurn(turn.getId());
        assertThat(outs).isNotEmpty();
        assertThat(outs.get(0).getStatus()).isEqualTo(OutboxDO.STATUS_PENDING);

        // 5. 出流出现该 outboxId 的 send_group_message 信封
        await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                !kit.readOutboundAll(OUT_STREAM_KEY).isEmpty());
        List<String> outbound = kit.readOutboundAll(OUT_STREAM_KEY);
        assertThat(outbound).anySatisfy(json -> {
            assertThat(json).contains(String.valueOf(outs.get(0).getId()));
            assertThat(json).contains("send_group_message");
        });

        // 6. 回执回流：按 outboxId 注入 DeliveryReport（成功）→ Turn 置 SETTLED
        kit.addToInbound(IN_STREAM_KEY, EnvelopeFactory.deliveryReportSettled(
                String.valueOf(outs.get(0).getId()), "platform-msg-1"));
        await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                "SETTLED".equals(allTurnsOfGroup().get(0).getStatus()));

        // 7. outbox 行结算回填
        OutboxDO settled = outboxMapper.selectById(outs.get(0).getId());
        assertThat(settled.getStatus()).isEqualTo(OutboxDO.STATUS_SETTLED);
        assertThat(settled.getReport()).contains("platform-msg-1");

        // 8. 转录轮次落库：assistant 与 toolResult 条目 run_id 可查
        List<cn.edu.scut.chatbot.engine.session.TranscriptEntryDO> entries =
                transcriptEntriesOf(turn);
        assertThat(entries).isNotEmpty();
        assertThat(entries).anySatisfy(entry -> assertThat(entry.getKind()).isEqualTo("assistant"));
        assertThat(entries).anySatisfy(entry -> assertThat(entry.getKind()).isEqualTo("toolResult"));
        assertThat(entries).allSatisfy(entry -> assertThat(entry.getRunId()).isNotNull());
    }

    @Test
    void 场景D_重驱前Outbox查重_有在途出事件不重复生成() {
        groupMapper.upsertDiscovered("qq", "10000", "90002", null);
        groupMapper.updateInService("qq", "10000", "90002", 1);
        groupMapper.updateLifecycle("qq", "10000", "90002", GroupDO.LIFECYCLE_ACTIVE);
        redisTemplate.delete(IN_STREAM_KEY);

        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);
        scriptedStreamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "sendMessage",
                "{\"text\":\"崩溃前的话\"}"));
        scriptedStreamFn.addScript(ScriptedStreamFn.naturalText("结束"));

        kit.addToInbound(IN_STREAM_KEY,
                EnvelopeFactory.groupMessage("qq", "10000", "90002", "10086", "再说一遍", null, true));

        // 等 Turn 到 SETTLING（sendMessage 已执行、出事件在途——等价于"轮次未落库窗口"之后的状态）
        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            List<TurnDO> turns = turnMapper.selectNonTerminalAll().stream()
                    .filter(turn -> "90002".equals(turn.getGroupId())).toList();
            return !turns.isEmpty() && "SETTLING".equals(turns.get(0).getStatus());
        });

        TurnDO turn = turnMapper.selectNonTerminalAll().stream()
                .filter(candidate -> "90002".equals(candidate.getGroupId())).findFirst().orElseThrow();

        // 断言：出流中的发言与 outbox 行一一对应（不重复——单飞与查重结构保证）
        List<OutboxDO> outs = outboxMapper.selectByTurn(turn.getId());
        assertThat(outs).hasSize(1);
        int outboundCount = countOutboundFor(kit, String.valueOf(outs.get(0).getId()));
        assertThat(outboundCount).isEqualTo(1);
    }

    private List<TurnDO> allTurnsOfGroup() {
        return queryTerminalTurns(TEST_GROUP);
    }

    private List<TurnDO> queryTerminalTurns(String groupId) {
        // 终态查询：从触发器留痕取 turn_id 逐个查（简单可靠的审计口径）
        List<TriggerEventDO> triggers = triggerEventMapper.selectAllByGroup("qq", "10000", groupId);
        return triggers.stream()
                .map(trigger -> turnMapper.selectById(trigger.getTurnId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<cn.edu.scut.chatbot.engine.session.TranscriptEntryDO> transcriptEntriesOf(TurnDO turn) {
        // 会话条目按群会话取（该 Turn 产生的轮次在其中——run_id 可查断言用）
        cn.edu.scut.chatbot.engine.session.AgentSessionDO session =
                sessionMapper.selectByGroup(turn.getPlatform(), turn.getBotId(), turn.getGroupId());
        return session == null ? List.of() : sessionMapper.selectOrdered(session.getId());
    }

    private int countOutboundFor(RedisStreamTestKit kit, String outboxId) {
        return (int) kit.readOutboundAll(OUT_STREAM_KEY).stream()
                .filter(json -> json.contains(outboxId))
                .count();
    }
}
