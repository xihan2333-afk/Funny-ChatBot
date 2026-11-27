// 守护纪律（US10 集成验收场景 1~5）：beforeToolCall 拒绝落 gateBlocked、
// 沙箱输出含 /var/run/docker.sock 落 sandboxAbnormal 并命中严重级告警、
// 近 1 小时安全事件数为 7 日基线 4 倍命中拦截率突增告警、按 traceId 关联审计事件还原现场。
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.observability.alert.AlertEngine;
import cn.edu.scut.chatbot.security.audit.*;
import cn.edu.scut.chatbot.tool.ToolCallGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全审计、Span 安全标记与沙箱逃逸审计 IT（spec US10 验收场景 1~5）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityAuditIT extends AbstractIntegrationTest {

    private static final String PLATFORM = "qq";
    private static final String BOT = "bot-us10";
    private static final String GROUP = "grp-us10-audit";

    @Autowired
    private ToolCallGuard toolCallGuard;

    @Autowired
    private SandboxAuditService sandboxAuditService;

    @Autowired
    private SecurityEventRecorder securityEventRecorder;

    @Autowired
    private SecurityEventMapper securityEventMapper;

    @Autowired
    private AlertEngine alertEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setupGroup() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO `group` (platform, bot_id, group_id, group_name, lifecycle, in_service)
                VALUES (?, ?, ?, 'US10 安全审计测试群', 'ACTIVE', 1)
                """, PLATFORM, BOT, GROUP);
        jdbcTemplate.update("""
                INSERT INTO group_config (platform, bot_id, group_id, user_blacklist,
                                          allowed_member_task_categories, allowed_autonomous_categories, disabled_tools)
                VALUES (?, ?, ?, '[]', '[]', '[]', '["fetchWeb"]')
                ON DUPLICATE KEY UPDATE disabled_tools = '["fetchWeb"]'
                """, PLATFORM, BOT, GROUP);
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM security_event WHERE group_id = ?", GROUP);
        jdbcTemplate.update("DELETE FROM alert_notification WHERE rule_name IN ('沙箱异常', '拦截率突增')");
    }

    @Test
    void beforeToolCall拒绝落gateBlocked且可按traceId还原() {
        String traceId = "abcdef1234567890abcdef1234567890";
        org.slf4j.MDC.put("traceId", traceId);
        try {
            LoopHooks.BeforeToolCallHook hook = toolCallGuard.forRun(PLATFORM, BOT, GROUP);
            LoopHooks.BeforeToolCallHook.Interception intercepted = hook.intercept(
                    new ToolInvocation("{\"url\":\"https://example.com\"}",
                            PLATFORM, BOT, GROUP, RunConfig.ExecutionForm.TURN, null, null, "fetchWeb"));
            assertThat(intercepted).isNotNull();
            assertThat(intercepted.overrideResult().isError()).isTrue();
        } finally {
            org.slf4j.MDC.remove("traceId");
        }

        List<SecurityEventDO> events = securityEventMapper.selectByTraceId(traceId);
        assertThat(events).anyMatch(e -> SecurityEventKind.GATE_BLOCKED.code().equals(e.getEventKind())
                && e.getDetail().contains("fetchWeb"));
    }

    @Test
    void 沙箱输出含dockerSock落sandboxAbnormal并触发严重级告警() {
        String traceId = "11112222333344445555666677778888";
        SandboxAuditService.AuditResult res = sandboxAuditService.audit(
                new SandboxAuditService.SandboxExecutionView(
                        0, false, "crw-rw---- /var/run/docker.sock", "", 88, null),
                SecurityDims.ofGroup(PLATFORM, BOT, GROUP), traceId);
        assertThat(res.abnormal()).isTrue();
        assertThat(res.matchedFeatures()).isNotEmpty();

        // 拨行到应用时区当前时间（规避 MySQL 容器 UTC vs CST 8 小时差）
        jdbcTemplate.update(
                "UPDATE security_event SET created_at = ? WHERE trace_id = ?",
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")), traceId);

        alertEngine.evaluateOnce();

        Integer alerts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_notification WHERE rule_name = '沙箱异常' AND level = 'critical'",
                Integer.class);
        assertThat(alerts).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 近1小时安全事件突增超7日基线3倍触发拦截率突增告警() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        // 写 16 条过去 1~6 天内的基线事件（7 日约 168 小时，均值 < 0.2/h）+ 20 条近 1 小时事件
        for (int i = 0; i < 20; i++) {
            securityEventRecorder.record(SecurityEventKind.GATE_BLOCKED,
                    SecurityDims.ofGroup(PLATFORM, BOT, GROUP),
                    Map.of("burstMarker", "us10-burst", "idx", i), null);
        }
        jdbcTemplate.update(
                "UPDATE security_event SET created_at = ? WHERE group_id = ? AND detail LIKE '%us10-burst%'",
                now.minusMinutes(5), GROUP);

        alertEngine.evaluateOnce();

        Integer burstAlerts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_notification WHERE rule_name = '拦截率突增'",
                Integer.class);
        assertThat(burstAlerts).isGreaterThanOrEqualTo(1);
    }
}
