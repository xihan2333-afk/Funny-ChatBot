package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.security.credential.AdminAccountDO;
import cn.edu.scut.chatbot.security.credential.AdminAccountMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 管理平台集成测试认证辅助工具：
 * 在共享 MySQL / Redis 单例容器下幂等确保测试管理员账号存在且已解除首登改密拦截，
 * 避免依赖未配置的初始化环境变量或受前序测试改密/删表影响。
 */
final class ManagementAuthTestHelper {

    private static final String TEST_ADMIN_USER = "admin";
    private static final String TEST_ADMIN_PASS = "Admin@2026!";
    private static final String LOGIN_FAIL_KEY = "admin:login:fail:" + TEST_ADMIN_USER;
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private ManagementAuthTestHelper() {
    }

    static String obtainValidToken(AdminAccountMapper accountMapper,
                                   AdminAccountService accountService,
                                   StringRedisTemplate redisTemplate) {
        redisTemplate.delete(LOGIN_FAIL_KEY);
        AdminAccountDO existing = accountMapper.selectByUsername(TEST_ADMIN_USER);
        if (existing == null) {
            AdminAccountDO account = new AdminAccountDO();
            account.setUsername(TEST_ADMIN_USER);
            account.setPasswordHash(PASSWORD_ENCODER.encode(TEST_ADMIN_PASS));
            account.setMustChange(0);
            accountMapper.insert(account);
        } else if (!Integer.valueOf(0).equals(existing.getMustChange())
                || !PASSWORD_ENCODER.matches(TEST_ADMIN_PASS, existing.getPasswordHash())) {
            accountMapper.updatePassword(existing.getId(), PASSWORD_ENCODER.encode(TEST_ADMIN_PASS), 0);
        }
        return accountService.login(TEST_ADMIN_USER, TEST_ADMIN_PASS).token();
    }
}
