package cn.edu.scut.chatbot.engine.session;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.EngineErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话管理器（spec US3、research R5）：AgentSession 惰性创建与重启恢复、
 * 群推理权内存闸（每群一个 InferencePermit：先来后到、交付类等待超阈值提升队头优先）。
 *
 * <p>并发问题的思考范围收窄到本组件内部（宪法）：外部只见 acquire/release（InferenceTicket）。
 * 推理权是进程内资源——多实例部署的演进口（Redis 锁/DB 乐观锁上移）不在本 feature 范围，
 * 接口形态不留多实例假设。
 *
 * <p>等待用 ReentrantLock + 每等待者独立 Condition（虚拟线程感知；
 * synchronized 长块禁区纪律）。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /**
     * 推理权获取票据：对外唯一可见形态（release 凭票归还；token 防无关 release 误释放）
     */
    public record InferenceTicket(String platform, String botId, String groupId,
                                  String purpose, long acquiredAt, long token) {
    }

    /**
     * 推理竞争目的（决定优先级提升资格）
     */
    public static final String PURPOSE_TURN = "turn";
    public static final String PURPOSE_LIGHT_TASK = "lightTask";
    public static final String PURPOSE_DELIVERY = "delivery";

    private final AgentSessionMapper sessionMapper;
    private final TranscriptCodec transcriptCodec;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final long deliveryWaitBoostMillis;

    /**
     * 群 → 推理权闸（惰性建闸）
     */
    private final Map<String, InferencePermit> permits = new ConcurrentHashMap<>();

    public SessionManager(AgentSessionMapper sessionMapper,
                          TranscriptCodec transcriptCodec,
                          IdGenerator idGenerator,
                          Clock clock,
                          long deliveryWaitBoostMillis) {
        this.sessionMapper = sessionMapper;
        this.transcriptCodec = transcriptCodec;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.deliveryWaitBoostMillis = deliveryWaitBoostMillis;
    }

    // =====================================================================
    // 推理权（内存闸）
    // =====================================================================

    /**
     * 排队获取群推理权（阻塞直至获得；先来后到，交付类等待超阈值被后台提升队头）。
     * 推理一旦开始不被抢占——任何组件不得 interrupt 持有者。
     */
    public InferenceTicket acquireInference(String platform, String botId, String groupId,
                                            String purpose) {
        InferencePermit permit = permits.computeIfAbsent(
                platform + ":" + botId + ":" + groupId, key -> new InferencePermit());
        return permit.acquire(platform, botId, groupId, purpose);
    }

    /**
     * 归还推理权并唤醒下一位（按优先序；凭票据 token 校验——非当前持有者的迟到释放无害）
     */
    public void releaseInference(InferenceTicket ticket) {
        InferencePermit permit = permits.get(
                ticket.platform() + ":" + ticket.botId() + ":" + ticket.groupId());
        if (permit != null) {
            permit.release(ticket);
        }
    }

    /**
     * 单群推理权闸：公平排队 + 交付优先提升 + 持有者票据校验（holderToken——release 凭票生效）
     */
    private final class InferencePermit {

        private final ReentrantLock lock = new ReentrantLock(true);

        private boolean held = false;

        private final List<Waiter> waiters = new ArrayList<>();

        /**
         * 票据序号发号器：每次推理权移交发放新 token（release 凭 token 匹配 holderToken）
         */
        private final java.util.concurrent.atomic.AtomicLong tokenSeq =
                new java.util.concurrent.atomic.AtomicLong();

        /**
         * 当前持有者的票据 token（-1 = 空闲）
         */
        private long holderToken = -1;

        private final class Waiter {
            final String purpose;
            final long enqueuedAt;
            final Condition condition = lock.newCondition();
            boolean granted = false;
            long grantedToken;

            Waiter(String purpose, long enqueuedAt) {
                this.purpose = purpose;
                this.enqueuedAt = enqueuedAt;
            }
        }

        InferenceTicket acquire(String platform, String botId, String groupId, String purpose) {
            lock.lock();
            try {
                if (!held && waiters.isEmpty()) {
                    held = true;
                    holderToken = tokenSeq.incrementAndGet();
                    return new InferenceTicket(platform, botId, groupId, purpose, clock.millis(), holderToken);
                }
                Waiter waiter = new Waiter(purpose, clock.millis());
                waiters.add(waiter);
                while (!waiter.granted) {
                    try {
                        waiter.condition.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        // 中断的等待者退出队列（不持锁——它从未获得推理权）
                        waiters.remove(waiter);
                        throw new NonRetryableException(EngineErrorCodes.SESSION_STORE_FAILED,
                                "推理权等待被中断", e);
                    }
                }
                return new InferenceTicket(platform, botId, groupId, purpose, clock.millis(), waiter.grantedToken);
            } finally {
                lock.unlock();
            }
        }

        void release(InferenceTicket ticket) {
            lock.lock();
            try {
                if (ticket == null || ticket.token() != holderToken) {
                    return;   // 迟到/陈旧/他人票据：不释放当前持有权
                }
                held = false;
                holderToken = -1;
                Waiter next = pickNext();
                if (next != null) {
                    held = true;
                    next.grantedToken = tokenSeq.incrementAndGet();
                    holderToken = next.grantedToken;
                    next.granted = true;
                    next.condition.signal();
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * 下一位选择：交付类等待超阈值优先（不被新 Turn 饿死），否则先来后到
         */
        private Waiter pickNext() {
            if (waiters.isEmpty()) {
                return null;
            }
            long now = clock.millis();
            for (Waiter waiter : waiters) {
                if (PURPOSE_DELIVERY.equals(waiter.purpose)
                        && now - waiter.enqueuedAt > deliveryWaitBoostMillis) {
                    waiters.remove(waiter);
                    return waiter;
                }
            }
            return waiters.remove(0);
        }
    }

    // =====================================================================
    // 会话与转录
    // =====================================================================

    /**
     * 惰性获取（或创建）常驻会话：群首次交互建行，已存在直接取
     */
    public AgentSessionDO getOrCreateSession(String platform, String botId, String groupId) {
        AgentSessionDO existing = sessionMapper.selectByGroup(platform, botId, groupId);
        if (existing != null) {
            return existing;
        }
        AgentSessionDO fresh = new AgentSessionDO();
        fresh.setPlatform(platform);
        fresh.setBotId(botId);
        fresh.setGroupId(groupId);
        fresh.setLifecycle(AgentSessionDO.LIFECYCLE_ACTIVE);
        fresh.setWindowLevel(AgentSessionDO.WINDOW_ACTIVE);
        sessionMapper.insertSession(fresh);
        AgentSessionDO created = sessionMapper.selectByGroup(platform, botId, groupId);
        if (created == null) {
            throw new NonRetryableException(EngineErrorCodes.SESSION_STORE_FAILED,
                    "会话创建后读取失败（platform=" + platform + ", groupId=" + groupId + "）");
        }
        return created;
    }

    /**
     * 轮次原子落库（TurnPersister 的实现入口；调用方已在事务内由 mapper 批量写保证）
     */
    public void appendTranscript(AgentSessionDO session,
                                 List<cn.edu.scut.chatbot.loop.message.AgentMessage> messages,
                                 Long runId) {
        List<TranscriptEntryDO> entries = new ArrayList<>(messages.size());
        for (cn.edu.scut.chatbot.loop.message.AgentMessage message : messages) {
            TranscriptEntryDO entry = new TranscriptEntryDO();
            entry.setId(idGenerator.nextId());
            entry.setSessionId(session.getId());
            entry.setPlatform(session.getPlatform());
            entry.setBotId(session.getBotId());
            entry.setGroupId(session.getGroupId());
            entry.setKind(message.kind().code());
            entry.setPayload(transcriptCodec.encode(message));
            entry.setWeight(message.weight());
            entry.setOccurredAt(java.time.LocalDateTime.ofInstant(message.occurredAt(), clock.getZone()));
            entry.setRunId(runId);
            entries.add(entry);
        }
        if (!entries.isEmpty()) {
            sessionMapper.insertTranscriptBatch(entries);
        }
    }

    /**
     * 会话历史投影快照（Run 启动时组装 AgentContext.messages 用）
     */
    public List<cn.edu.scut.chatbot.loop.message.AgentMessage> loadTranscript(AgentSessionDO session) {
        List<TranscriptEntryDO> ordered = sessionMapper.selectOrdered(session.getId());
        List<cn.edu.scut.chatbot.loop.message.AgentMessage> messages = new ArrayList<>(ordered.size());
        for (TranscriptEntryDO entry : ordered) {
            try {
                messages.add(transcriptCodec.decode(entry.getPayload(), entry.getKind()));
            } catch (Exception e) {
                // 单条坏数据不拖垮整段历史（跳过并告警——历史全量只增不改，坏条目人工修）
                log.warn("转录条目解码失败跳过 entryId={} kind={}", entry.getId(), entry.getKind(), e);
            }
        }
        return messages;
    }

    /**
     * 群销毁卸载会话（推理权闸残留无害——群不再有新推理请求）
     */
    public void destroySession(String platform, String botId, String groupId) {
        sessionMapper.markDestroyed(platform, botId, groupId);
        permits.remove(platform + ":" + botId + ":" + groupId);
    }
}
