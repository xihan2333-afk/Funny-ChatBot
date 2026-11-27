package cn.edu.scut.chatbot.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 主服务启动装配：全进程唯一 Clock、全局 ObjectMapper、雪花发号器、定时任务启用
 * （宪法「时间与 ID」「并发模型」节）。
 *
 * <p>Flyway 随 Spring Boot 自动配置执行（classpath:db/migration，见 application.yml），
 * 无需专门配置类。
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
     * 全局共享 ObjectMapper（001「显式 ObjectMapper」纪律延续）：显式配置后装进
     * {@link JsonMappers} 静态持有者，供 JsonTypeHandler 与 Redis 序列化共用。
     */
    @Bean
    public ObjectMapper sharedObjectMapper() {
        ObjectMapper mapper = JsonMappers.configure(new ObjectMapper());
        JsonMappers.install(mapper);
        return mapper;
    }

    /**
     * 雪花发号器：agent-core 机器号默认 1（与接入层默认 2 区分，同机部署不撞号）。
     * 时间源刻意用系统时钟而非业务 Clock bean——发号算法依赖真实物理时刻
     * （方案 3.4.5 的唯一例外条款）；测试注入可控时钟驱动回拨分支。
     */
    @Bean
    public IdGenerator idGenerator(@Value("${agent.snowflake.worker-id:1}") int workerId) {
        return new IdGenerator(workerId, Clock.systemUTC());
    }

    /**
     * 凭证加解密组件：密钥来自部署层（.env 的 CHATBOT_CREDENTIAL_KEY，base64 的 32 字节）。
     * 未配置密钥时不建 bean——泛化工具的凭证功能按"能力未配置"降级，不伪造可用假象
     * （密钥与密文分离存放；临时生成的密钥会在重启后解不开存量密文，绝不可取）。
     */
    @Bean
    @ConditionalOnExpression("!'${chatbot.credential.key:}'.isEmpty()")
    public CredentialCodec credentialCodec(
            @Value("${chatbot.credential.key}") String base64Key,
            @Value("${chatbot.credential.previous-key:}") String previousBase64Key) {
        return CredentialCodec.fromBase64Keys(base64Key, previousBase64Key);
    }
}
