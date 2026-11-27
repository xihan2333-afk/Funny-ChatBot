package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.observability.alert.AlertNotificationService;
import cn.edu.scut.chatbot.observability.flywheel.ProposalService;
import cn.edu.scut.chatbot.security.gatepolicy.CostBreakerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 管理平台总览与初始化引导接口（overview-and-groups 契约第 1 节）：
 * 一次聚合配置完整性、5 步初始化引导检查单与日常速览（群健康、当日消费与熔断、待办队列）。
 */
@RestController
@RequestMapping("/api/management/overview")
public class OverviewController {

    private static final List<String> CORE_SLOTS = List.of(
            ModelCatalog.SLOT_CHAT_MAIN,
            ModelCatalog.SLOT_EVAL_L1,
            ModelCatalog.SLOT_EMBEDDING
    );

    /**
     * 可选外部统计贡献接口（供接入渠道、盲评、人格演进提案模块在各自域内实现并注入）
     */
    public interface OverviewExtensionStats {
        default Boolean hasEnabledPlatformChannel() {
            return null;
        }

        default Integer countPendingBlindReviews() {
            return null;
        }

        default Integer countPendingPersonaProposals() {
            return null;
        }
    }

    private final AdminAccountService adminAccountService;
    private final ModelCatalog modelCatalog;
    private final EngineCommandFacades.EngineOverviewQueries overviewQueries;
    private final CostBreakerService costBreakerService;
    private final AlertNotificationService alertNotificationService;
    private final ProposalService proposalService;
    private final ObjectProvider<OverviewExtensionStats> extensionStatsProvider;

    public OverviewController(AdminAccountService adminAccountService,
                              ModelCatalog modelCatalog,
                              EngineCommandFacades.EngineOverviewQueries overviewQueries,
                              CostBreakerService costBreakerService,
                              AlertNotificationService alertNotificationService,
                              ProposalService proposalService,
                              ObjectProvider<OverviewExtensionStats> extensionStatsProvider) {
        this.adminAccountService = adminAccountService;
        this.modelCatalog = modelCatalog;
        this.overviewQueries = overviewQueries;
        this.costBreakerService = costBreakerService;
        this.alertNotificationService = alertNotificationService;
        this.proposalService = proposalService;
        this.extensionStatsProvider = extensionStatsProvider;
    }

    @GetMapping
    public OverviewResponse overview() {
        List<ModelCatalog.ConfigGap> gaps = modelCatalog.configGaps();
        List<String> unboundSlots = new ArrayList<>();
        Set<String> missingCredProviders = new LinkedHashSet<>();

        for (ModelCatalog.ConfigGap gap : gaps) {
            if (ModelCatalog.ConfigGap.KIND_SLOT_UNBOUND.equals(gap.kind())) {
                String slot = extractSuffix(gap.detail(), "slot=");
                if (!slot.isBlank()) {
                    unboundSlots.add(slot);
                }
            } else if (ModelCatalog.ConfigGap.KIND_PROVIDER_NO_CREDENTIAL.equals(gap.kind())) {
                String provider = extractSuffix(gap.detail(), "provider=");
                if (!provider.isBlank()) {
                    missingCredProviders.add(provider);
                }
            }
        }

        List<ProviderGap> providerGaps = missingCredProviders.stream()
                .map(code -> new ProviderGap(code, code))
                .toList();

        boolean hasPlatformChannel = resolveHasPlatformChannel();
        boolean missingPlatformChannel = !hasPlatformChannel;
        boolean complete = unboundSlots.isEmpty() && providerGaps.isEmpty() && !missingPlatformChannel;

        ConfigCompleteness configCompleteness = new ConfigCompleteness(
                complete, unboundSlots, providerGaps, missingPlatformChannel);

        boolean passwordChanged = !adminAccountService.mustChangePending();
        boolean coreSlotsReady = isCoreSlotsReady();

        List<EngineViews.GroupHealthView> groupHealth = overviewQueries.queryGroupHealthLast24h();
        boolean anyGroupInService = groupHealth.stream().anyMatch(EngineViews.GroupHealthView::inService);
        boolean anyServiceGroupBoundPersona = groupHealth.stream()
                .anyMatch(g -> g.inService() && g.hasPersonaBound());

        List<OnboardingStep> steps = List.of(
                new OnboardingStep("password_changed", "修改默认口令", passwordChanged, "/auth/change-password"),
                new OnboardingStep("platform_credential", "配置平台接入与凭证", hasPlatformChannel, "/access/channels"),
                new OnboardingStep("model_slots", "绑定核心模型槽位", coreSlotsReady, "/capability/models"),
                new OnboardingStep("enable_group", "将群加入服务名单", anyGroupInService, "/access/groups"),
                new OnboardingStep("bind_persona", "为服务群绑定人格", anyServiceGroupBoundPersona, "/access/groups")
        );
        boolean onboardingCompleted = steps.stream().allMatch(OnboardingStep::done);
        OnboardingGuide onboarding = new OnboardingGuide(onboardingCompleted, steps);

        CostBreakerService.BreakerSnapshot breaker = costBreakerService.aggregateDaily();
        BigDecimal todayCost = breaker != null && breaker.globalUsage() != null
                ? breaker.globalUsage() : BigDecimal.ZERO;
        boolean globalTripped = breaker != null && breaker.globalTripped();
        int trippedGroups = breaker != null && breaker.trippedGroups() != null
                ? (int) breaker.trippedGroups().values().stream().filter(Boolean::booleanValue).count()
                : 0;

        int unreadAlerts = alertNotificationService.list(null, true, 0, 1000).size();
        int pendingImprovementProposals = proposalService.listProposals("pending", 0, 1000).size();
        int pendingBlindReviews = resolvePendingBlindReviews();
        int pendingPersonaProposals = resolvePendingPersonaProposals();

        DailySnapshot dailySnapshot = new DailySnapshot(
                groupHealth,
                todayCost.doubleValue(),
                globalTripped,
                trippedGroups,
                new TodoCounts(pendingBlindReviews, pendingPersonaProposals, pendingImprovementProposals, unreadAlerts)
        );

        return new OverviewResponse(configCompleteness, onboarding, dailySnapshot);
    }

    private boolean isCoreSlotsReady() {
        for (String slotCode : CORE_SLOTS) {
            try {
                var modelRef = modelCatalog.resolveSlot(slotCode);
                if (modelRef == null) {
                    return false;
                }
            } catch (Exception ex) {
                return false;
            }
        }
        return true;
    }

    private boolean resolveHasPlatformChannel() {
        for (OverviewExtensionStats ext : extensionStatsProvider) {
            Boolean v = ext.hasEnabledPlatformChannel();
            if (v != null) {
                return v;
            }
        }
        return false;
    }

    private int resolvePendingBlindReviews() {
        for (OverviewExtensionStats ext : extensionStatsProvider) {
            Integer v = ext.countPendingBlindReviews();
            if (v != null) {
                return v;
            }
        }
        return 0;
    }

    private int resolvePendingPersonaProposals() {
        for (OverviewExtensionStats ext : extensionStatsProvider) {
            Integer v = ext.countPendingPersonaProposals();
            if (v != null) {
                return v;
            }
        }
        return 0;
    }

    private static String extractSuffix(String detail, String marker) {
        if (detail == null) {
            return "";
        }
        int idx = detail.indexOf(marker);
        return idx >= 0 ? detail.substring(idx + marker.length()).trim() : "";
    }

    public record ProviderGap(String providerCode, String displayName) {
    }

    public record ConfigCompleteness(
            boolean complete,
            List<String> unboundSlots,
            List<ProviderGap> providersMissingCredentials,
            boolean missingPlatformChannel
    ) {
    }

    public record OnboardingStep(String key, String title, boolean done, String route) {
    }

    public record OnboardingGuide(boolean completed, List<OnboardingStep> steps) {
    }

    public record TodoCounts(
            int pendingBlindReviews,
            int pendingPersonaProposals,
            int pendingImprovementProposals,
            int unreadAlerts
    ) {
    }

    public record DailySnapshot(
            List<EngineViews.GroupHealthView> groupHealth,
            double todayCostUsd,
            boolean globalBreakerTripped,
            int trippedGroupCount,
            TodoCounts todo
    ) {
    }

    public record OverviewResponse(
            ConfigCompleteness configCompleteness,
            OnboardingGuide onboarding,
            DailySnapshot dailySnapshot
    ) {
    }
}
