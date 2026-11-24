package cn.edu.scut.chatbot.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 全局共享 ObjectMapper 静态持有者（research R3 决策）：JsonTypeHandler 与 Redis 序列化
 * 共用同一配置（001「显式 ObjectMapper」纪律的延续）。
 *
 * <p>静态持有者的存在理由：MyBatis 实例化 TypeHandler 时不经 Spring 容器，
 * 拿不到 bean——用静态字段在 Spring 上下文启动时注入一次，TypeHandler 侧静态读取。
 *
 * <p>配置项显式声明（不依赖默认值漂移）：
 * <ul>
 *   <li>record 构造器绑定开箱即用（Jackson 2.12+ 对 record 的原生支持）</li>
 *   <li>Instant / LocalDateTime 序列化为毫秒数值（JavaTimeModule + 时间戳模式、关纳秒），
 *       与信封线格式一致</li>
 *   <li>未知字段忽略（JSON 列前向兼容：加字段后旧代码读旧快照不炸）</li>
 *   <li>空 Bean 不报错（防御性：空 record 序列化场景）</li>
 * </ul>
 */
public final class JsonMappers {

    private static volatile ObjectMapper shared;

    static {
        // 类加载即建默认实例：上下文未启动前的兜底（正常路径由 BootstrapConfiguration 覆盖注入）
        shared = configure(new ObjectMapper());
    }

    private JsonMappers() {
    }

    /**
     * Spring 上下文装配时注入全局单例（BootstrapConfiguration 调用）
     */
    public static void install(ObjectMapper mapper) {
        shared = mapper;
    }

    /**
     * 全站取用点：JsonTypeHandler、Redis 信封序列化、测试断言
     */
    public static ObjectMapper shared() {
        return shared;
    }

    /**
     * 对象 → JSON 字符串（失败返回 null——回执等留档场景不因序列化失败中断结算）
     */
    public static String valueToStringOrNull(Object value) {
        try {
            return shared().writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 显式配置模板：install 的入参与类加载兜底共用同一份配置声明
     */
    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        // 时间统一毫秒数值（与信封 occurredAt 的 epoch 毫秒一致），关闭纳秒精度
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        mapper.configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return mapper;
    }
}
