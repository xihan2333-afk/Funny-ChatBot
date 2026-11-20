package cn.edu.scut.chatbot.access.bootstrap;

import cn.edu.scut.chatbot.access.mediastore.SafeFetcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 接入层启动装配：全进程唯一 Clock、雪花发号器、虚拟线程执行器（宪法「时间与 ID」「并发模型」节）、
 * 定时任务装配（pending 重投扫描）。
 */
@Configuration
@EnableScheduling
public class BootstrapConfiguration {

    /**
     * 全系统时区（设计方案 3.4.5 时间规约：JVM 与 MySQL 连接参数统一 Asia/Shanghai）
     */
    private static final ZoneId SYSTEM_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 全进程唯一业务时钟：Asia/Shanghai、毫秒精度。业务代码一律注入本 bean，
     * 禁止直接调 System.currentTimeMillis() / LocalDateTime.now()——时间逻辑才可用固定时钟测试。
     */
    @Bean
    public Clock systemClock() {
        return Clock.system(SYSTEM_ZONE);
    }

    /**
     * 雪花发号器（R3 决策）：接入层机器号默认 2，可经环境变量 ACCESS_WORKER_ID 覆盖。
     * 时间源刻意用系统时钟而非上面的业务 Clock bean——发号算法依赖真实物理时刻，
     * 与业务时间无关（方案 3.4.5 的唯一例外条款）；测试注入可控时钟驱动回拨分支。
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${access.snowflake.worker-id:2}") int workerId) {
        return new SnowflakeIdGenerator(workerId, Clock.systemUTC());
    }

    /**
     * 媒体下载执行器：每任务一条新命名的虚拟线程（不池化——宪法并发纪律；
     * 并发闸在 SafeFetcher 的超时与大小限额上，执行器本身只负责起线程）。
     */
    @Bean(name = "mediaDownloadExecutor")
    public ExecutorService mediaDownloadExecutor() {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("media-download-", 0).factory();
        return Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    }

    /**
     * 出站抓取统一底座（FR-019 纪律）：HttpClient 必须手动重定向（NEVER），
     * 重定向逐跳重新校验由 SafeFetcher 自行跟随。
     */
    @Bean
    public SafeFetcher safeFetcher(
            @Value("${access.media.max-download-bytes:26214400}") long maxDownloadBytes,
            @Value("${access.media.connect-timeout-millis:3000}") long connectTimeoutMillis,
            @Value("${access.media.read-timeout-millis:3000}") long readTimeoutMillis) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new SafeFetcher(httpClient, maxDownloadBytes, Duration.ofMillis(readTimeoutMillis));
    }
}
