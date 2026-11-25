package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.memory.retrieve.CollectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * memory 域装配（T021）：worker / 兜底扫描 / 每日任务 / 生命周期服务以 @Component +
 *
 * @Scheduled 自注册（@EnableScheduling 在 BootstrapConfiguration）——本配置类承担两件事：
 * <ul>
 *   <li>CollectionGuard 启动自检挂载（R3：describeCollection 双源校验，失败告警不阻断启动
 *       ——本地无 Qdrant / 无 embedding 槽位的开发环境照样起服务）；</li>
 *   <li>领域纪律声明：沉淀 worker 不进 Effects 串行通道（几十秒级批调用塞 Effects 会阻塞
 *       该群 generation 派发——SedimentWorker 自持轮询 + 信号量并发闸）。</li>
 * </ul>
 */
@Configuration
public class MemoryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfiguration.class);

    @Bean
    public CollectionGuardStartupCheck collectionGuardStartupCheck(CollectionGuard guard) {
        return new CollectionGuardStartupCheck(guard);
    }

    /**
     * 每日认知任务编排（T057）：五任务统一入口（04:00 低峰，@Scheduled 自注册
     * ——@EnableScheduling 在 BootstrapConfiguration）
     */
    @Bean
    public cn.edu.scut.chatbot.memory.social.DailyCognitionTasks dailyCognitionTasks(
            cn.edu.scut.chatbot.memory.sediment.SedimentFallbackScanner sedimentFallbackScanner,
            cn.edu.scut.chatbot.memory.profile.AffinityService affinityService,
            cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService memoryLifecycleService,
            cn.edu.scut.chatbot.memory.emotion.EmotionLogMapper emotionLogMapper,
            cn.edu.scut.chatbot.memory.social.TopicLifecycleService topicLifecycleService,
            java.time.Clock clock) {
        return new cn.edu.scut.chatbot.memory.social.DailyCognitionTasks(
                sedimentFallbackScanner, affinityService, memoryLifecycleService,
                emotionLogMapper, topicLifecycleService, clock);
    }

    /**
     * 启动自检（ApplicationRunner——上下文就绪后执行；任何失败只告警不抛）
     */
    static class CollectionGuardStartupCheck implements ApplicationRunner {

        private final CollectionGuard guard;

        CollectionGuardStartupCheck(CollectionGuard guard) {
            this.guard = guard;
        }

        @Override
        public void run(ApplicationArguments args) {
            try {
                guard.ensureCollection();
            } catch (Exception e) {
                log.warn("Qdrant 集合启动自检失败（不阻断启动——索引层可失败、事实源在 MySQL）：{}",
                        e.getMessage());
            }
        }
    }
}
