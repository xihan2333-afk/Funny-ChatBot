package cn.edu.scut.chatbot.observability.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 评估调度（T045）：每日凌晨触发每日抽样批次。调度周期是部署面参数
 * （observe.eval.daily-cron，application.yml / 环境变量覆盖），抽样条数与盲评
 * 比例才是数据面参数（system_param 热变更）。
 *
 * <p>异常纪律：调度入口吞异常记 ERROR——调度线程不允许因单次失败退出。
 */
@Component
public class EvalScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvalScheduler.class);

    private final EvalTaskService evalTaskService;

    public EvalScheduler(EvalTaskService evalTaskService) {
        this.evalTaskService = evalTaskService;
    }

    @Scheduled(cron = "${observe.eval.daily-cron:0 0 4 * * *}")
    public void daily() {
        try {
            EvalBatchView batch = evalTaskService.startDaily();
            if (batch != null) {
                log.info("每日评估批次已启动（batchId={}）", batch.id());
            }
        } catch (Exception e) {
            log.error("每日评估批次启动失败", e);
        }
    }
}
