package cn.edu.scut.chatbot.memory.social;

import cn.edu.scut.chatbot.memory.emotion.EmotionLogMapper;
import cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService;
import cn.edu.scut.chatbot.memory.profile.AffinityService;
import cn.edu.scut.chatbot.memory.sediment.SedimentFallbackScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 每日认知任务编排（contracts/social-and-supply.md §6）：低峰统一入口五件事——
 * 沉淀兜底扫描、画像统计刷新、遗忘衰减与归档、情绪日志 90 天裁剪、话题翻篇兜底。
 * 全部任务幂等可重入（各任务自身保证），单个失败告警不拖垮其余；
 * 月度演进提案任务独立 cron（memory.evolution.period，US7 落地）。
 */
public class DailyCognitionTasks {

    private static final Logger log = LoggerFactory.getLogger(DailyCognitionTasks.class);

    /**
     * 情绪日志保留天数（append-only 轻维护）
     */
    static final int EMOTION_LOG_KEEP_DAYS = 90;

    private final SedimentFallbackScanner sedimentFallbackScanner;
    private final AffinityService affinityService;
    private final MemoryLifecycleService memoryLifecycleService;
    private final EmotionLogMapper emotionLogMapper;
    private final TopicLifecycleService topicLifecycleService;
    private final Clock clock;

    public DailyCognitionTasks(SedimentFallbackScanner sedimentFallbackScanner,
                               AffinityService affinityService,
                               MemoryLifecycleService memoryLifecycleService,
                               EmotionLogMapper emotionLogMapper,
                               TopicLifecycleService topicLifecycleService,
                               Clock clock) {
        this.sedimentFallbackScanner = sedimentFallbackScanner;
        this.affinityService = affinityService;
        this.memoryLifecycleService = memoryLifecycleService;
        this.emotionLogMapper = emotionLogMapper;
        this.topicLifecycleService = topicLifecycleService;
        this.clock = clock;
    }

    /**
     * 每日低峰（04:00——与 03:40 沉淀兜底错峰）：五任务顺序执行
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void runDailyCognition() {
        runOne("沉淀兜底扫描", sedimentFallbackScanner::runDailyFallback);
        runOne("画像统计刷新", affinityService::runDailySedimentStatistics);
        runOne("遗忘衰减与归档", memoryLifecycleService::runDailyDecayAndArchive);
        runOne("情绪日志裁剪", this::trimEmotionLog);
        runOne("话题翻篇兜底", topicLifecycleService::runDailyCloseSweep);
    }

    private void trimEmotionLog() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(EMOTION_LOG_KEEP_DAYS);
        int trimmed = emotionLogMapper.deleteBefore(cutoff, 500);
        if (trimmed > 0) {
            log.info("情绪日志裁剪完成 保留 {} 天 清理 {} 行", EMOTION_LOG_KEEP_DAYS, trimmed);
        }
    }

    private void runOne(String name, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("每日认知任务失败（其余任务照常、本任务幂等可重入——次日或手动重跑）{}：{}", name, e.getMessage());
        }
    }
}
