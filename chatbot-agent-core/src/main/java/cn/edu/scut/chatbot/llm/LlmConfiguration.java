package cn.edu.scut.chatbot.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * llm 域装配：方言注册表（@Component 收集两翻译器）、网关与转存媒体端口的 Bean 面。
 * 凭证池由 JdbcCredentialPool @Component 装配（多 key 轮转、反馈式冷却、鉴权停用）。
 *
 * <p>装配链就位后 EngineConfiguration 的 unavailableStreamFn 桩
 * （@ConditionalOnMissingBean(StreamFn)）自动退位——真 StreamFn 上线对循环内核零感知。
 */
@Configuration
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    /**
     * 转存媒体取字节端口（读方向）：接入层媒体服务是独立部署面，客户端未接入前给占位实现
     * （返回 null——翻译器跳过该图装载并告警，不阻塞调用）
     */
    @Bean
    public cn.edu.scut.chatbot.llm.gateway.StoredMediaFetcher storedMediaFetcher() {
        return storedRef -> {
            log.debug("媒体字节获取占位实现（接入层客户端未接入） storedRef={}", storedRef);
            return null;
        };
    }

    /**
     * 转存媒体写端口（写方向）：能力产物字节落存储取 storedRef——占位实现返回 null，
     * 工具按转存失败降级（不阻塞循环）；接入层客户端接入后换真实现
     */
    @Bean
    public cn.edu.scut.chatbot.llm.gateway.StoredMediaStore storedMediaStore() {
        return (data, mimeType) -> {
            log.debug("媒体转存占位实现（接入层客户端未接入） bytes={} mimeType={}",
                    data == null ? 0 : data.length, mimeType);
            return null;
        };
    }

}
