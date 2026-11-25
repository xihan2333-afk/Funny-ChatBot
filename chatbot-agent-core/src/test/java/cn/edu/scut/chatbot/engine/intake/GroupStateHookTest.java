// 守护纪律：群状态与话题挂点在群消息处理顺带执行（同群串行执行器内天然保序）（T055）
package cn.edu.scut.chatbot.engine.intake;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.in.SenderProfile;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.gate.L0Gate;
import cn.edu.scut.chatbot.engine.turn.TurnCoordinator;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import cn.edu.scut.chatbot.memory.emotion.EmotionRuleEngine;
import cn.edu.scut.chatbot.memory.social.GroupStateService;
import cn.edu.scut.chatbot.memory.social.TopicLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T055：InboundDispatch 挂点——群消息处理顺带群状态更新与话题再热判定（保序：同一处理线程内顺序调用）。
 */
class GroupStateHookTest {

    private IntakeService intakeService;
    private TriggerEventMapper triggerEventMapper;
    private EffectsQueueMapper effectsQueueMapper;
    private GroupMapper groupMapper;
    private GroupConfigMapper groupConfigMapper;
    private GroupStateHash groupStateHash;
    private L0Gate l0Gate;
    private TurnCoordinator turnCoordinator;
    private SystemParamService systemParamService;
    private EmotionRuleEngine emotionRuleEngine;
    private GroupStateService groupStateService;
    private TopicLifecycleService topicLifecycleService;
    private InboundDispatch dispatch;

    @BeforeEach
    void setUp() {
        intakeService = mock(IntakeService.class);
        triggerEventMapper = mock(TriggerEventMapper.class);
        effectsQueueMapper = mock(EffectsQueueMapper.class);
        groupMapper = mock(GroupMapper.class);
        groupConfigMapper = mock(GroupConfigMapper.class);
        groupStateHash = mock(GroupStateHash.class);
        l0Gate = mock(L0Gate.class);
        turnCoordinator = mock(TurnCoordinator.class);
        systemParamService = mock(SystemParamService.class);
        emotionRuleEngine = mock(EmotionRuleEngine.class);
        groupStateService = mock(GroupStateService.class);
        topicLifecycleService = mock(TopicLifecycleService.class);
        dispatch = new InboundDispatch(intakeService, triggerEventMapper, effectsQueueMapper,
                groupMapper, groupConfigMapper, groupStateHash, l0Gate, turnCoordinator,
                systemParamService, Clock.systemUTC(), emotionRuleEngine,
                groupStateService, topicLifecycleService);

        TriggerEventDO pending = new TriggerEventDO();
        pending.setId(1L);
        pending.setDecision(TriggerEventDO.DECISION_PENDING);
        when(triggerEventMapper.selectByDedupKey(anyString(), anyString(), anyString()))
                .thenReturn(pending);
        when(l0Gate.judge(any())).thenReturn(new L0Gate.L0Decision("ignore", "L0", "普通消息", false));
        when(systemParamService.getLong(anyString(), anyLong())).thenReturn(8000L);
        when(systemParamService.getInt(anyString(), anyInt())).thenReturn(6);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private EventEnvelope groupMessage(String sender, String text) {
        GroupMessagePayload payload = new GroupMessagePayload("m-1", null,
                new SenderProfile(sender, "张三", "", "member", "", ""),
                List.of(new TextPart("text", text)), null);
        return new EventEnvelope("evt-1", null, "dedup-e1", "qq", "bot1", "g1", sender,
                "group_message", payload, 0L, 0L, null, null, null);
    }

    @Test
    void 群消息处理顺带驱动群状态与话题挂点() {
        dispatch.handle(groupMessage("u1", "这批瓷砖预算超了"));

        GroupKey key = new GroupKey("qq", "bot1", "g1");
        verify(groupStateService).onGroupMessage(key, "u1", "这批瓷砖预算超了");
        verify(topicLifecycleService).onGroupMessage(key, "这批瓷砖预算超了");
    }

    @Test
    void 挂点在串行处理内顺序执行_情绪群状态话题同批顺带完成() {
        dispatch.handle(groupMessage("u1", "在吗"));

        InOrder order = inOrder(emotionRuleEngine, groupStateService, topicLifecycleService);
        order.verify(emotionRuleEngine).onGroupMessage(any());
        order.verify(groupStateService).onGroupMessage(any(), anyString(), anyString());
        order.verify(topicLifecycleService).onGroupMessage(any(), anyString());
    }

    @Test
    void 多段文本拼接后进话题判定() {
        EventEnvelope envelope = new EventEnvelope("evt-2", null, "dedup-e2", "qq", "bot1", "g1",
                "u1", "group_message",
                new GroupMessagePayload("m-2", null,
                        new SenderProfile("u1", "张三", "", "member", "", ""),
                        List.of(new TextPart("text", "聊装修"), new TextPart("text", "的预算")), null),
                0L, 0L, null, null, null);
        dispatch.handle(envelope);

        verify(topicLifecycleService).onGroupMessage(
                eq(new GroupKey("qq", "bot1", "g1")), eq("聊装修的预算"));
    }
}
