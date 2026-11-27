package cn.edu.scut.chatbot.security.credential;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.management.PageResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 运维工具门面服务（A12）：主密钥轮换（支持 Base64 入参或环境变量 CHATBOT_CREDENTIAL_NEW_KEY）与轮换历史记录。
 */
@Service
public class CredentialRotationOpsService {

    private static final String ENV_STAGED_KEY = "CHATBOT_CREDENTIAL_NEW_KEY";

    private final CredentialRotationService rotationService;
    private final CredentialRotationLogMapper logMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public CredentialRotationOpsService(CredentialRotationService rotationService,
                                        CredentialRotationLogMapper logMapper,
                                        IdGenerator idGenerator,
                                        Clock clock) {
        this.rotationService = rotationService;
        this.logMapper = logMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public RotationLogView triggerRotation(String newMasterKeyBase64, boolean useEnvStagedKey, String operator) {
        String keyBase64 = newMasterKeyBase64;
        if (useEnvStagedKey) {
            keyBase64 = System.getenv(ENV_STAGED_KEY);
            if (keyBase64 == null || keyBase64.isBlank()) {
                throw new IllegalArgumentException("环境变量 " + ENV_STAGED_KEY + " 未配置");
            }
        }
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalArgumentException("必须提供 newMasterKeyBase64 或设置 useEnvStagedKey=true");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("新主密钥不是合法的 Base64 编码");
        }
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 主密钥必须为 32 字节（当前 " + keyBytes.length + " 字节）");
        }

        long logId = idGenerator.nextId();
        CredentialRotationLogDO row = new CredentialRotationLogDO();
        row.setId(logId);
        row.setOperator(operator != null ? operator : "admin");
        row.setStatus("running");
        row.setRotatedCount(0);
        row.setSkippedCount(0);
        row.setStartedAt(LocalDateTime.now(clock));
        logMapper.insert(row);

        try {
            CredentialRotationService.RotationReport report = rotationService.rotate(keyBytes);
            row.setStatus(report.failed() > 0 ? "failed" : "completed");
            row.setRotatedCount(report.rotated());
            row.setSkippedCount(report.skipped());
            row.setFinishedAt(LocalDateTime.now(clock));
            logMapper.updateFinished(row);
        } catch (Exception e) {
            row.setStatus("failed");
            row.setErrorMessage(e.getMessage());
            row.setFinishedAt(LocalDateTime.now(clock));
            logMapper.updateFinished(row);
            throw e;
        }
        return toView(row);
    }

    public PageResult<RotationLogView> listHistory(int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        long total = logMapper.countAll();
        List<RotationLogView> items = logMapper.selectAllPaged(PageResult.offset(p, ps), ps).stream()
                .map(CredentialRotationOpsService::toView)
                .toList();
        return PageResult.of(items, total, p, ps);
    }

    private static RotationLogView toView(CredentialRotationLogDO r) {
        return new RotationLogView(
                r.getId(), r.getOperator(), r.getStatus(), r.getRotatedCount(), r.getSkippedCount(),
                r.getErrorMessage(),
                r.getStartedAt() != null ? r.getStartedAt().toString() : null,
                r.getFinishedAt() != null ? r.getFinishedAt().toString() : null);
    }

    public record RotationLogView(Long id, String operator, String status, Integer rotatedCount,
                                  Integer skippedCount, String errorMessage, String startedAt, String finishedAt) {
    }
}
