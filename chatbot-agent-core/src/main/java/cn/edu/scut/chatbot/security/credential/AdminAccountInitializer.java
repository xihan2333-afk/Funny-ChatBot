package cn.edu.scut.chatbot.security.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理平台单账号首次启动初始化器（T082，admin-auth 契约第 1 节）：
 * 默认用户名与初始口令来自 .env（CHATBOT_ADMIN_INIT_USERNAME / CHATBOT_ADMIN_INIT_PASSWORD），
 * 仅在 admin_account 表为空时写入 BCrypt 哈希并置 must_change=1（强制首登改密）。
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final AdminAccountMapper mapper;
    private final BCryptPasswordEncoder encoder;
    private final String initUsername;
    private final String initPassword;

    public AdminAccountInitializer(
            AdminAccountMapper mapper,
            @Value("${chatbot.admin.init-username:${CHATBOT_ADMIN_INIT_USERNAME:admin}}") String initUsername,
            @Value("${chatbot.admin.init-password:${CHATBOT_ADMIN_INIT_PASSWORD:}}") String initPassword) {
        this.mapper = mapper;
        this.encoder = new BCryptPasswordEncoder();
        this.initUsername = initUsername;
        this.initPassword = initPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
    }

    public void seedIfEmpty() {
        try {
            if (mapper.countAll() > 0) {
                return;
            }
            if (initPassword == null || initPassword.isBlank()) {
                log.warn("admin_account 为空且未配置 CHATBOT_ADMIN_INIT_PASSWORD，跳过初始账号种入");
                return;
            }
            AdminAccountDO account = new AdminAccountDO();
            account.setUsername(initUsername == null || initUsername.isBlank() ? "admin" : initUsername.trim());
            account.setPasswordHash(encoder.encode(initPassword));
            account.setMustChange(1);
            mapper.insert(account);
            log.info("管理平台初始单账号已种入 username={} must_change=1（首次登录强制改密）", account.getUsername());
        } catch (Exception e) {
            log.warn("管理平台初始账号种入失败（请检查数据库与配置）", e);
        }
    }
}
