// 守护纪律：每日认知五任务统一编排、单任务失败不拖垮其余、幂等可重入（T057）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.memory.emotion.EmotionLogMapper;
import cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService;
import cn.edu.scut.chatbot.memory.profile.AffinityService;
import cn.edu.scut.chatbot.memory.sediment.SedimentFallbackScanner;
import cn.edu.scut.chatbot.memory.social.DailyCognitionTasks;
import cn.edu.scut.chatbot.memory.social.TopicLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * T057：DailyCognitionTasks——五任务调度顺序与幂等重入。
 */
class DailyCognitionTasksTest {

    private SedimentFallbackScanner sedimentFallbackScanner;
    private AffinityService affinityService;
    private MemoryLifecycleService memoryLifecycleService;
    private EmotionLogMapper emotionLogMapper;
    private TopicLifecycleService topicLifecycleService;
    private DailyCognitionTasks dailyCognitionTasks;

    @BeforeEach
    void setUp() {
        sedimentFallbackScanner = mock(SedimentFallbackScanner.class);
        affinityService = mock(AffinityService.class);
        memoryLifecycleService = mock(MemoryLifecycleService.class);
        emotionLogMapper = mock(EmotionLogMapper.class);
        topicLifecycleService = mock(TopicLifecycleService.class);
        when(emotionLogMapper.deleteBefore(any(), Mockito.anyInt())).thenReturn(0);
        dailyCognitionTasks = new DailyCognitionTasks(
                sedimentFallbackScanner, affinityService, memoryLifecycleService,
                emotionLogMapper, topicLifecycleService, Clock.systemUTC());
    }

    @Test
    void 五任务按序执行_裁剪保留九十天() {
        dailyCognitionTasks.runDailyCognition();

        InOrder order = inOrder(sedimentFallbackScanner, affinityService,
                memoryLifecycleService, emotionLogMapper, topicLifecycleService);
        order.verify(sedimentFallbackScanner).runDailyFallback();
        order.verify(affinityService).runDailySedimentStatistics();
        order.verify(memoryLifecycleService).runDailyDecayAndArchive();
        order.verify(emotionLogMapper).deleteBefore(any(LocalDateTime.class), Mockito.anyInt());
        order.verify(topicLifecycleService).runDailyCloseSweep();

        verify(emotionLogMapper).deleteBefore(any(LocalDateTime.class), Mockito.anyInt());
    }

    @Test
    void 裁剪截止时刻为九十天前() {
        org.mockito.ArgumentCaptor<LocalDateTime> cutoff =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        dailyCognitionTasks.runDailyCognition();
        verify(emotionLogMapper).deleteBefore(cutoff.capture(), Mockito.anyInt());
        long daysBetween = java.time.Duration.between(
                cutoff.getValue(), LocalDateTime.now()).toDays();
        org.junit.jupiter.api.Assertions.assertEquals(90, daysBetween,
                "保留 90 天（当日执行裁掉 90 天前之前的行）");
    }

    @Test
    void 单任务失败不拖垮其余() {
        doThrow(new RuntimeException("db busy")).when(affinityService).runDailySedimentStatistics();
        dailyCognitionTasks.runDailyCognition();

        verify(sedimentFallbackScanner).runDailyFallback();
        verify(memoryLifecycleService).runDailyDecayAndArchive();
        verify(topicLifecycleService).runDailyCloseSweep();
    }

    @Test
    void 幂等可重入_重复执行全部任务再跑() {
        dailyCognitionTasks.runDailyCognition();
        dailyCognitionTasks.runDailyCognition();

        verify(sedimentFallbackScanner, times(2)).runDailyFallback();
        verify(affinityService, times(2)).runDailySedimentStatistics();
        verify(memoryLifecycleService, times(2)).runDailyDecayAndArchive();
        verify(topicLifecycleService, times(2)).runDailyCloseSweep();
    }
}
