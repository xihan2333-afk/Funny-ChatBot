package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.observability.eval.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评估管理门面（eval-and-flywheel 契约第 2/3 节、T049）：评估任务触发、批次与
 * 结果查询、归因标注、盲评打分、评测集管理。控制器只调领域门面不见 DO / Mapper
 * （宪法第五条 management 收口）。认证过滤器归 US9，本阶段接口先裸奔注册。
 */
@RestController
@RequestMapping("/api/management/eval")
public class EvalAdminController {

    private final EvalTaskService evalTaskService;
    private final EvalAttributionService attributionService;
    private final EvalCaseAdminService caseAdminService;

    public EvalAdminController(EvalTaskService evalTaskService,
                               EvalAttributionService attributionService,
                               EvalCaseAdminService caseAdminService) {
        this.evalTaskService = evalTaskService;
        this.attributionService = attributionService;
        this.caseAdminService = caseAdminService;
    }

    // ===== 评估任务 =====

    /**
     * 手动触发每日抽样语义的批次（调度侧的同一入口）
     */
    @PostMapping("/daily")
    public EvalBatchView startDaily() {
        return evalTaskService.startDaily();
    }

    @PostMapping("/manual")
    public EvalBatchView startManual(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) Integer samplePerGroup) {
        return evalTaskService.startManual(
                new EvalScope(from, to, platform, botId, groupId, samplePerGroup));
    }

    @GetMapping("/batches")
    public List<EvalBatchView> listBatches(
            @RequestParam(required = false) String batchKind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return evalTaskService.listBatches(batchKind, page, size);
    }

    @GetMapping("/batches/{batchId}")
    public EvalBatchView getBatch(@PathVariable long batchId) {
        return evalTaskService.getBatch(batchId);
    }

    @GetMapping("/results")
    public List<EvalResultView> listResults(
            @RequestParam long batchId) {
        return evalTaskService.listResults(batchId);
    }

    // ===== 归因与盲评 =====

    /**
     * 盲评人工打分回填（以盲评机器结果为参照插对照行）
     */
    @PostMapping("/results/{resultId}/blind-score")
    public void submitBlindScore(@PathVariable long resultId,
                                 @RequestParam Integer total,
                                 @RequestParam(required = false) Integer respond,
                                 @RequestParam(required = false) Integer process,
                                 @RequestParam(required = false) Integer form,
                                 @RequestParam(required = false) Integer cost,
                                 @RequestParam(required = false) Integer safety,
                                 @RequestParam(defaultValue = "pass") String decision,
                                 @RequestParam(required = false) String explanation) {
        evalTaskService.submitBlindScore(resultId, new BlindScore(
                new EvalScore(total, respond, process, form, cost, safety), decision, explanation));
    }

    /**
     * 归因标注（三分支：agent / caseMiss / rubricError）
     */
    @PostMapping("/results/{resultId}/attribution")
    public void attribute(@PathVariable long resultId, @RequestParam String attribution) {
        attributionService.attribute(resultId, attribution);
    }

    /**
     * 人机一致率（时间窗 [from, to)——趋势按窗分段取数）
     */
    @GetMapping("/agreement")
    public EvalAttributionService.AgreementReport agreement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return attributionService.agreement(from, to);
    }

    // ===== 评测集管理 =====

    @PostMapping("/cases")
    public EvalCaseView createCase(@RequestBody CreateCaseCommand command) {
        return caseAdminService.create(command);
    }

    /**
     * 场景卡生成辅助（eval_judge 产初稿，status=draft 待人工修订）
     */
    @PostMapping("/cases/draft")
    public EvalCaseView generateDraft(@RequestParam String interactionNature,
                                      @RequestParam String capabilityDomain,
                                      @RequestParam(required = false) String note) {
        return caseAdminService.generateDraft(
                new GenerateHint(interactionNature, capabilityDomain, note));
    }

    @PostMapping("/cases/{caseId}/rubric")
    public void reviseRubric(@PathVariable long caseId, @RequestBody String newRubricJson) {
        caseAdminService.reviseRubric(caseId, newRubricJson);
    }

    @PostMapping("/cases/{caseId}/discard")
    public void discard(@PathVariable long caseId) {
        caseAdminService.discard(caseId);
    }

    /**
     * 低分一键沉淀错题（origin=marked、case_type=fault）
     */
    @PostMapping("/results/{resultId}/promote")
    public EvalCaseView promoteFromResult(@PathVariable long resultId) {
        return caseAdminService.promoteFromResult(resultId);
    }

    /**
     * 高分一键升黄金集（case_type=golden）
     */
    @PostMapping("/results/{resultId}/promote-golden")
    public EvalCaseView promoteGoldenFromResult(@PathVariable long resultId) {
        return caseAdminService.promoteGoldenFromResult(resultId);
    }

    @GetMapping("/cases")
    public List<EvalCaseView> listCases(@RequestParam(required = false) String caseType,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return caseAdminService.listCases(caseType, status, page, size);
    }
}
