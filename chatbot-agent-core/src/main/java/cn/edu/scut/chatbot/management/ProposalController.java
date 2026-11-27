package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.observability.flywheel.ProposalService;
import cn.edu.scut.chatbot.observability.flywheel.ProposalView;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提案审阅门面（eval-and-flywheel 契约第 5 节、T060）：列表 / 归因 / 接受 / 拒绝 /
 * 落地关联。控制器只调领域门面不见 DO / Mapper（宪法第五条）。
 */
@RestController
@RequestMapping("/api/management/proposals")
public class ProposalController {

    private final ProposalService proposalService;

    public ProposalController(ProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @GetMapping
    public List<ProposalView> list(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return proposalService.listProposals(status, page, size);
    }

    @GetMapping("/{proposalId}")
    public ProposalView get(@PathVariable long proposalId) {
        return proposalService.getProposal(proposalId);
    }

    /**
     * 归因标注（三分支：agent / caseMiss / rubricError）
     */
    @PostMapping("/{proposalId}/attribute")
    public void attribute(@PathVariable long proposalId, @RequestParam String attribution) {
        proposalService.attribute(proposalId, attribution);
    }

    /**
     * 接受（仅 agent 归因可接受——进入对应落点编辑流程）
     */
    @PostMapping("/{proposalId}/accept")
    public void accept(@PathVariable long proposalId) {
        proposalService.accept(proposalId);
    }

    @PostMapping("/{proposalId}/reject")
    public void reject(@PathVariable long proposalId, @RequestParam String reason) {
        proposalService.reject(proposalId, reason);
    }

    /**
     * 落地关联（发布版本留痕）
     */
    @PostMapping("/{proposalId}/landed")
    public void markLanded(@PathVariable long proposalId, @RequestParam long contentVersionId) {
        proposalService.markLanded(proposalId, contentVersionId);
    }
}
