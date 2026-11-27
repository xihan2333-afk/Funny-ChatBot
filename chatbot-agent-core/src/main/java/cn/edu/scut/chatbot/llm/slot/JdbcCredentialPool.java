package cn.edu.scut.chatbot.llm.slot;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 凭证池 JDBC 实现（contracts/slot-and-credentials.md 第 3 节 + data-model 第 9 节）：
 * 多 key 轮转分摊配额、429 反馈式冷却（到期解析时按时间判定恢复，不依赖后台任务）、
 * 401 停用告警（终态）、全部不可用返回 null 走既有错误路径。
 *
 * <p>脱敏纪律：明文只在拼装请求头瞬间存在、不落日志——日志与定位一律用 key_mask；
 * 解密失败按能力不可用降级告警，不抛明文不抛异常。
 */
@Component
public class JdbcCredentialPool implements CredentialPool {

    private static final Logger log = LoggerFactory.getLogger(JdbcCredentialPool.class);

    /**
     * 冷却时长 system_param 键（缺省 60 秒）
     */
    public static final String COOLDOWN_SECONDS_PARAM = "llm.credential.cooldown_seconds";

    private static final String OWNER_KIND_PROVIDER = "provider";

    private static final String STATUS_DISABLED = "disabled";

    private final CredentialMapper credentialMapper;

    private final CredentialCodec codec;

    private final SystemParamService systemParams;

    private final Clock clock;

    /**
     * 安全事件统一入口（US10 T092：凭证 401 鉴权失败同步记 credentialInvalid 事件供告警探针消费）
     */
    private cn.edu.scut.chatbot.security.audit.SecurityEventRecorder securityEventRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSecurityEventRecorder(cn.edu.scut.chatbot.security.audit.SecurityEventRecorder recorder) {
        this.securityEventRecorder = recorder;
    }

    /**
     * 轮转指针（providerCode → 计数；过滤后序列上取模）
     */
    private final ConcurrentHashMap<String, AtomicInteger> rotation = new ConcurrentHashMap<>();

    public JdbcCredentialPool(CredentialMapper credentialMapper,
                              java.util.Optional<CredentialCodec> codec,
                              SystemParamService systemParams,
                              Clock clock) {
        this.credentialMapper = credentialMapper;
        this.codec = codec.orElse(null);
        this.systemParams = systemParams;
        this.clock = clock;
    }

    /**
     * 取下一把可用 key：过滤停用与冷却未到期（到期即视作恢复），按 id 轮转；
     * 全部不可用返回 null（钩子既定契约：按无凭证转错误结果，Run 失败进 Effects 重试）。
     */
    @Override
    public String nextAvailable(String providerCode) {
        if (codec == null) {
            return null;   // 加密密钥未配置：能力不可用
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        List<CredentialDO> usable = credentialMapper.selectByOwner(OWNER_KIND_PROVIDER, providerCode)
                .stream()
                .filter(row -> !STATUS_DISABLED.equals(row.getStatus()))
                .filter(row -> row.getCooldownUntil() == null || !row.getCooldownUntil().isAfter(now))
                .toList();
        if (usable.isEmpty()) {
            return null;
        }
        int cursor = rotation.computeIfAbsent(providerCode, key -> new AtomicInteger())
                .getAndIncrement();
        CredentialDO chosen = usable.get(Math.floorMod(cursor, usable.size()));
        try {
            return codec.decryptFromBytes(chosen.getKeyCipher());
        } catch (Exception e) {
            log.error("凭证解密失败按能力不可用降级 provider={} mask={} traceId=n/a",
                    providerCode, chosen.getKeyMask());
            return null;
        }
    }

    /**
     * 反馈：限流 → 冷却当次 key（时长经 system_param，缺省 60s；并发反馈幂等取更晚者）；
     * 鉴权失败 → 停用 + 告警（终态，补配新 key 而非复活）
     */
    @Override
    public void feedback(String providerCode, String usedKeyMask, FeedbackKind kind) {
        CredentialDO row = locateByMask(providerCode, usedKeyMask);
        if (row == null) {
            log.warn("凭证反馈定位不到行（可能已删） provider={} mask={}", providerCode, usedKeyMask);
            return;
        }
        if (kind == FeedbackKind.RATE_LIMITED) {
            long seconds = systemParams.getLong(COOLDOWN_SECONDS_PARAM, 60L);
            LocalDateTime until = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
                    .plusSeconds(seconds);
            credentialMapper.markCooldownLater(row.getId(), until);
            log.info("凭证限流冷却 provider={} mask={} until={}s", providerCode, usedKeyMask, seconds);
        } else {
            credentialMapper.markDisabled(row.getId());
            if (securityEventRecorder != null) {
                try {
                    io.opentelemetry.api.trace.SpanContext ctx =
                            io.opentelemetry.api.trace.Span.current().getSpanContext();
                    String traceId = (ctx != null && ctx.isValid()) ? ctx.getTraceId()
                            : org.slf4j.MDC.get("traceId");
                    securityEventRecorder.record(
                            cn.edu.scut.chatbot.security.audit.SecurityEventKind.CREDENTIAL_INVALID,
                            cn.edu.scut.chatbot.security.audit.SecurityDims.none(),
                            java.util.Map.of("provider", providerCode, "keyMask",
                                    usedKeyMask != null ? usedKeyMask : ""),
                            traceId);
                } catch (Exception e) {
                    log.warn("credentialInvalid 审计事件写入失败 provider={}", providerCode, e);
                }
            }
            log.error("凭证鉴权失败停用告警 errorCode=llm.auth provider={} mask={} traceId=n/a "
                    + "（终态——补配新 key 而非复活）", providerCode, usedKeyMask);
        }
    }

    private CredentialDO locateByMask(String providerCode, String usedKeyMask) {
        return credentialMapper.selectByOwner(OWNER_KIND_PROVIDER, providerCode).stream()
                .filter(row -> usedKeyMask != null && usedKeyMask.equals(row.getKeyMask()))
                .findFirst()
                .orElse(null);
    }
}
