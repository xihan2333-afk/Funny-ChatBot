package cn.edu.scut.chatbot.memory.profile;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.context.window.TokenEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 态度行渲染（contracts/social-and-supply.md §5 态度行注入契约）：
 * 本批触发器与锚定区涉及的发言人中态度显著者（|有效好感| ≥ 30 或熟悉度 ≥ 50）
 * 各注一行「称呼 + 档位措辞 + 基调短语」；上限 5 行、总计 ≤150 token；
 * 无显著态度的人一个字不注（零注入负向断言锚点）。
 *
 * <p>称呼回退链 call_name → platform_name → userId。落点是现注区
 * （GenerationRunner.injectCurrentNotes）不是群上下文层——逐人态度是
 * 「这次跟谁说话」的函数，每次调用都在变。
 */
@Component
public class AttitudeNoteRenderer {

    public static final int MAX_LINES = 5;
    public static final int TOKEN_BUDGET = 150;

    private final AffinityService affinityService;
    private final SystemParamService systemParams;

    public AttitudeNoteRenderer(AffinityService affinityService,
                                SystemParamService systemParams) {
        this.affinityService = affinityService;
        this.systemParams = systemParams;
    }

    /**
     * 依序渲染显著者的态度行（orderedUserIds 按触发相关度排序——调用方给序，先到先注）
     */
    public List<String> render(GroupKey group, List<String> orderedUserIds) {
        AffinityParams params = AffinityParams.load(systemParams);
        List<String> lines = new ArrayList<>();
        int usedTokens = 0;
        for (String userId : orderedUserIds) {
            if (lines.size() >= MAX_LINES) {
                break;
            }
            AffinityService.AttitudeView view = affinityService.view(group, userId);
            if (!significant(view, params)) {
                continue;
            }
            String line = "对「" + displayName(view, userId) + "」：" + view.tier().label()
                    + (view.interactDigest() == null || view.interactDigest().isBlank()
                    ? "" : "，" + view.interactDigest());
            int cost = TokenEstimator.estimate(line);
            if (usedTokens + cost > TOKEN_BUDGET) {
                break;   // 无界供给纪律：超预算即止
            }
            lines.add(line);
            usedTokens += cost;
        }
        return lines;
    }

    /**
     * 显著者判定（|有效好感| ≥ significantAffinity 或熟悉度 ≥ significantFamiliarity）
     */
    private boolean significant(AffinityService.AttitudeView view, AffinityParams params) {
        return Math.abs(view.effectiveAffinity()) >= params.significantAffinity()
                || view.familiarity() >= params.significantFamiliarity();
    }

    /**
     * 称呼回退链：call_name → platform_name → userId
     */
    public static String displayName(AffinityService.AttitudeView view, String userId) {
        if (view.callName() != null && !view.callName().isBlank()) {
            return view.callName();
        }
        if (view.platformName() != null && !view.platformName().isBlank()) {
            return view.platformName();
        }
        return userId;
    }
}
