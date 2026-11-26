package cn.edu.scut.chatbot.observability.eval;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 归因与一致率（eval-and-flywheel 契约、FR-010、T047）：
 *
 * <p>归因三分支——agent（Agent 真有问题，进提案管线）/ caseMiss（评测集没覆盖，
 * 样本补进评测集）/ rubricError（评分标准判错，修订 Rubric 留痕）。只做人工标注的
 * 回填与校验：三分支的后续动作分别由提案管线（US6）与评测集管理（promoteFromResult
 * + reviseRubric）承担——本服务不越权触发。
 *
 * <p>人机一致率——盲评样本上管理员打分（judge_model=null 对照行）与 eval_judge
 * 打分（机器行）按五组指标分别统计：配对键 = batchId + turnId（线上抽样）或
 * batchId + caseId（评测集样本）。输出各维度配对数、平均绝对差与判定一致率，
 * 可按时间窗查询（趋势由调用方按窗分段取数）。
 */
@Service
public class EvalAttributionService {

    /**
     * 一致判定的分数容忍带宽（分）：|机器分 − 人工分| ≤ 带宽视为一致——
     * 五组指标各自 0~100，10 分是「同一档」的口径
     */
    public static final int AGREEMENT_TOLERANCE = 10;

    private final EvalResultMapper resultMapper;

    public EvalAttributionService(EvalResultMapper resultMapper) {
        this.resultMapper = resultMapper;
    }

    /**
     * 归因标注：三分支字典校验 + 无效结果拒绝（不可变更已归因行——留痕纪律，
     * 需改判走新一轮评估）
     */
    public void attribute(long resultId, String attribution) {
        if (!EvalResultDO.ATTRIBUTION_AGENT.equals(attribution)
                && !EvalResultDO.ATTRIBUTION_CASE_MISS.equals(attribution)
                && !EvalResultDO.ATTRIBUTION_RUBRIC_ERROR.equals(attribution)) {
            throw new IllegalArgumentException("归因必须是 agent / caseMiss / rubricError：" + attribution);
        }
        EvalResultDO result = resultMapper.selectById(resultId);
        if (result == null) {
            throw new IllegalArgumentException("评估结果不存在：" + resultId);
        }
        if (result.getAttribution() != null) {
            throw new IllegalArgumentException("结果已归因为 " + result.getAttribution() + "（留痕不改判）");
        }
        resultMapper.updateAttribution(resultId, attribution);
    }

    /**
     * 人机一致率（时间窗 [from, to)）
     */
    public AgreementReport agreement(LocalDateTime from, LocalDateTime to) {
        List<EvalResultDO> blinds = resultMapper.selectBlindBetween(from, to);
        Map<String, EvalResultDO> humans = new HashMap<>();
        for (EvalResultDO row : blinds) {
            if (row.getJudgeModel() == null) {
                humans.put(pairKey(row), row);
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (EvalResultDO row : blinds) {
            if (row.getJudgeModel() != null) {
                EvalResultDO human = humans.get(pairKey(row));
                if (human != null) {
                    pairs.add(new Pair(row, human));
                }
            }
        }
        int decisionAgreed = 0;
        for (Pair pair : pairs) {
            if (pair.machine().getDecision() != null
                    && pair.machine().getDecision().equals(pair.human().getDecision())) {
                decisionAgreed++;
            }
        }
        List<DimensionAgreement> dimensions = new ArrayList<>();
        for (String dimension : List.of("respond", "process", "form", "cost", "safety")) {
            dimensions.add(dimensionAgreement(pairs, dimension));
        }
        return new AgreementReport(pairs.size(), decisionAgreed, dimensions);
    }

    private DimensionAgreement dimensionAgreement(List<Pair> pairs, String dimension) {
        int count = 0;
        long diffSum = 0;
        int agreed = 0;
        for (Pair pair : pairs) {
            EvalScore machine = EvalScore.parse(pair.machine().getScore());
            EvalScore human = EvalScore.parse(pair.human().getScore());
            Integer machineScore = machine == null ? null : machine.byDimension(dimension);
            Integer humanScore = human == null ? null : human.byDimension(dimension);
            if (machineScore == null || humanScore == null) {
                continue;
            }
            count++;
            int diff = Math.abs(machineScore - humanScore);
            diffSum += diff;
            if (diff <= AGREEMENT_TOLERANCE) {
                agreed++;
            }
        }
        return new DimensionAgreement(dimension, count,
                count == 0 ? null : (double) diffSum / count,
                count == 0 ? null : (double) agreed / count);
    }

    /**
     * 配对键：同批同 Turn（线上样本）或同批同题（评测集样本）
     */
    private String pairKey(EvalResultDO row) {
        return row.getBatchId() + ":" + (row.getTurnId() != null ? "t" + row.getTurnId()
                : "c" + row.getCaseId());
    }

    /**
     * 一致率报表：配对数、判定一致数、五组维度各自统计
     */
    public record AgreementReport(int pairs,
                                  int decisionAgreed,
                                  List<DimensionAgreement> dimensions) {
    }

    /**
     * 单维度统计：配对数（双方都有该维度分的对数）、平均绝对差、带宽内一致率
     */
    public record DimensionAgreement(String dimension,
                                     int count,
                                     Double meanAbsoluteDiff,
                                     Double agreementRate) {
    }

    private record Pair(EvalResultDO machine, EvalResultDO human) {
    }
}
