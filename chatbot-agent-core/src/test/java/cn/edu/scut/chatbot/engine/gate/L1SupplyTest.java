// 守护纪律：L1 五参供给（群状态摘要进评估输入）、档位标注无感不注、
// 人格名经 PersonaResolver 解析不再硬编码（T027）
package cn.edu.scut.chatbot.engine.gate;

import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.fixtures.FixedClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T027：L1 评估输入演进——五参透传与档位标注。
 */
class L1SupplyTest {

    private TriggerEventMapper mapper;
    private FixedClock clock = new FixedClock();

    /**
     * 捕获式假件：记录每次 assess 的五参入参（断言供给内容）
     */
    static class CapturingModel implements L1AssessmentModel {
        String lastPrompt;
        List<String> lastRecentMessages;
        String lastPersonaPreference;
        String lastGroupStateDigest;

        @Override
        public List<AssessedDecision> assess(String prompt, List<TriggerDigest> batchDigests,
                                             List<String> recentMessages, String personaPreference,
                                             String groupStateDigest) {
            this.lastPrompt = prompt;
            this.lastRecentMessages = recentMessages;
            this.lastPersonaPreference = personaPreference;
            this.lastGroupStateDigest = groupStateDigest;
            return List.of();
        }
    }

    private L1BatchAssessor assessorWith(CapturingModel model, String personaPreference,
                                         String groupStateDigest, String tierForA,
                                         AtomicReference<String> suppliedPersonaName) {
        return new L1BatchAssessor(
                mapper, model,
                personaName -> {
                    suppliedPersonaName.set(personaName);
                    return "提示词：" + personaName;
                },
                (p, b, g, limit) -> List.of("uA：在吗", "uB：聊装修"),
                (p, b, g) -> personaPreference,
                (p, b, g) -> groupStateDigest,
                (p, b, g, sender) -> "uA".equals(sender) ? tierForA : null,
                (p, b, g) -> "解析出的人格",
                clock, 0, 10);
    }

    private void stubPending() {
        TriggerEventDO trigger = new TriggerEventDO();
        trigger.setId(1L);
        trigger.setPlatform("qq");
        trigger.setBotId("10000");
        trigger.setGroupId("80001");
        trigger.setDedupKey("msg-1");
        trigger.setEventType("group_message");
        trigger.setDigest("消息内容");
        trigger.setDecision(TriggerEventDO.DECISION_PENDING);
        trigger.setOccurredAt(LocalDateTime.now(clock));
        when(mapper.selectPendingByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of(trigger));
    }

    @Test
    void 五参透传_群状态摘要与人格偏好都进评估输入() {
        mapper = mock(TriggerEventMapper.class);
        stubPending();
        CapturingModel model = new CapturingModel();
        AtomicReference<String> personaName = new AtomicReference<>();
        L1BatchAssessor assessor = assessorWith(
                model, "健谈度高、好奇心强", "群活跃度：活跃", "挚友", personaName);

        assessor.assessGroup("qq", "10000", "80001");

        assertEquals("健谈度高、好奇心强", model.lastPersonaPreference);
        assertEquals("群活跃度：活跃", model.lastGroupStateDigest);
        assertTrue(model.lastPrompt.contains("解析出的人格"), "提示词消费解析的人格名");
        assertEquals("提示词：解析出的人格", model.lastPrompt);
    }

    @Test
    void 态度显著者行首档位标注_无感不注() {
        mapper = mock(TriggerEventMapper.class);
        stubPending();
        CapturingModel model = new CapturingModel();
        L1BatchAssessor assessor = assessorWith(
                model, "", "", "厌恶", new AtomicReference<>());

        assessor.assessGroup("qq", "10000", "80001");

        assertEquals(2, model.lastRecentMessages.size());
        assertEquals("[厌恶]uA：在吗", model.lastRecentMessages.get(0), "显著者行首档位标注");
        assertEquals("uB：聊装修", model.lastRecentMessages.get(1), "无感者不标注");
    }

    @Test
    void 档位取数失败不拦评估_按无感处理() {
        mapper = mock(TriggerEventMapper.class);
        stubPending();
        CapturingModel model = new CapturingModel();
        L1BatchAssessor assessor = new L1BatchAssessor(
                mapper, model,
                name -> "提示词",
                (p, b, g, limit) -> List.of("uA：在吗"),
                (p, b, g) -> "",
                (p, b, g) -> "",
                (p, b, g, sender) -> {
                    throw new RuntimeException("画像不可用");
                },
                (p, b, g) -> "人格",
                clock, 0, 10);

        assessor.assessGroup("qq", "10000", "80001");
        assertEquals("uA：在吗", model.lastRecentMessages.get(0), "取数失败按无感——评估照常");
    }

    @Test
    void 人格名解析失败回退默认名是装配层语义_评估仍运行() {
        mapper = mock(TriggerEventMapper.class);
        stubPending();
        CapturingModel model = new CapturingModel();
        AtomicReference<String> used = new AtomicReference<>();
        L1BatchAssessor assessor = new L1BatchAssessor(
                mapper, model,
                name -> {
                    used.set(name);
                    return "提示词";
                },
                (p, b, g, limit) -> List.of(),
                (p, b, g) -> "",
                (p, b, g) -> "",
                (p, b, g, sender) -> null,
                (p, b, g) -> "默认人格",
                clock, 0, 10);

        assessor.assessGroup("qq", "10000", "80001");
        assertEquals("默认人格", used.get());
    }
}
