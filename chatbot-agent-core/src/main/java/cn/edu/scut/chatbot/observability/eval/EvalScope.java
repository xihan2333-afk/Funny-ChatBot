package cn.edu.scut.chatbot.observability.eval;

import java.time.LocalDateTime;

/**
 * 手动圈选条件（eval-and-flywheel 契约第 2 节 startManual 入参）：时间范围 /
 * 群三元组（空即全部在服务群）/ 每群样本数（空即 observe.eval.dailySamplePerGroup）。
 */
public record EvalScope(LocalDateTime from,
                        LocalDateTime to,
                        String platform,
                        String botId,
                        String groupId,
                        Integer samplePerGroup) {
}
