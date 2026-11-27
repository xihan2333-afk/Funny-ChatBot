package cn.edu.scut.chatbot.observability.flywheel;

import java.util.List;

/**
 * 发布门禁门面（eval-and-flywheel 契约第 6 节、R12）：挂既有内容发布流程——
 * 发布确认前对必过子集（全部红线题）触发 dry-run 回测，红黄绿判定挂发布单；
 * 越过红牌需 overrideReason 必填并记 security_event(adminOperate) 审计。
 */
public interface PublishGateService {

    /**
     * 触发回测（异步：发布单显示 running，不阻塞其它管理操作）。intention 是
     * 发布意图标识（本版 = promptFragment 的 fragmentKey）。
     */
    GateStatus trigger(String intention);

    /**
     * 门禁状态查询
     */
    GateStatus status(String intention);

    /**
     * 发布（红牌时 overrideReason 必填 → 审计 → 发布生效下个 Run）
     */
    void publish(String intention, String overrideReason);

    /**
     * 门禁状态：running（回测中）/ green / yellow / red；emptySubset=true 表示
     * 必过子集为空（green 附提示防空集假绿）；detail 载各题判定明细。
     */
    record GateStatus(String status,
                      boolean emptySubset,
                      int totalCases,
                      int passedCases,
                      List<CaseVerdict> verdicts) {

        public record CaseVerdict(long caseId, boolean redLine, boolean anyFailed, double passRate) {
        }
    }
}
