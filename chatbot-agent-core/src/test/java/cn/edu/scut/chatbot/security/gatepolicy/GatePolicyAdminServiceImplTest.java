// 守护纪律：安全闸策略管理门面参数校验、黑名单新增触发存量 Task 重验证、converted 来源词条与阈值键白名单校验（security-gate 契约第 3、4 节，T078/T079）
package cn.edu.scut.chatbot.security.gatepolicy;

import cn.edu.scut.chatbot.bootstrap.SystemParamDO;
import cn.edu.scut.chatbot.bootstrap.SystemParamMapper;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatePolicyAdminServiceImplTest {

    private BlacklistMapper blacklistMapper;
    private ContentFilterWordMapper wordMapper;
    private SystemParamMapper paramMapper;
    private SystemParamService systemParams;
    private SecurityEventRecorder recorder;
    private ApplicationEventPublisher eventPublisher;
    private TaskCoordinator taskCoordinator;
    private GroupMapper groupMapper;
    private GatePolicyAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        blacklistMapper = mock(BlacklistMapper.class);
        wordMapper = mock(ContentFilterWordMapper.class);
        paramMapper = mock(SystemParamMapper.class);
        systemParams = mock(SystemParamService.class);
        recorder = mock(SecurityEventRecorder.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        taskCoordinator = mock(TaskCoordinator.class);
        groupMapper = mock(GroupMapper.class);

        doAnswer(inv -> {
            BlacklistDO row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(101L);
            }
            return 1;
        }).when(blacklistMapper).insert(any(BlacklistDO.class));

        doAnswer(inv -> {
            ContentFilterWordDO row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(202L);
            }
            return 1;
        }).when(wordMapper).insert(any(ContentFilterWordDO.class));

        service = new GatePolicyAdminServiceImpl(
                blacklistMapper, wordMapper, paramMapper, systemParams, recorder, eventPublisher);
        service.setTaskCoordinator(taskCoordinator);
        service.setGroupMapper(groupMapper);
    }

    @Test
    void 新增黑名单参数非法时拒绝并零落库零审计() {
        assertThrows(IllegalArgumentException.class, () -> service.addBlacklist(null, "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.addBlacklist(
                new GatePolicyAdminService.BlacklistCreateRequest("group", "qq", "b1", "g1", "  ", "原因"),
                "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.addBlacklist(
                new GatePolicyAdminService.BlacklistCreateRequest("tenant", "qq", "b1", "g1", "u1", "原因"),
                "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.addBlacklist(
                new GatePolicyAdminService.BlacklistCreateRequest("group", "qq", "b1", null, "u1", "原因"),
                "ops"));

        verify(blacklistMapper, never()).insert(any());
        verify(recorder, never()).record(any(), any(), any(), any());
        verify(taskCoordinator, never()).revalidateMandates(any(), any(), any());
    }

    @Test
    void 新增群级黑名单触发本群存量Task重验证并发布刷新事件与审计() {
        long id = service.addBlacklist(new GatePolicyAdminService.BlacklistCreateRequest(
                null, "qq", "b1", "g1", " u-bad ", "刷屏"), "sec-admin");

        assertEquals(101L, id);
        verify(blacklistMapper).insert(argThat(row ->
                BlacklistDO.SCOPE_GROUP.equals(row.getScope())
                        && "qq".equals(row.getPlatform())
                        && "b1".equals(row.getBotId())
                        && "g1".equals(row.getGroupId())
                        && "u-bad".equals(row.getUserId())));
        verify(eventPublisher).publishEvent(any(ToolRegistry.ConfigRefreshEvent.class));
        verify(recorder).record(eq(SecurityEventKind.ADMIN_OPERATE),
                eq(SecurityDims.ofUser("qq", "b1", "g1", "u-bad")),
                argThat(detail -> "blacklistAdd".equals(detail.get("action"))
                        && "sec-admin".equals(detail.get("operator"))),
                isNull());
        verify(taskCoordinator).revalidateMandates("qq", "b1", "g1");
        verify(groupMapper, never()).selectAllInService();
    }

    @Test
    void 新增全局黑名单清空三元组并遍历在服群触发存量Task重验证() {
        GroupDO g1 = new GroupDO();
        g1.setPlatform("qq");
        g1.setBotId("b1");
        g1.setGroupId("g-active-1");
        GroupDO g2 = new GroupDO();
        g2.setPlatform("qq");
        g2.setBotId("b2");
        g2.setGroupId("g-active-2");
        when(groupMapper.selectAllInService()).thenReturn(List.of(g1, g2));

        long id = service.addBlacklist(new GatePolicyAdminService.BlacklistCreateRequest(
                BlacklistDO.SCOPE_GLOBAL, "qq", "b1", "g1", "u-global", "全平台封禁"), "root");

        assertEquals(101L, id);
        verify(blacklistMapper).insert(argThat(row ->
                BlacklistDO.SCOPE_GLOBAL.equals(row.getScope())
                        && row.getPlatform() == null
                        && row.getBotId() == null
                        && row.getGroupId() == null
                        && "u-global".equals(row.getUserId())));
        verify(taskCoordinator).revalidateMandates("qq", "b1", "g-active-1");
        verify(taskCoordinator).revalidateMandates("qq", "b2", "g-active-2");
    }

    @Test
    void 新增converted来源过滤词落库并拒绝非法level与source() {
        long id = service.addFilterWord(new GatePolicyAdminService.FilterWordCreateRequest(
                        " 违禁引导词 ", ContentFilterWordDO.LEVEL_BLOCK, ContentFilterWordDO.SOURCE_CONVERTED),
                "reviewer");
        assertEquals(202L, id);

        verify(wordMapper).insert(argThat(row ->
                "违禁引导词".equals(row.getWord())
                        && ContentFilterWordDO.LEVEL_BLOCK.equals(row.getLevel())
                        && ContentFilterWordDO.SOURCE_CONVERTED.equals(row.getSource())
                        && Integer.valueOf(1).equals(row.getEnabled())));
        verify(eventPublisher).publishEvent(any(ToolRegistry.ConfigRefreshEvent.class));
        verify(recorder).record(eq(SecurityEventKind.ADMIN_OPERATE),
                eq(SecurityDims.none()),
                argThat(detail -> "filterWordAdd".equals(detail.get("action"))
                        && ContentFilterWordDO.SOURCE_CONVERTED.equals(detail.get("source"))
                        && "reviewer".equals(detail.get("operator"))),
                isNull());

        assertThrows(IllegalArgumentException.class, () -> service.addFilterWord(
                new GatePolicyAdminService.FilterWordCreateRequest("  ", "block", "manual"), "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.addFilterWord(
                new GatePolicyAdminService.FilterWordCreateRequest("词条", "warn", "manual"), "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.addFilterWord(
                new GatePolicyAdminService.FilterWordCreateRequest("词条", "block", "unknown"), "ops"));
    }

    @Test
    void 更新策略阈值拒绝非法键与空值且白名单键正常刷新与审计() {
        assertThrows(IllegalArgumentException.class, () -> service.updateThresholds(
                Map.of("security.unknown.threshold", "10"), "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.updateThresholds(
                Map.of(OutboundScreenServiceImpl.PARAM_FINGERPRINT_THRESHOLD, " "), "ops"));
        verify(paramMapper, never()).upsertParam(any());

        service.updateThresholds(Map.of(
                OutboundScreenServiceImpl.PARAM_FINGERPRINT_THRESHOLD, " 25 ",
                CostBreakerServiceImpl.PARAM_GLOBAL_DAILY_LIMIT, "80.00"
        ), "ops-lead");

        ArgumentCaptor<SystemParamDO> captor = ArgumentCaptor.forClass(SystemParamDO.class);
        verify(paramMapper, times(2)).upsertParam(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(p ->
                OutboundScreenServiceImpl.PARAM_FINGERPRINT_THRESHOLD.equals(p.getParamKey())
                        && "25".equals(p.getParamValue())));
        verify(systemParams).refresh();
        verify(eventPublisher).publishEvent(any(ToolRegistry.ConfigRefreshEvent.class));
        verify(recorder).record(eq(SecurityEventKind.ADMIN_OPERATE),
                eq(SecurityDims.none()),
                argThat(detail -> "thresholdsUpdate".equals(detail.get("action"))
                        && "ops-lead".equals(detail.get("operator"))),
                isNull());
    }

    @Test
    void 更新域名黑名单规范化去重并在JSON损坏时降级为空列表() {
        service.updateDomainBlacklist(List.of(" Evil.COM ", "evil.com", "  ", "bad.test"), "ops");

        verify(paramMapper).upsertParam(argThat(param ->
                GatePolicyServiceImpl.PARAM_DOMAIN_BLACKLIST.equals(param.getParamKey())
                        && "[\"evil.com\",\"bad.test\"]".equals(param.getParamValue())));
        verify(systemParams).refresh();

        when(systemParams.getString(GatePolicyServiceImpl.PARAM_DOMAIN_BLACKLIST, "[]"))
                .thenReturn("{not-a-json-array");
        assertTrue(service.getDomainBlacklist().isEmpty());
    }
}
