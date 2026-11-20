package cn.edu.scut.chatbot.access.fixtures;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;

/**
 * 测试工具：把 Mock 的 StringRedisTemplate.execute(callback) 桥接到 Mock 连接上。
 *
 * <p>为什么要一个 Java 工具类：Groovy 4 对 LambdaMetafactory 生成的隐藏类（Java lambda）
 * 做动态方法分派存在已知缺陷，直接在 Groovy 闭包里调 callback.doInConnection(...) 会抛
 * MissingMethodException；在 Java 侧按接口类型调用则完全正常。本类是这条桥的胶水。
 */
public final class RedisTemplateBridges {

    private RedisTemplateBridges() {
    }

    /**
     * 立即在给定连接上执行回调（RedisTemplate.execute 的替身行为）
     */
    public static Object executeOn(RedisCallback<?> callback, RedisConnection connection) {
        return connection == null ? null : invoke(callback, connection);
    }

    @SuppressWarnings("unchecked")
    private static Object invoke(RedisCallback callback, RedisConnection connection) {
        return callback.doInRedis(connection);
    }
}
