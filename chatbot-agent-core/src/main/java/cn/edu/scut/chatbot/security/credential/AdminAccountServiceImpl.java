package cn.edu.scut.chatbot.security.credential;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.management.AdminAccountService;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * {@link AdminAccountService} 实现（R16、admin-auth 契约第 1 节）：
 * 不引 Spring Security 全家，仅使用 spring-security-crypto 的 BCryptPasswordEncoder；
 * 会话存 Redis {@code admin:session:{token}}（TTL 默认 12h，续期上限默认 24h）；
 * 失败计数防爆破（15 分钟内连续失败达到上限暂时锁定）；改密清空全部会话 key 实现全端下线。
 */
@Service
public class AdminAccountServiceImpl implements AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountServiceImpl.class);

    static final String SESSION_KEY_PREFIX = "admin:session:";
    static final String SESSION_INDEX_PREFIX = "admin:sessions:";
    static final String LOGIN_FAIL_PREFIX = "admin:login:fail:";

    static final String PARAM_SESSION_TTL_HOURS = "security.admin.sessionTtlHours";
    static final String PARAM_SESSION_MAX_HOURS = "security.admin.sessionMaxHours";
    static final String PARAM_MAX_LOGIN_FAILS = "security.admin.maxLoginFails";

    private static final int DEFAULT_SESSION_TTL_HOURS = 12;
    private static final int DEFAULT_SESSION_MAX_HOURS = 24;
    private static final int DEFAULT_MAX_LOGIN_FAILS = 5;
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AdminAccountMapper accountMapper;
    private final StringRedisTemplate redis;
    private final SystemParamService systemParams;
    private final SecurityEventRecorder recorder;
    private final Clock clock;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public AdminAccountServiceImpl(AdminAccountMapper accountMapper,
                                   StringRedisTemplate redis,
                                   SystemParamService systemParams,
                                   SecurityEventRecorder recorder,
                                   Clock clock) {
        this.accountMapper = accountMapper;
        this.redis = redis;
        this.systemParams = systemParams;
        this.recorder = recorder;
        this.clock = clock;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Override
    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new CommandRejectedException("用户名与口令不能为空");
        }
        String user = username.trim();
        int maxFails = systemParams.getInt(PARAM_MAX_LOGIN_FAILS, DEFAULT_MAX_LOGIN_FAILS);
        String failKey = LOGIN_FAIL_PREFIX + user;
        String currentFails = redis.opsForValue().get(failKey);
        if (currentFails != null && parseLong(currentFails) >= maxFails) {
            recordAdminEvent("loginLocked", user, Map.of("reason", "tooManyFailures"));
            throw new CommandRejectedException("登录失败次数过多，请 15 分钟后再试");
        }

        AdminAccountDO account = accountMapper.selectByUsername(user);
        if (account == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            Long fails = redis.opsForValue().increment(failKey);
            if (fails != null && fails == 1L) {
                redis.expire(failKey, Duration.ofMinutes(15));
            }
            recordAdminEvent("loginFailed", user, Map.of("failCount", fails != null ? fails : 1));
            throw new CommandRejectedException("用户名或口令不正确");
        }

        // 登录成功：清除失败计数、签发随机 token 写 Redis
        redis.delete(failKey);
        String token = generateToken();
        long issuedAt = clock.millis();
        Duration ttl = Duration.ofHours(systemParams.getInt(PARAM_SESSION_TTL_HOURS, DEFAULT_SESSION_TTL_HOURS));
        long expiresAt = issuedAt + ttl.toMillis();

        Map<String, Object> sessionPayload = new LinkedHashMap<>();
        sessionPayload.put("username", account.getUsername());
        sessionPayload.put("issuedAt", issuedAt);
        String json = JsonMappers.valueToStringOrNull(sessionPayload);
        redis.opsForValue().set(SESSION_KEY_PREFIX + token, json != null ? json : "{}", ttl);
        redis.opsForSet().add(SESSION_INDEX_PREFIX + account.getUsername(), token);

        boolean mustChange = account.getMustChange() != null && account.getMustChange() == 1;
        recordAdminEvent("loginSuccess", account.getUsername(), Map.of("mustChange", mustChange));
        return new LoginResult(token, account.getUsername(), mustChange, expiresAt);
    }

    @Override
    public Optional<SessionPrincipal> verifySession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = SESSION_KEY_PREFIX + token.trim();
        String raw = redis.opsForValue().get(key);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(raw);
            String username = node.path("username").asText(null);
            long issuedAt = node.path("issuedAt").asLong(0L);
            if (username == null || username.isBlank()) {
                return Optional.empty();
            }
            long now = clock.millis();
            long maxLifetimeMs = Duration.ofHours(
                    systemParams.getInt(PARAM_SESSION_MAX_HOURS, DEFAULT_SESSION_MAX_HOURS)).toMillis();
            if (issuedAt > 0 && (now - issuedAt) >= maxLifetimeMs) {
                redis.delete(key);
                redis.opsForSet().remove(SESSION_INDEX_PREFIX + username, token.trim());
                return Optional.empty();
            }
            // 滑动续期（不超过最大续期上限）
            long remainingCapMs = issuedAt > 0 ? Math.max(1000L, maxLifetimeMs - (now - issuedAt))
                    : Duration.ofHours(DEFAULT_SESSION_TTL_HOURS).toMillis();
            long slideMs = Duration.ofHours(
                    systemParams.getInt(PARAM_SESSION_TTL_HOURS, DEFAULT_SESSION_TTL_HOURS)).toMillis();
            redis.expire(key, Duration.ofMillis(Math.min(slideMs, remainingCapMs)));

            AdminAccountDO account = accountMapper.selectByUsername(username);
            if (account == null) {
                return Optional.empty();
            }
            boolean mustChange = account.getMustChange() != null && account.getMustChange() == 1;
            return Optional.of(new SessionPrincipal(token.trim(), username, issuedAt, mustChange));
        } catch (Exception e) {
            log.warn("管理会话解析失败 tokenPrefix={}", token.substring(0, Math.min(6, token.length())), e);
            return Optional.empty();
        }
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String trimmed = token.trim();
        Optional<SessionPrincipal> existing = verifySession(trimmed);
        redis.delete(SESSION_KEY_PREFIX + trimmed);
        String username = existing.map(SessionPrincipal::username).orElse("unknown");
        if (existing.isPresent()) {
            redis.opsForSet().remove(SESSION_INDEX_PREFIX + username, trimmed);
        }
        recordAdminEvent("logout", username, Map.of());
    }

    @Override
    public void changePassword(String oldRaw, String newRaw) {
        if (oldRaw == null || oldRaw.isBlank() || newRaw == null || newRaw.isBlank()) {
            throw new IllegalArgumentException("原口令与新口令不能为空");
        }
        if (newRaw.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("新口令长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (oldRaw.equals(newRaw)) {
            throw new IllegalArgumentException("新口令不能与原口令相同");
        }
        AdminAccountDO account = accountMapper.selectPrimary();
        if (account == null) {
            throw new CommandRejectedException("管理账号尚未初始化");
        }
        if (!passwordEncoder.matches(oldRaw, account.getPasswordHash())) {
            recordAdminEvent("changePasswordFailed", account.getUsername(), Map.of("reason", "oldPasswordMismatch"));
            throw new CommandRejectedException("原口令不正确");
        }
        String newHash = passwordEncoder.encode(newRaw);
        accountMapper.updatePassword(account.getId(), newHash, 0);

        // 改密后全端下线：删除该账号全部活跃会话 key
        String indexKey = SESSION_INDEX_PREFIX + account.getUsername();
        Set<String> tokens = redis.opsForSet().members(indexKey);
        int revokedCount = 0;
        if (tokens != null && !tokens.isEmpty()) {
            List<String> keys = tokens.stream().map(t -> SESSION_KEY_PREFIX + t).toList();
            redis.delete(keys);
            revokedCount = keys.size();
        }
        redis.delete(indexKey);
        recordAdminEvent("changePasswordSuccess", account.getUsername(), Map.of("revokedSessions", revokedCount));
    }

    @Override
    public boolean mustChangePending() {
        AdminAccountDO account = accountMapper.selectPrimary();
        return account != null && account.getMustChange() != null && account.getMustChange() == 1;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void recordAdminEvent(String action, String username, Map<String, Object> extra) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("action", action);
            detail.put("operator", username);
            detail.putAll(extra);
            recorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(), detail, null);
        } catch (Exception e) {
            log.warn("管理认证审计写入失败 action={}", action, e);
        }
    }

    private static long parseLong(String val) {
        try {
            return Long.parseLong(val.trim());
        } catch (Exception e) {
            return 0L;
        }
    }
}
