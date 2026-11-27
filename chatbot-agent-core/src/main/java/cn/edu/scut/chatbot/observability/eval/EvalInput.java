package cn.edu.scut.chatbot.observability.eval;

import java.util.List;

/**
 * 评估器统一输入（eval-and-flywheel 契约第 1 节）：样本 + rubric 检查项 +
 * 轨迹视图（可选，Agent 判分消费）+ 代码判据事实（Code 判分消费）。
 */
public record EvalInput(EvalSample sample,
                        List<RubricItem> rubric,
                        String trajectoryView,
                        SampleFacts facts) {
}
