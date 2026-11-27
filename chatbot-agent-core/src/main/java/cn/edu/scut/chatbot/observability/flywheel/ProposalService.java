package cn.edu.scut.chatbot.observability.flywheel;

import java.util.List;

/**
 * 提案管线门面（eval-and-flywheel 契约第 5 节）：状态机
 * pending → attributed → accepted → landed | rejected，同证据去重。
 */
public interface ProposalService {

    long createFromMining(ProblemCard card);

    void attribute(long proposalId, String attribution);

    /**
     * 接受：进入对应落点编辑流程（落点内容编辑走既有 content_version 机制——
     * 本方法只做状态迁移与校验，不改任何配置）
     */
    void accept(long proposalId);

    void reject(long proposalId, String reason);

    void markLanded(long proposalId, long contentVersionId);

    ProposalView getProposal(long proposalId);

    List<ProposalView> listProposals(String status, int page, int size);

    /**
     * 挖掘产出的问题卡（MiningScheduler 之外的 manual 来源也走这里）
     */
    record ProblemCard(String description,
                       List<String> evidence,
                       String suggestTarget,
                       String expectedEffect,
                       String dedupKey) {
    }
}
