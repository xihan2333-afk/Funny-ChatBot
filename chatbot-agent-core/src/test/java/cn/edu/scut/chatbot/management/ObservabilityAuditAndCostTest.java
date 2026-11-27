package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.infrastructure.es.CostQuery;
import cn.edu.scut.chatbot.infrastructure.es.CostReport;
import cn.edu.scut.chatbot.infrastructure.es.MetricQueryServiceImpl;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogAggregation;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper;
import cn.edu.scut.chatbot.observability.eval.*;
import cn.edu.scut.chatbot.security.audit.SecurityAuditQueryService;
import cn.edu.scut.chatbot.security.audit.SecurityEventDO;
import cn.edu.scut.chatbot.security.audit.SecurityEventMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ObservabilityAuditAndCostTest {

    @Test
    void securityAuditQueryServiceReturnsPagedAuditEvents() {
        SecurityEventMapper mapper = mock(SecurityEventMapper.class);
        SecurityEventDO row = new SecurityEventDO();
        row.setId(11L);
        row.setEventKind("admin_audit");
        row.setUserId("admin");
        row.setTraceId("trace-123");
        row.setDetail("{\"target\":\"group_config\"}");
        row.setCreatedAt(LocalDateTime.of(2026, 11, 27, 12, 0));

        when(mapper.countPage("admin_audit", null, null, null, null, null, null, null)).thenReturn(1L);
        when(mapper.selectPage("admin_audit", null, null, null, null, null, null, null, 0, 20))
                .thenReturn(List.of(row));

        SecurityAuditQueryService service = new SecurityAuditQueryService(mapper);
        PageResult<SecurityAuditQueryService.AuditEventView> page = service.queryAuditEvents(
                "admin_audit", null, null, null, null, null, null, null, 1, 20);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).traceId()).isEqualTo("trace-123");
    }

    @Test
    void costQueryPassesAllDimensionFiltersToMapper() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        LlmCallLogAggregation agg = new LlmCallLogAggregation();
        agg.setDimensionKey("chat_main");
        agg.setCalls(5L);
        agg.setInputTokens(1000L);
        agg.setOutputTokens(200L);
        agg.setCachedTokens(100L);
        agg.setCostAmount(new BigDecimal("0.015"));

        when(mapper.selectCostAggregationFiltered(
                "SLOT", null, null, "g1", "onebot-v11", "bot1", "chat_main", "gpt-4o"))
                .thenReturn(List.of(agg));

        MetricQueryServiceImpl service = new MetricQueryServiceImpl(
                mock(ElasticsearchClient.class), mapper, null, null, null, null,
                Clock.fixed(Instant.parse("2026-11-27T06:00:00Z"), ZoneId.of("Asia/Shanghai")));

        CostReport report = service.cost(new CostQuery(
                CostQuery.CostDim.SLOT, null, null, "g1", "onebot-v11", "bot1", "chat_main", "gpt-4o"));
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0).calls()).isEqualTo(5L);
    }

    @Test
    void evalTaskServiceComputesBatchSummaryAndFiltersBlindQueue() {
        EvalBatchMapper batchMapper = mock(EvalBatchMapper.class);
        EvalResultMapper resultMapper = mock(EvalResultMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T06:00:00Z"), ZoneId.of("Asia/Shanghai"));

        EvalBatchDO batch = new EvalBatchDO();
        batch.setId(10L);
        batch.setBatchKind("daily");
        batch.setStatus("done");
        when(batchMapper.selectById(10L)).thenReturn(batch);

        EvalResultDO r1 = new EvalResultDO();
        r1.setId(1L);
        r1.setBatchId(10L);
        r1.setTurnId(100L);
        r1.setDecision("pass");
        r1.setScore("{\"total\":90}");
        r1.setJudgeModel("gpt-4o");

        EvalResultDO r2 = new EvalResultDO();
        r2.setId(2L);
        r2.setBatchId(10L);
        r2.setTurnId(101L);
        r2.setDecision("badcase");
        r2.setScore("{\"total\":40}");
        r2.setJudgeModel("gpt-4o");

        EvalResultDO r2Human = new EvalResultDO();
        r2Human.setId(3L);
        r2Human.setBatchId(10L);
        r2Human.setTurnId(101L);
        r2Human.setDecision("badcase");
        r2Human.setScore("{\"total\":45}");
        r2Human.setJudgeModel(null);

        when(resultMapper.selectByBatch(10L)).thenReturn(List.of(r1, r2));
        when(resultMapper.selectBlindBetween(any(), any())).thenReturn(List.of(r1, r2, r2Human));

        EvalTaskServiceImpl service = new EvalTaskServiceImpl(
                batchMapper, resultMapper, null, null, null, null, null, null, null, null, clock, null);

        EvalTaskService.EvalBatchSummaryView summary = service.getBatchSummary(10L);
        assertThat(summary.totalCount()).isEqualTo(2);
        assertThat(summary.passedCount()).isEqualTo(1);
        assertThat(summary.passRate()).isEqualTo(0.5);
        assertThat(summary.avgTotalScore()).isEqualTo(65.0);

        List<EvalResultView> blindQueue = service.listBlindQueue(0, 20);
        assertThat(blindQueue).hasSize(1);
        assertThat(blindQueue.get(0).id()).isEqualTo(1L);
    }
}
