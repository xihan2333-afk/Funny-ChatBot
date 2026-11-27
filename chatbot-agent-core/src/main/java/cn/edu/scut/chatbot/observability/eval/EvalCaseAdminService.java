package cn.edu.scut.chatbot.observability.eval;

import java.util.List;

/**
 * 评测集管理门面（eval-and-flywheel 契约第 3 节、FR-028）。
 */
public interface EvalCaseAdminService {

    /**
     * 人工录入（结构校验后入库，status=ready）
     */
    EvalCaseView create(CreateCaseCommand command);

    /**
     * 场景卡生成辅助：eval_judge 产初稿（origin=generated、status=draft 待人工修订，
     * 不直接进入可回测状态）
     */
    EvalCaseView generateDraft(GenerateHint hint);

    /**
     * Rubric 修订（rubric_version 递增留痕——归因 rubricError 的回补动作）
     */
    void reviseRubric(long caseId, String newRubricJson);

    /**
     * 完整修订评测用例（草稿定稿、场景卡/类型/Rubric 调整；006 US7 / T091）
     */
    EvalCaseView updateCase(long caseId, UpdateCaseCommand command);

    record UpdateCaseCommand(String caseType, String scenarioJson, String rubricJson, String status) {
    }

    /**
     * 低分结果一键沉淀错题（case_type=fault、origin=marked）
     */
    EvalCaseView promoteFromResult(long evalResultId);

    /**
     * 高分结果一键升黄金集（case_type=golden——2.5.6 黄金集来源的对称动作）
     */
    EvalCaseView promoteGoldenFromResult(long evalResultId);

    /**
     * 废弃（status=discarded，不物理删除——三层资产的留痕纪律）
     */
    void discard(long caseId);

    EvalCaseView getCase(long caseId);

    List<EvalCaseView> listCases(String caseType, String status, int page, int size);
}
