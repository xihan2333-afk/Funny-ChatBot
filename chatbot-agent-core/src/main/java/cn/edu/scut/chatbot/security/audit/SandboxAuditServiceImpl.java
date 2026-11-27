package cn.edu.scut.chatbot.security.audit;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SandboxAuditService} 实现（T090 + T091）：
 * <ol>
 *   <li>输出特征扫描（{@link OutputFeatureScanner}）：命中提权/宿主路径/docker.sock 等特征</li>
 *   <li>异常模式评估：退出码异常（137 OOMKill / 126 / 127 / 负数信号）、执行超时或资源超限错误码、
 *       同群 5 分钟内高频执行超阈值（{@code security.sandbox.highFreqPer5Min}，默认 10）</li>
 * </ol>
 * 任一命中即记 {@code security_event(sandboxAbnormal)}，不拦截正常返回（留痕纪律）。
 */
@Service
public class SandboxAuditServiceImpl implements SandboxAuditService {

    private static final Logger log = LoggerFactory.getLogger(SandboxAuditServiceImpl.class);

    static final String PARAM_HIGH_FREQ_PER_5MIN = "security.sandbox.highFreqPer5Min";
    private static final int DEFAULT_HIGH_FREQ_PER_5MIN = 10;
    private static final long WINDOW_MILLIS = 5 * 60_000L;

    private static final Set<Integer> ABNORMAL_EXIT_CODES = Set.of(126, 127, 137, 139, 143);

    private final OutputFeatureScanner featureScanner;
    private final SecurityEventRecorder recorder;
    private final SystemParamService systemParams;
    private final Clock clock;

    /**
     * 同群近 5 分钟执行时间戳队列（滑动窗口计数——滥用与探测信号）
     */
    private final Map<String, Deque<Long>> recentExecutions = new ConcurrentHashMap<>();

    public SandboxAuditServiceImpl(OutputFeatureScanner featureScanner,
                                   SecurityEventRecorder recorder,
                                   SystemParamService systemParams,
                                   Clock clock) {
        this.featureScanner = featureScanner;
        this.recorder = recorder;
        this.systemParams = systemParams;
        this.clock = clock;
    }

    @Override
    public AuditResult audit(SandboxExecutionView response, SecurityDims dims, String traceId) {
        if (response == null) {
            return new AuditResult(false, List.of(), List.of());
        }
        List<String> features = featureScanner.scan(response.stdout(), response.stderr());
        List<String> anomalies = new ArrayList<>();

        if (!features.isEmpty()) {
            anomalies.add("outputFeatureMatched");
        }
        if (response.timedOut()) {
            anomalies.add("timedOut");
        }
        if (ABNORMAL_EXIT_CODES.contains(response.exitCode()) || response.exitCode() < 0) {
            anomalies.add("abnormalExitCode:" + response.exitCode());
        }
        if (response.errorCode() != null && !response.errorCode().isBlank()) {
            anomalies.add("resourceOrError:" + response.errorCode());
        }
        int recentCount = recordAndCountGroupFrequency(dims);
        int freqThreshold = systemParams.getInt(PARAM_HIGH_FREQ_PER_5MIN, DEFAULT_HIGH_FREQ_PER_5MIN);
        if (freqThreshold > 0 && recentCount >= freqThreshold) {
            anomalies.add("highFrequency:" + recentCount + "/" + freqThreshold);
        }

        boolean abnormal = !anomalies.isEmpty();
        if (abnormal) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("exitCode", response.exitCode());
            detail.put("timedOut", response.timedOut());
            detail.put("wallMs", response.wallMs());
            detail.put("matchedFeatures", features);
            detail.put("anomalyReasons", anomalies);
            if (response.errorCode() != null) {
                detail.put("errorCode", response.errorCode());
            }
            recorder.record(SecurityEventKind.SANDBOX_ABNORMAL,
                    dims != null ? dims : SecurityDims.none(),
                    detail, traceId);
            log.warn("沙箱执行命中异常审计 reasons={} features={} exitCode={} wallMs={}",
                    anomalies, features, response.exitCode(), response.wallMs());
        }
        return new AuditResult(abnormal, features, anomalies);
    }

    private int recordAndCountGroupFrequency(SecurityDims dims) {
        if (dims == null || dims.groupId() == null) {
            return 0;
        }
        String key = dims.platform() + ":" + dims.botId() + ":" + dims.groupId();
        long now = clock.millis();
        long cutoff = now - WINDOW_MILLIS;
        Deque<Long> deque = recentExecutions.computeIfAbsent(key,
                ignored -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        deque.addLast(now);
        Long head;
        while ((head = deque.peekFirst()) != null && head < cutoff) {
            deque.pollFirst();
        }
        return deque.size();
    }
}
