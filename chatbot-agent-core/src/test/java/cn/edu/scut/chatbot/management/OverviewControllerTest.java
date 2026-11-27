package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.loop.ModelCapabilities;
import cn.edu.scut.chatbot.loop.ModelPrices;
import cn.edu.scut.chatbot.loop.ModelRef;
import cn.edu.scut.chatbot.observability.alert.AlertNotificationService;
import cn.edu.scut.chatbot.observability.flywheel.ProposalService;
import cn.edu.scut.chatbot.security.gatepolicy.CostBreakerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverviewControllerTest {

    @Test
    void computesCompletenessAndFiveOnboardingStepsAccurately() {
        AdminAccountService accountService = mock(AdminAccountService.class);
        when(accountService.mustChangePending()).thenReturn(false);

        ModelCatalog catalog = mock(ModelCatalog.class);
        when(catalog.configGaps()).thenReturn(List.of(
                new ModelCatalog.ConfigGap(ModelCatalog.ConfigGap.KIND_SLOT_UNBOUND, "槽位未绑定 slot=image_gen")
        ));
        ModelRef dummyRef = new ModelRef("openai", "gpt-4o", "openai", "https://api.openai.com",
                new ModelCapabilities(false, true, 128000, 4096),
                new ModelPrices(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO),
                "{}");
        when(catalog.resolveSlot(ModelCatalog.SLOT_CHAT_MAIN)).thenReturn(dummyRef);
        when(catalog.resolveSlot(ModelCatalog.SLOT_EVAL_L1)).thenReturn(dummyRef);
        when(catalog.resolveSlot(ModelCatalog.SLOT_EMBEDDING)).thenThrow(new ModelCatalog.SlotResolutionException("unbound"));

        EngineCommandFacades.EngineOverviewQueries overviewQueries = () -> List.of(
                new EngineViews.GroupHealthView("onebot-v11", "10001", "20001", "测试群",
                        "ACTIVE", true, true, 20, 0.95)
        );

        CostBreakerService breakerService = mock(CostBreakerService.class);
        when(breakerService.aggregateDaily()).thenReturn(new CostBreakerService.BreakerSnapshot(
                "2026-11-27", new BigDecimal("1.25"), new BigDecimal("10.00"),
                false, Map.of(), new BigDecimal("5.00"), Map.of()
        ));

        AlertNotificationService alertService = mock(AlertNotificationService.class);
        when(alertService.list(null, true, 0, 1000)).thenReturn(List.of());

        ProposalService proposalService = mock(ProposalService.class);
        when(proposalService.listProposals("pending", 0, 1000)).thenReturn(List.of());

        OverviewController.OverviewExtensionStats ext = new OverviewController.OverviewExtensionStats() {
            @Override
            public Boolean hasEnabledPlatformChannel() {
                return true;
            }

            @Override
            public Integer countPendingBlindReviews() {
                return 3;
            }

            @Override
            public Integer countPendingPersonaProposals() {
                return 2;
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<OverviewController.OverviewExtensionStats> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenAnswer(inv -> List.of(ext).iterator());

        OverviewController controller = new OverviewController(
                accountService, catalog, overviewQueries, breakerService, alertService, proposalService, provider);

        OverviewController.OverviewResponse resp = controller.overview();

        assertThat(resp.configCompleteness().complete()).isFalse();
        assertThat(resp.configCompleteness().unboundSlots()).containsExactly("image_gen");
        assertThat(resp.configCompleteness().missingPlatformChannel()).isFalse();

        assertThat(resp.onboarding().completed()).isFalse();
        assertThat(resp.onboarding().steps())
                .extracting(OverviewController.OnboardingStep::key, OverviewController.OnboardingStep::done)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("password_changed", true),
                        org.assertj.core.groups.Tuple.tuple("platform_credential", true),
                        org.assertj.core.groups.Tuple.tuple("model_slots", false),
                        org.assertj.core.groups.Tuple.tuple("enable_group", true),
                        org.assertj.core.groups.Tuple.tuple("bind_persona", true)
                );

        assertThat(resp.dailySnapshot().todayCostUsd()).isEqualTo(1.25);
        assertThat(resp.dailySnapshot().todo().pendingBlindReviews()).isEqualTo(3);
        assertThat(resp.dailySnapshot().todo().pendingPersonaProposals()).isEqualTo(2);
    }
}
