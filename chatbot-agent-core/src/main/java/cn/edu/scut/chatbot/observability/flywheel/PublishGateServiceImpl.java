package cn.edu.scut.chatbot.observability.flywheel;

import cn.edu.scut.chatbot.observability.eval.EvalCaseDO;
import cn.edu.scut.chatbot.observability.eval.EvalCaseMapper;
import cn.edu.scut.chatbot.observability.eval.RubricItem;
import cn.edu.scut.chatbot.observability.replay.ReplayReport;
import cn.edu.scut.chatbot.observability.replay.ReplayService;
import cn.edu.scut.chatbot.prompt.ContentVersioningService;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布门禁实现（eval-and-flywheel 契约第 6 节、T059）：挂
 * {@link ContentVersioningService} 的 Prompt 片段发布流程——发布确认前对必过子集
 * （status=ready 且 rubric 含红线项的题全量）触发 dry-run 回测批次；判定口径：
 * 红线题任何一次不过 → red；体验类（非红线题）通过率低于阈值 → yellow；
 * 全过 → green；必过子集为空 → green 附「子集为空」提示（防空集假绿）。
 *
 * <p>越过红牌：overrideReason 必填 → security_event(adminOperate 含理由) →
 * 发布生效下个 Run。异步纪律：回测在后台命名虚拟线程执行（发布单显示 running），
 * 期间其它管理操作不阻塞。门禁态进程内持有（发布是低频管理动作，重启后重新
 * trigger 即可——不落表）。
 */
@Service
public class PublishGateServiceImpl implements PublishGateService {

    private static final Logger log = LoggerFactory.getLogger(PublishGateServiceImpl.class);

    /**
     * 体验类通过率黄牌线（非红线题 Pass@k 低于此值 → yellow）
     */
    static final double YELLOW_PASS_RATE = 0.8;

    private final EvalCaseMapper caseMapper;
    private final ReplayService replayService;
    private final ContentVersioningService contentVersioningService;
    private final SecurityEventRecorder securityEventRecorder;

    private final Map<String, GateStatus> gates = new ConcurrentHashMap<>();

    public PublishGateServiceImpl(EvalCaseMapper caseMapper,
                                  ReplayService replayService,
                                  ContentVersioningService contentVersioningService,
                                  SecurityEventRecorder securityEventRecorder) {
        this.caseMapper = caseMapper;
        this.replayService = replayService;
        this.contentVersioningService = contentVersioningService;
        this.securityEventRecorder = securityEventRecorder;
    }

    @Override
    public GateStatus trigger(String intention) {
        requireIntention(intention);
        List<EvalCaseDO> mustPass = mustPassSubset();
        GateStatus running = new GateStatus("running", mustPass.isEmpty(), mustPass.size(),
                0, List.of());
        gates.put(intention, running);
        Thread.ofVirtual().name("publish-gate-" + intention).start(() -> {
            try {
                gates.put(intention, evaluate(mustPass));
            } catch (Exception e) {
                // 门禁执行异常按红牌处理（宁可拦住不可放行）并留原因
                log.error("发布门禁回测异常（intention={}）", intention, e);
                gates.put(intention, new GateStatus("red", mustPass.isEmpty(), mustPass.size(),
                        0, List.of(new GateStatus.CaseVerdict(0, true, true, 0.0))));
            }
        });
        return running;
    }

    @Override
    public GateStatus status(String intention) {
        requireIntention(intention);
        GateStatus status = gates.get(intention);
        if (status == null) {
            throw new IllegalArgumentException("发布意图无门禁记录（先 trigger）：" + intention);
        }
        return status;
    }

    @Override
    public void publish(String intention, String overrideReason) {
        requireIntention(intention);
        GateStatus status = status(intention);
        if ("running".equals(status.status())) {
            throw new IllegalArgumentException("回测进行中，发布动作等待门禁完成（intention=" + intention + "）");
        }
        if ("red".equals(status.status()) && (overrideReason == null || overrideReason.isBlank())) {
            throw new IllegalArgumentException("红牌未处理：越过发布必须填写 overrideReason（动作与理由将记审计）");
        }
        if ("red".equals(status.status())) {
            // 越过红牌：动作连同理由记 security_event(adminOperate)——审计先于发布
            securityEventRecorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(),
                    Map.of("action", "publishGateOverride",
                            "intention", intention,
                            "gateStatus", status.status(),
                            "overrideReason", overrideReason),
                    null);
        }
        contentVersioningService.publishFragment(intention);
    }

    /**
     * 必过子集：ready 状态且 rubric 含红线检查项的题（第一版以红线题全量为口径——
     * 「涉及落点相关题」的落点映射随提案落地关系表扩展）
     */
    private List<EvalCaseDO> mustPassSubset() {
        List<EvalCaseDO> subset = new ArrayList<>();
        for (EvalCaseDO caze : caseMapper.selectReadyByTypes(
                List.of(EvalCaseDO.TYPE_SEED, EvalCaseDO.TYPE_GOLDEN, EvalCaseDO.TYPE_FAULT), 200)) {
            if (RubricItem.parseRubric(caze.getRubric()).stream().anyMatch(RubricItem::redLine)) {
                subset.add(caze);
            }
        }
        return subset;
    }

    /**
     * 逐题 dry-run 回测 → 汇总判定（red 一票否决 → yellow 体验线 → green）
     */
    private GateStatus evaluate(List<EvalCaseDO> mustPass) {
        if (mustPass.isEmpty()) {
            return new GateStatus("green", true, 0, 0, List.of());
        }
        List<GateStatus.CaseVerdict> verdicts = new ArrayList<>();
        int passedCases = 0;
        for (EvalCaseDO caze : mustPass) {
            ReplayReport report = replayService.run(caze, 1);   // 门禁单次执行（Pass@k 聚合归手动回测）
            boolean anyFailed = report.details().stream()
                    .anyMatch(d -> !"pass".equals(d.decision()));
            verdicts.add(new GateStatus.CaseVerdict(caze.getId(), true, anyFailed, report.passRate()));
            if (!anyFailed) {
                passedCases++;
            }
        }
        boolean anyRed = verdicts.stream().anyMatch(GateStatus.CaseVerdict::anyFailed);
        String status = anyRed ? "red"
                : verdicts.stream().anyMatch(v -> v.passRate() < YELLOW_PASS_RATE) ? "yellow"
                : "green";
        return new GateStatus(status, false, mustPass.size(), passedCases, List.copyOf(verdicts));
    }

    private static void requireIntention(String intention) {
        if (intention == null || intention.isBlank()) {
            throw new IllegalArgumentException("发布意图（intention）不能为空");
        }
    }
}
