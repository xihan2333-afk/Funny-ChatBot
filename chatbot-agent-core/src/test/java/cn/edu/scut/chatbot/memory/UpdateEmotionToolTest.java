// 守护纪律：词汇集外拒绝且不泄漏（错误结果只给模型看）、日累计封顶反馈、
// 正常自报透传内核（钳制与落账在 EmotionService——工具层薄壳）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import cn.edu.scut.chatbot.memory.emotion.EmotionService;
import cn.edu.scut.chatbot.tool.builtin.StubTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdateEmotionToolTest {

    private EmotionService emotionService;
    private StubTools.UpdateEmotionTool tool;

    @BeforeEach
    void setUp() {
        emotionService = mock(EmotionService.class);
        tool = new StubTools.UpdateEmotionTool(emotionService);
    }

    private ToolInvocation invocation(String argumentsJson) {
        return new ToolInvocation(argumentsJson, "qq", "10000", "99601",
                null, 1L, null, "updateEmotion");
    }

    @Test
    void 正常自报_透传内核_成功文本() {
        when(emotionService.applySelfReport(any(), anyString(), anyInt(), any()))
                .thenReturn(true);

        AgentToolResult result = tool.execute(invocation(
                "{\"moodLabel\":\"委屈\",\"intensityDelta\":-20,\"cause\":\"被怼了\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content().get(0).toString()).contains("委屈");
        ArgumentCaptor<GroupKey> group = ArgumentCaptor.forClass(GroupKey.class);
        verify(emotionService).applySelfReport(group.capture(),
                org.mockito.ArgumentMatchers.eq("委屈"),
                org.mockito.ArgumentMatchers.eq(-20),
                org.mockito.ArgumentMatchers.eq("被怼了"));
        assertThat(group.getValue().groupId()).isEqualTo("99601");
    }

    @Test
    void 词汇集外标签_错误结果不应用不泄漏() {
        when(emotionService.applySelfReport(any(), anyString(), anyInt(), any()))
                .thenThrow(new IllegalArgumentException("情绪标签不在词汇集内（可用：开心、平静）"));

        AgentToolResult result = tool.execute(invocation(
                "{\"moodLabel\":\"狂喜\",\"intensityDelta\":10}"));

        assertThat(result.isError()).isTrue();
        // 错误文本只含可用词汇提示——不进发言、不泄漏到群（工具结果不外发）
        assertThat(result.content().get(0).toString()).contains("词汇集");
    }

    @Test
    void 日累计封顶_明确反馈() {
        when(emotionService.applySelfReport(any(), anyString(), anyInt(), any()))
                .thenReturn(false);

        AgentToolResult result = tool.execute(invocation(
                "{\"moodLabel\":\"开心\",\"intensityDelta\":5}"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content().get(0).toString()).contains("上限");
    }
}
