package cn.edu.scut.chatbot.management;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 跳过 {@link ManagementAuditInterceptor} 的自动审计。
 * 仅用于 Service 层已自行记录 {@code SecurityEventKind.ADMIN_OPERATE} 的写端点，防止重复记账。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipAutoAudit {
}
