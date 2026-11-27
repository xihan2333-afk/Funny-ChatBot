package cn.edu.scut.chatbot.management;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明管理端写接口的审计元数据，供 {@link ManagementAuditInterceptor} 自动写入
 * {@code security_event.adminOperate}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditAction {

    /**
     * 操作目标类型（如 group_config、access_channel、persona 等）
     */
    String target();

    /**
     * 操作动作（如 create、update、delete、publish、rollback、cancel 等）
     */
    String action();

    /**
     * 人类可读的操作摘要，未填时默认使用「action target」
     */
    String summary() default "";
}
