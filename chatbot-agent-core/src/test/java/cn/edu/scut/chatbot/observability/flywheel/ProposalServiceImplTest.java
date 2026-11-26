// 守护纪律：提案状态机全迁移与同证据去重（T061 单元面——eval-and-flywheel 契约
// 第 5 节三纪律：只认证据、人工确认、去重）
package cn.edu.scut.chatbot.observability.flywheel;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProposalServiceImplTest {

    private ImprovementProposalMapper proposalMapper;
    private ProposalServiceImpl service;

    @BeforeEach
    void setUp() {
        proposalMapper = mock(ImprovementProposalMapper.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        when(idGenerator.nextId()).thenReturn(9_600_001L);
        service = new ProposalServiceImpl(proposalMapper, idGenerator);
    }

    private static ImprovementProposalDO row(long id, String status, String attribution) {
        ImprovementProposalDO row = new ImprovementProposalDO();
        row.setId(id);
        row.setStatus(status);
        row.setAttribution(attribution);
        row.setTarget("promptFragment");
        return row;
    }

    private static ProposalService.ProblemCard card(String dedupKey) {
        return new ProposalService.ProblemCard("回应切题度不足",
                List.of("eval_result#1", "trace=abc"), "promptFragment", "提升切题", dedupKey);
    }

    @Test
    void 建卡_合法落库pending_五类落点外拒绝() {
        service.createFromMining(card("turn:1"));

        verify(proposalMapper).insert(org.mockito.ArgumentMatchers.argThat(row ->
                ImprovementProposalDO.STATUS_PENDING.equals(row.getStatus())
                        && "promptFragment".equals(row.getTarget())
                        && row.getProblemCard().contains("eval_result#1")));

        assertThatThrownBy(() -> service.createFromMining(new ProposalService.ProblemCard(
                "描述", List.of(), "database", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("落点");
        assertThatThrownBy(() -> service.createFromMining(new ProposalService.ProblemCard(
                " ", List.of(), "promptFragment", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 同证据去重_先查后拒() {
        when(proposalMapper.selectByDedupKey("turn:1")).thenReturn(row(1, "pending", null));

        assertThatThrownBy(() -> service.createFromMining(card("turn:1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("去重");
    }

    @Test
    void 状态机全迁移_pending归因_接受_落地() {
        when(proposalMapper.selectById(1L))
                .thenReturn(row(1, "pending", null))
                .thenReturn(row(1, "attributed", "agent"))
                .thenReturn(row(1, "accepted", "agent"));

        service.attribute(1, "agent");
        verify(proposalMapper).updateAttribution(1, "attributed", "agent");

        service.accept(1);
        verify(proposalMapper).updateStatus(1, "accepted", null);

        service.markLanded(1, 42L);
        verify(proposalMapper).updateStatus(1, "landed", "contentVersion#42");
    }

    @Test
    void 非agent归因_接受被拒() {
        when(proposalMapper.selectById(2L)).thenReturn(row(2, "attributed", "rubricError"));

        assertThatThrownBy(() -> service.accept(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有归因为 agent 的提案可接受");
    }

    @Test
    void 拒绝_留理由留痕_无理由拒绝入参() {
        when(proposalMapper.selectById(3L)).thenReturn(row(3, "pending", null));

        service.reject(3, "证据不足");
        verify(proposalMapper).updateStatus(3, "rejected", "证据不足");

        assertThatThrownBy(() -> service.reject(3, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("理由");
    }

    @Test
    void 非法迁移_跳状态与回退均拒绝() {
        when(proposalMapper.selectById(4L)).thenReturn(row(4, "pending", null));

        assertThatThrownBy(() -> service.accept(4))   // pending 不可直接接受
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("状态机");

        when(proposalMapper.selectById(5L)).thenReturn(row(5, "landed", "agent"));
        assertThatThrownBy(() -> service.attribute(5, "agent"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("状态机");

        when(proposalMapper.selectById(6L)).thenReturn(row(6, "rejected", "agent"));
        assertThatThrownBy(() -> service.accept(6))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("状态机");
    }

    @Test
    void 归因字典外取值拒绝() {
        assertThatThrownBy(() -> service.attribute(7, "modelError"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent / caseMiss / rubricError");
    }
}
