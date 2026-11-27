package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.observability.eval.EvalBatchView;
import cn.edu.scut.chatbot.observability.replay.ReplayService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 隔离重放与对照实验 REST（006 US7 / T093）。
 */
@RestController
@RequestMapping("/api/management/replay")
public class ReplayAdminController {

    private final ReplayService replayService;

    public ReplayAdminController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/run")
    @AuditAction(target = "replay", action = "run", summary = "发起隔离重放实验")
    public ReplayService.BatchReplayReport runBatch(@RequestBody(required = false) RunReplayRequest request) {
        if (request == null) {
            return replayService.runBatch(List.of(), null, null, null);
        }
        return replayService.runBatch(
                request.caseIds(), request.kOverride(), request.versionRef(), request.experimentId());
    }

    @GetMapping("/batches")
    public List<EvalBatchView> listBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String experimentId) {
        return replayService.listReplayBatches(page, size, experimentId);
    }

    @GetMapping("/{experimentId}")
    public ReplayService.BatchReplayComparison getExperiment(@PathVariable String experimentId) {
        return replayService.getExperimentComparison(experimentId);
    }

    public record RunReplayRequest(
            List<Long> caseIds,
            Integer kOverride,
            String versionRef,
            String experimentId
    ) {
    }
}
