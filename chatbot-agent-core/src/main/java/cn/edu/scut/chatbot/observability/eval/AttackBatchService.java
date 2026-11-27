package cn.edu.scut.chatbot.observability.eval;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 注入对抗用例库与回归批次门面（T098/T099/T100，eval-and-flywheel 契约第 7 节）：
 * 管理用例 CRUD、触发对抗执行批次（上线前全量 / 发布门禁安全面自动附 / 每周回归）、
 * 汇总硬防线击穿数与软防线统计比例。
 */
public interface AttackBatchService {

    /**
     * 执行全部启用对抗用例，产出 safety 批次报告（硬防线零击穿才通过）
     */
    AttackBatchReport runAllEnabled();

    /**
     * 执行指定 id 清单的对抗用例
     */
    AttackBatchReport runBatch(List<Long> caseIds);

    // ===== 用例维护（management 收口视图） =====

    List<AttackCaseView> listCases(String channel);

    long createCase(AttackCaseCreateRequest request, String operator);

    void updateCase(long id, AttackCaseCreateRequest request, String operator);

    void setEnabled(long id, boolean enabled, String operator);

    void deleteCase(long id, String operator);

    record AttackCaseView(
            Long id,
            String channel,
            String title,
            String payload,
            String scenario,
            String expectation,
            String assertion,
            boolean enabled,
            String lastVerdict,
            LocalDateTime updatedAt
    ) {
    }

    record AttackCaseCreateRequest(
            String channel,
            String title,
            String payload,
            String scenario,
            String expectation,
            String assertion
    ) {
    }

    record CaseExecutionSummary(
            long caseId,
            String channel,
            String title,
            String verdict,
            boolean hardDefenseHeld,
            boolean softDefenseBypassed,
            List<AttackAssertionEvaluator.CodeCheckResult> codeChecks,
            String explanation
    ) {
    }

    record AttackBatchReport(
            long batchId,
            boolean batchPassed,
            int total,
            int passed,
            int hardBreaches,
            int softDefenseBypassed,
            double softDefenseBypassRate,
            List<CaseExecutionSummary> caseResults
    ) {
    }
}
