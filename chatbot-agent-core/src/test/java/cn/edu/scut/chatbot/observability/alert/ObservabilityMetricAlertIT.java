// 守护纪律：US3 数据面端到端（spec US3 验收场景 1/2/3、T034）——
// 成本报表 is_replay=0 口径按群/日聚合正确、链路反查 traceId 与 Span 树取回、
// 告警命中落库且窗口内不重复、停用规则不再判定
package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.engine.turn.TurnDO;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.fixtures.AbstractObservabilityIT;
import cn.edu.scut.chatbot.infrastructure.es.*;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogDO;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 指标成本统计与告警引擎 IT（T034）：MySQL 事实源聚合 + ES Span 检索 + 告警引擎
 * 调度命中的三段验证。告警轮直接调 evaluateOnce（调度节流归单测语义，IT 验证
 * 判定链与数据库交互）。通知断言按规则 id 精确圈定——其它内置规则窗口无数据
 * 不落库，不影响计数。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObservabilityMetricAlertIT extends AbstractObservabilityIT {

    private static final String TRACE_INDEX = "traces-it";
    private static final String TRACE_ID = "it-trace-0001";

    @Autowired
    private LlmCallLogMapper llmCallLogMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private TurnMapper turnMapper;
    @Autowired
    private MetricQueryService metricQueryService;
    @Autowired
    private TraceQueryService traceQueryService;
    @Autowired
    private AlertEngine alertEngine;
    @Autowired
    private AlertRuleMapper alertRuleMapper;
    @Autowired
    private AlertNotificationMapper notificationMapper;
    @Autowired
    private ElasticsearchClient esClient;
    @Autowired
    private Clock clock;

    @BeforeAll
    void seedCostRows() {
        llmCallLogMapper.insertBatch(List.of(
                callLog("qq", "bot01", "80001", 100, 50, 0, "1.50", false),
                callLog("qq", "bot01", "80001", 200, 80, 40, "2.50", false),
                callLog("qq", "bot01", "80002", 300, 120, 0, "3.00", false),
                // 重放成本单独记账：不进线上报表（SC-005）
                callLog("qq", "bot01", "80001", 999, 999, 0, "9.99", true)));
    }

    @BeforeAll
    void setUpTraceIndexTemplate() throws Exception {
        // 共享 ES 容器可能残留上一轮的 traces-it（traceId 被动态映射成 text），
        // 索引模板只对新建索引生效——先删旧索引再立模板
        try {
            esClient.indices().delete(d -> d.index(TRACE_INDEX));
        } catch (Exception ignored) {
            // 索引不存在即无需清理
        }
        // 无显式 mapping 时 traceId 被动态映射为 text，terms 聚合直接 all shards failed——
        // 写文档前先立普通索引模板（非 data stream），把检索与聚合要用的字段钉成 keyword
        esClient.indices().putIndexTemplate(p -> p
                .name("traces-it-template")
                .indexPatterns(List.of(TRACE_INDEX))
                // 层级纪律：内置 100 < es-init 生产模板 200 < 测试模板 300（同优先级
                // 多模板匹配会让索引/数据流创建歧义——与 LogStreamCategoryIT 同坑）
                .priority(300)
                .template(t -> t
                        .mappings(m -> m
                                .properties("traceId", pr -> pr.keyword(k -> k))
                                .properties("spanId", pr -> pr.keyword(k -> k))
                                .properties("parentSpanId", pr -> pr.keyword(k -> k))
                                .properties("name", pr -> pr.keyword(k -> k))
                                .properties("startTimeMillis", pr -> pr.long_(l -> l))
                                .properties("durationMillis", pr -> pr.long_(l -> l))
                                .properties("attributes", pr -> pr.object(o -> o)))));
    }

    @AfterAll
    void cleanEsIndex() {
        try {
            esClient.indices().delete(d -> d.index(TRACE_INDEX));
        } catch (Exception ignored) {
            // 索引不存在即无需清理
        }
        try {
            esClient.indices().deleteIndexTemplate(t -> t.name("traces-it-template"));
        } catch (Exception ignored) {
            // 模板不存在即无需清理
        }
    }

    @Test
    void 成本报表按群与日聚合且重放不进线上口径() {
        // 共享 MySQL 容器里 llm_call_log 还落着其它 IT 的数据（qq:10000:80001 等），
        // 测试隔离靠维度过滤完成：群维度只圈定本测试的 bot01 两群，不锁全表行数
        CostReport byGroup = metricQueryService.cost(
                new CostQuery(CostQuery.CostDim.GROUP, null, null, null));
        // dimensionKey 形如 qq:bot01:80001（platform:botId:groupId），bot01 在中段
        List<CostReport.CostRow> bot01Rows = byGroup.rows().stream()
                .filter(r -> r.dimensionKey().contains(":bot01:")).toList();
        assertThat(bot01Rows).hasSize(2);
        CostReport.CostRow groupA = bot01Rows.stream()
                .filter(r -> r.dimensionKey().endsWith("80001")).findFirst().orElseThrow();
        assertThat(groupA.calls()).isEqualTo(2);
        assertThat(groupA.inputTokens()).isEqualTo(300);
        assertThat(groupA.outputTokens()).isEqualTo(130);
        assertThat(groupA.cachedTokens()).isEqualTo(40);
        assertThat(groupA.costAmount()).isEqualByComparingTo(new BigDecimal("4.00"));

        // 日维度不锁行数与精确总额（其它 IT 数据也按日聚合进来），只验 bot01 的
        // 线上口径贡献下限（1.50+2.50+3.00，重放 9.99 不算）与行形态（yyyy-MM-dd）
        CostReport byDay = metricQueryService.cost(
                new CostQuery(CostQuery.CostDim.DAY, null, null, null));
        assertThat(byDay.rows())
                .anyMatch(r -> r.dimensionKey().matches("\\d{4}-\\d{2}-\\d{2}"));
        BigDecimal dayTotal = byDay.rows().stream()
                .map(CostReport.CostRow::costAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dayTotal).isGreaterThanOrEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void 链路反查traceId并取回完整Span树() throws Exception {
        // 模拟 Collector 写入的 Span 文档（intake → run → llm.call 三层树）
        esClient.index(i -> i.index(TRACE_INDEX).id("s1").document(Map.of(
                "traceId", TRACE_ID, "spanId", "span-1", "parentSpanId", "",
                "name", "intake", "startTimeMillis", 1000L, "durationMillis", 5L,
                "attributes", Map.of("chatbot.group.platform", "qq",
                        "chatbot.group.bot_id", "bot01", "chatbot.group_id", "80001"))));
        esClient.index(i -> i.index(TRACE_INDEX).id("s2").document(Map.of(
                "traceId", TRACE_ID, "spanId", "span-2", "parentSpanId", "span-1",
                "name", "run", "startTimeMillis", 1100L, "durationMillis", 900L,
                "attributes", Map.of("chatbot.group_id", "80001", "chatbot.slot", "chat_main"))));
        esClient.index(i -> i.index(TRACE_INDEX).id("s3").document(Map.of(
                "traceId", TRACE_ID, "spanId", "span-3", "parentSpanId", "span-2",
                "name", "llm.call", "startTimeMillis", 1200L, "durationMillis", 300L,
                "attributes", Map.of("chatbot.slot", "chat_main"))));
        esClient.indices().refresh(r -> r.index(TRACE_INDEX));

        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                !traceQueryService.searchTraces(new TraceFilter(
                        "qq", "bot01", "80001", null, null, null)).isEmpty());

        List<String> traceIds = traceQueryService.searchTraces(
                new TraceFilter("qq", "bot01", "80001", null, null, null));
        assertThat(traceIds).contains(TRACE_ID);

        List<SpanView> spans = traceQueryService.getTrace(TRACE_ID);
        assertThat(spans).hasSize(3);
        assertThat(spans).extracting(SpanView::name)
                .containsExactly("intake", "run", "llm.call");
        assertThat(spans.get(1).parentSpanId()).isEqualTo("span-1");
        assertThat(spans.get(2).parentSpanId()).isEqualTo("span-2");
        assertThat(spans.get(2).attributes()).containsEntry("chatbot.slot", "chat_main");
    }

    @Test
    void 告警命中落库且窗口内不重复停用后不再判定() {
        // 20 条 Turn 中 3 条 FAILED（15% > 10% 阈值）；但失败率分母是全表 turn（无群过滤），
        // 其它 IT 落的数据会抬高分母——只断言命中文案，不锁具体比率数值
        for (int i = 0; i < 20; i++) {
            TurnDO turn = new TurnDO();
            turn.setId(900_000L + i);
            turn.setPlatform("qq");
            turn.setBotId("bot01");
            turn.setGroupId("80001");
            turn.setStatus(i < 3 ? "FAILED" : "SETTLED");
            turn.setErrorCode(i < 3 ? "llm.upstream_error:x" : null);
            turn.setAcceptedAt(LocalDateTime.now(clock).minusMinutes(5));
            turnMapper.insertTurn(turn);
        }
        AlertRuleDO failRateRule = alertRuleMapper.selectByName("Turn 失败率");
        assertThat(failRateRule).isNotNull();

        alertEngine.evaluateOnce();
        List<AlertNotificationDO> afterFirst = notificationsOf(failRateRule.getId());
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).getLevel()).isEqualTo("warn");
        assertThat(afterFirst.get(0).getRuleName()).isEqualTo("Turn 失败率");
        assertThat(afterFirst.get(0).getSummary()).contains("Turn 失败率");
        assertThat(afterFirst.get(0).getReadFlag()).isFalse();

        // 同窗口再跑一轮：抑制，不重复。
        // 时区适配（仅测试环境）：应用侧系统钟走 Asia/Shanghai，而测试机 MySQL 容器
        // 会话时区是 UTC——通知 created_at 存的是 UTC 字面量，比抑制查询传入的
        // now(CST)-suppressMin 字面量小 8 小时，刚落的通知永远落在查询窗口外。
        // 把该行 created_at 字面量拨回 5 分钟前（CST 语义下仍在 30 分钟窗口内），
        // 让抑制判定链（selectRecentByRule 窗口查询 + 维度后缀匹配）在真实 SQL 上走到
        jdbcTemplate.update("UPDATE alert_notification SET created_at = ? WHERE id = ?",
                LocalDateTime.now(clock).minusMinutes(5), afterFirst.get(0).getId());

        alertEngine.evaluateOnce();
        assertThat(notificationsOf(failRateRule.getId())).hasSize(1);

        // 停用后不再判定（builtin 允许停用）
        failRateRule.setEnabled(false);
        alertRuleMapper.updateRule(failRateRule);
        alertEngine.evaluateOnce();
        assertThat(notificationsOf(failRateRule.getId())).hasSize(1);
    }

    private List<AlertNotificationDO> notificationsOf(long ruleId) {
        return notificationMapper.selectRecentByRule(ruleId, LocalDateTime.of(2000, 1, 1, 0, 0));
    }

    private LlmCallLogDO callLog(String platform, String botId, String groupId,
                                 int input, int output, int cached, String cost, boolean replay) {
        LlmCallLogDO row = new LlmCallLogDO();
        row.setPlatform(platform);
        row.setBotId(botId);
        row.setGroupId(groupId);
        row.setRunKind("chat");
        row.setSlotCode("chat_main");
        row.setProviderCode("provider-a");
        row.setModelId("model-x");
        row.setInputTokens(input);
        row.setOutputTokens(output);
        row.setCachedTokens(cached);
        row.setCostAmount(new BigDecimal(cost));
        row.setTtftMs(120);
        row.setTotalMs(500);
        row.setStopReason("stop");
        row.setTraceId(TRACE_ID);
        row.setReplay(replay);
        return row;
    }
}
