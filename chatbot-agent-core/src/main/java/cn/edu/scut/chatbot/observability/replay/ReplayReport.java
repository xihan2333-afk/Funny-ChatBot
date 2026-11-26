package cn.edu.scut.chatbot.observability.replay;

import java.util.List;

/**
 * dry-run 重放报告（eval-and-flywheel 契约第 4 节）：k 次执行明细（影子发言清单、
 * 断言结果、创建即取消的任务清单）、通过率（Pass@k）、版本分组键（注入前后对照的
 * 分组口径）与重放成本（is_replay=1 单独合计，SC-005）。
 */
public record ReplayReport(long batchId,
                           long caseId,
                           int executions,
                           int passed,
                           double passRate,
                           String versionGroupKey,
                           List<ExecutionDetail> details,
                           String replayCostJson,
                           String failureReason) {

    /**
     * 单次执行明细：attempt 序号（1 起）、判定、影子发言、影子任务、失败原因
     * （评估失败时 decision 为空 failureReason 载原因）
     */
    public record ExecutionDetail(int attempt,
                                  String decision,
                                  List<String> speeches,
                                  List<String> createdTasks,
                                  String failureReason) {
    }
}
