package cn.edu.scut.chatbot.management;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理平台 Web MVC 配置：注册 {@link ManagementAuditInterceptor}，覆盖全部 {@code /api/management/**} 路径。
 */
@Configuration
public class ManagementWebConfig implements WebMvcConfigurer {

    private final ManagementAuditInterceptor auditInterceptor;

    public ManagementWebConfig(ManagementAuditInterceptor auditInterceptor) {
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditInterceptor)
                .addPathPatterns("/api/management/**");
    }
}
