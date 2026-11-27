package cn.edu.scut.chatbot.security.credential;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link CredentialRotationService} 实现（R17、FR-020）：
 * <ul>
 *   <li>逐行在独立事务中更新密文（中断后已处理行保持新密文，未处理行保持旧密文，无半加密状态）</li>
 *   <li>幂等判定：先用新密钥试解（GCM 认证标签天然防伪解密）——成功即已轮换、直接跳过</li>
 *   <li>轮换结果（total / rotated / skipped / failed）写入 security_event(adminOperate)</li>
 * </ul>
 */
@Service
public class CredentialRotationServiceImpl implements CredentialRotationService {

    private static final Logger log = LoggerFactory.getLogger(CredentialRotationServiceImpl.class);

    private final CredentialMapper credentialMapper;
    private final CredentialCodec currentCodec;
    private final SecurityEventRecorder recorder;
    private final TransactionTemplate txTemplate;

    public CredentialRotationServiceImpl(CredentialMapper credentialMapper,
                                         Optional<CredentialCodec> currentCodec,
                                         SecurityEventRecorder recorder,
                                         Optional<PlatformTransactionManager> txManager) {
        this.credentialMapper = credentialMapper;
        this.currentCodec = currentCodec.orElse(null);
        this.recorder = recorder;
        this.txTemplate = txManager.map(TransactionTemplate::new).orElse(null);
    }

    @Override
    public RotationReport rotate(byte[] newMasterKey) {
        if (currentCodec == null) {
            throw new IllegalStateException("当前主密钥未配置（chatbot.credential.key 缺失），无法执行轮换");
        }
        CredentialCodec newCodec = new CredentialCodec(newMasterKey);
        return rotateWith(currentCodec, newCodec);
    }

    /**
     * 显式指定旧/新编解码器的轮换入口（支持运维 CLI 与隔离单测）
     */
    public RotationReport rotateWith(CredentialCodec oldCodec, CredentialCodec newCodec) {
        List<CredentialDO> rows = credentialMapper.selectAll();
        int rotated = 0;
        int skipped = 0;
        int failed = 0;

        for (CredentialDO row : rows) {
            byte[] existingCipher = row.getKeyCipher();
            if (existingCipher == null || existingCipher.length == 0) {
                skipped++;
                continue;
            }
            // 幂等判定：若新钥可直接通过 GCM 认证解密，说明该行已轮换过，跳过
            if (newCodec.canDecryptBytes(existingCipher)) {
                skipped++;
                continue;
            }
            try {
                String plaintext = oldCodec.decryptFromBytes(existingCipher);
                byte[] reEncrypted = newCodec.encryptVersionedToBytes(plaintext, CredentialCodec.VERSION_V2);
                updateSingleRowInTx(row.getId(), reEncrypted);
                rotated++;
            } catch (Exception e) {
                failed++;
                log.error("主密钥轮换单行失败 errorCode=security.credential.rotate_failed traceId=n/a credentialId={} mask={}",
                        row.getId(), row.getKeyMask(), e);
            }
        }

        RotationReport report = new RotationReport(rows.size(), rotated, skipped, failed);
        recordAudit(report);
        log.info("主密钥轮换完成 total={} rotated={} skipped={} failed={}",
                report.total(), report.rotated(), report.skipped(), report.failed());
        return report;
    }

    private void updateSingleRowInTx(long id, byte[] newCipher) {
        if (txTemplate != null) {
            txTemplate.executeWithoutResult(status -> credentialMapper.updateKeyCipher(id, newCipher));
        } else {
            credentialMapper.updateKeyCipher(id, newCipher);
        }
    }

    private void recordAudit(RotationReport report) {
        try {
            recorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(),
                    Map.of("action", "credentialMasterKeyRotate",
                            "total", report.total(),
                            "rotated", report.rotated(),
                            "skipped", report.skipped(),
                            "failed", report.failed()),
                    null);
        } catch (Exception e) {
            log.warn("主密钥轮换审计记录失败", e);
        }
    }
}
