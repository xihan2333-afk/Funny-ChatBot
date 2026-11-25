package cn.edu.scut.chatbot.llm.slot;

import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.loop.ModelRef;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调用计量落账（data-model 第 10/11 节、research R8）：有界队列 + 命名虚拟线程消费者
 * 批量 INSERT——落账异步不阻塞调用主线程；计量丢一条不伤主流程（队列满丢弃计数告警）。
 *
 * <p>成本折算：(input−cached)×输入单价 + cached×缓存单价 + output×输出单价，每百万
 * token 口径、DECIMAL(12,6) 六位小数（命中部分不重复计输入价——cached 是 input 子集）；
 * 单价任一缺失 → cost 落 null 并告警。失败调用照记（error_code 携带、用量与延迟尽力保留）。
 */
@Component
public class CostRecorder {

    private static final Logger log = LoggerFactory.getLogger(CostRecorder.class);

    /**
     * 批量落库上限（每批 ≤100 行或 500ms 窗口——data-model 第 11 节队列纪律）
     */
    static final int BATCH_LIMIT = 100;

    static final long FLUSH_WINDOW_MILLIS = 500;

    private final LlmCallLogMapper llmCallLogMapper;

    private final ModelCatalog catalog;

    /**
     * 有界队列（满则丢弃——计量不是关键路径）
     */
    private final BlockingQueue<LlmCallLogDO> queue = new ArrayBlockingQueue<>(4096);

    private final AtomicLong droppedCount = new AtomicLong();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread consumerThread;

    public CostRecorder(LlmCallLogMapper llmCallLogMapper, ModelCatalog catalog) {
        this.llmCallLogMapper = llmCallLogMapper;
        this.catalog = catalog;
    }

    /**
     * 记一次调用（异步——立即返回；落账失败与队列满都不影响调用方）
     */
    public void record(LlmCallMeta meta, ModelRef model, CostSample sample) {
        LlmCallLogDO row = assemble(meta, model, sample);
        if (!queue.offer(row)) {
            long dropped = droppedCount.incrementAndGet();
            log.warn("计量队列满丢弃（累计 {} 条——计量丢一条不伤主流程）", dropped);
        }
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        consumerThread = Thread.ofVirtual().name("llm-cost-recorder").start(this::consumeLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                List<LlmCallLogDO> batch = collectBatch();
                if (!batch.isEmpty()) {
                    llmCallLogMapper.insertBatch(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 落库失败只告警——计量线程不因数据库抖动退出
                log.warn("llm_call_log 批量落库失败（本批丢弃）", e);
            }
        }
    }

    /**
     * 500ms 窗口内收集 ≤100 行（阻塞等第一条——空窗不烧 CPU）
     */
    private List<LlmCallLogDO> collectBatch() throws InterruptedException {
        LlmCallLogDO first = queue.poll(FLUSH_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        if (first == null) {
            return List.of();
        }
        List<LlmCallLogDO> batch = new ArrayList<>(BATCH_LIMIT);
        batch.add(first);
        queue.drainTo(batch, BATCH_LIMIT - 1);
        return batch;
    }

    private LlmCallLogDO assemble(LlmCallMeta meta, ModelRef model, CostSample sample) {
        LlmCallLogDO row = new LlmCallLogDO();
        if (meta != null) {
            row.setPlatform(meta.platform());
            row.setBotId(meta.botId());
            row.setGroupId(meta.groupId());
            row.setRunId(meta.runId());
            row.setRunKind(meta.runKind());
            row.setTraceId(meta.traceId());
            row.setReplay(meta.replay());
        }
        if (model != null) {
            row.setSlotCode(model.slotCode());
            row.setProviderCode(model.providerCode());
            row.setModelId(model.modelId());
            row.setCostAmount(costOf(model, sample.usage()));
        }
        row.setStopReason(sample.stopReason());
        row.setErrorCode(sample.errorCode());
        row.setTtftMs(narrowToUnsignedInt(sample.ttftMs()));
        row.setTotalMs(narrowToUnsignedInt(sample.totalMs()));
        AssistantMessage.TokenUsage usage = sample.usage();
        if (usage != null) {
            row.setInputTokens(usage.inputTokens());
            row.setOutputTokens(usage.outputTokens());
            row.setCachedTokens(usage.cachedTokens());
        }
        return row;
    }

    /**
     * 成本折算（六位小数）；单价任一缺失返回 null（落账列 NULL + 告警）
     */
    private BigDecimal costOf(ModelRef model, AssistantMessage.TokenUsage usage) {
        if (usage == null || model == null) {
            return null;
        }
        cn.edu.scut.chatbot.loop.ModelPrices prices = catalog.pricesOf(model.providerCode(), model.modelId());
        if (prices == null || prices.inputPerMillion() == null
                || prices.outputPerMillion() == null || prices.cachedPerMillion() == null) {
            log.warn("模型单价缺失，cost_amount 落 null provider={} model={}",
                    model.providerCode(), model.modelId());
            return null;
        }
        BigDecimal input = BigDecimal.valueOf(usage.inputTokens() - usage.cachedTokens());
        BigDecimal cached = BigDecimal.valueOf(usage.cachedTokens());
        BigDecimal output = BigDecimal.valueOf(usage.outputTokens());
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        return input.multiply(prices.inputPerMillion())
                .add(cached.multiply(prices.cachedPerMillion()))
                .add(output.multiply(prices.outputPerMillion()))
                .divide(million, 6, RoundingMode.HALF_UP);
    }

    private static Integer narrowToUnsignedInt(long value) {
        return value < 0 ? 0 : (int) Math.min(value, Integer.MAX_VALUE);
    }
}
