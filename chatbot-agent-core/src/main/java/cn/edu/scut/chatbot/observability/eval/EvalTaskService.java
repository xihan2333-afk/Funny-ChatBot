package cn.edu.scut.chatbot.observability.eval;

import java.util.List;

/**
 * 评估任务门面（eval-and-flywheel 契约第 2 节）：三类任务调度与盲评回填。
 * startReplay 归 US6（ReplayService 共用批次表，入口在重放侧）。
 */
public interface EvalTaskService {

    /**
     * 每日抽样：每群 observe.eval.dailySamplePerGroup 条 Turn，
     * observe.eval.blindRatio 比例标记 source_kind=blind。
     * 并发上限占满时跳过本周期（调度语义——记日志不抛异常），返回 null。
     */
    EvalBatchView startDaily();

    /**
     * 手动圈选（时间范围 / 群 / 样本数）。并发上限占满时抛
     * observe.eval_busy（管理面语义——用户需明确知道被拒）。
     */
    EvalBatchView startManual(EvalScope scope);

    /**
     * 盲评人工打分回填：以盲评机器结果为参照插入对照行（judge_model=null）
     */
    void submitBlindScore(long resultId, BlindScore score);

    EvalBatchView getBatch(long batchId);

    List<EvalBatchView> listBatches(String batchKind, int page, int size);

    List<EvalResultView> listResults(long batchId);

    EvalBatchSummaryView getBatchSummary(long batchId);

    List<EvalResultView> listBlindQueue(int page, int size);

    record EvalBatchSummaryView(
            long batchId,
            String batchKind,
            String status,
            int totalCount,
            int passedCount,
            int failedCount,
            double passRate,
            double avgTotalScore,
            String costStat,
            String finishedAt
    ) {
    }
}
