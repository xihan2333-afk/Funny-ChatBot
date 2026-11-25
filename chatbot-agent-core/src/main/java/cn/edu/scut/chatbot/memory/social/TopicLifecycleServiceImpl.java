package cn.edu.scut.chatbot.memory.social;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.task.TaskWakeScanner;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 话题生命周期实现。状态机：hot（沉淀默认）→ cooling（连续 coolingCompressions 次压缩
 * 摘要不含）→ closed（cooling 超 closedAfterDays 或被判定结束）；cooling 被群消息关键词
 * 再热命中 → 回 hot 并唤醒订阅 Task。
 *
 * <p>热度计数与冷却计次载体是群状态 Hash 的 topicSnapshot JSON（内存态、可丢——
 * 丢了只影响计次精度不影响状态机，条目状态在 MySQL）。全部轻量匹配零模型调用。
 */
@Component
public class TopicLifecycleServiceImpl implements TopicLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TopicLifecycleServiceImpl.class);

    static final String PARAMS_KEY = "memory.social.topic_params";

    private final MemoryEntryMapper memoryEntryMapper;
    private final GroupStateHash groupStateHash;
    private final TaskWakeScanner taskWakeScanner;
    private final SystemParamService systemParams;
    private final Clock clock;

    public TopicLifecycleServiceImpl(MemoryEntryMapper memoryEntryMapper,
                                     GroupStateHash groupStateHash,
                                     TaskWakeScanner taskWakeScanner,
                                     SystemParamService systemParams,
                                     Clock clock) {
        this.memoryEntryMapper = memoryEntryMapper;
        this.groupStateHash = groupStateHash;
        this.taskWakeScanner = taskWakeScanner;
        this.systemParams = systemParams;
        this.clock = clock;
    }

    @Override
    public void onCompactionRefreshed(GroupKey group, List<String> topicsInSummary) {
        try {
            int coolingCompressions = topicParams().coolingCompressions();
            List<MemoryEntryDO> hotEntries = memoryEntryMapper.selectTopicByLifecycle(
                    group.platform(), group.botId(), group.groupId(), MemoryEntryDO.TOPIC_HOT);
            ObjectNode snapshot = readSnapshot(group);

            for (MemoryEntryDO entry : hotEntries) {
                List<String> keywords = parseKeywords(entry.getKeywords());
                ObjectNode item = snapshotItem(snapshot, entry.getId(), keywords);
                if (matched(keywords, topicsInSummary)) {
                    item.put("count", item.path("count").asInt(0) + 1);
                    item.put("misses", 0);
                } else {
                    int misses = item.path("misses").asInt(0) + 1;
                    if (misses >= coolingCompressions) {
                        memoryEntryMapper.updateTopicLifecycle(entry.getId(), MemoryEntryDO.TOPIC_COOLING);
                        removeItem(snapshot, entry.getId());
                        log.info("话题冷却 entryId={} 连续 {} 次压缩摘要不含 group={}",
                                entry.getId(), misses, group.groupId());
                    } else {
                        item.put("misses", misses);
                    }
                }
            }
            writeSnapshot(group, snapshot);
        } catch (Exception e) {
            log.warn("话题冷却判定失败降级 group={}：{}", group.groupId(), e.getMessage());
        }
    }

    @Override
    public void onGroupMessage(GroupKey group, String messageText) {
        if (messageText == null || messageText.isBlank()) {
            return;
        }
        try {
            List<MemoryEntryDO> coolingEntries = memoryEntryMapper.selectTopicByLifecycle(
                    group.platform(), group.botId(), group.groupId(), MemoryEntryDO.TOPIC_COOLING);
            if (coolingEntries.isEmpty()) {
                return;
            }
            ObjectNode snapshot = readSnapshot(group);
            boolean reheated = false;
            for (MemoryEntryDO entry : coolingEntries) {
                List<String> keywords = parseKeywords(entry.getKeywords());
                boolean hit = false;
                for (String keyword : keywords) {
                    if (keyword != null && !keyword.isBlank() && messageText.contains(keyword)) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    memoryEntryMapper.updateTopicLifecycle(entry.getId(), MemoryEntryDO.TOPIC_HOT);
                    ObjectNode item = snapshotItem(snapshot, entry.getId(), keywords);
                    item.put("count", item.path("count").asInt(0) + 1);
                    item.put("misses", 0);
                    reheated = true;
                    log.info("话题再热回 hot entryId={} keyword 命中 group={}",
                            entry.getId(), group.groupId());
                }
            }
            if (reheated) {
                writeSnapshot(group, snapshot);
                // 唤醒订阅该话题的 WAITING Task（wake_condition=groupActive 的条件由
                // 既有扫描求值——群消息已登记为触发器，直调一次扫描即时兑现）
                taskWakeScanner.scanWaitingTasks();
            }
        } catch (Exception e) {
            log.warn("话题再热判定失败降级 group={}：{}", group.groupId(), e.getMessage());
        }
    }

    @Override
    public void runDailyCloseSweep() {
        try {
            int closedAfterDays = topicParams().closedAfterDays();
            LocalDateTime cutoff = LocalDateTime.now(clock).minus(Duration.ofDays(closedAfterDays));
            List<MemoryGroupRef> groups = activeTopicGroups(MemoryEntryDO.TOPIC_COOLING);
            int closed = 0;
            for (MemoryGroupRef groupRef : groups) {
                for (MemoryEntryDO entry : memoryEntryMapper.selectTopicByLifecycle(
                        groupRef.platform(), groupRef.botId(), groupRef.groupId(),
                        MemoryEntryDO.TOPIC_COOLING)) {
                    // updated_at 即置 cooling 时刻（updateTopicLifecycle 同步刷新）
                    if (entry.getUpdatedAt() != null && entry.getUpdatedAt().isBefore(cutoff)) {
                        memoryEntryMapper.updateTopicLifecycle(entry.getId(), MemoryEntryDO.TOPIC_CLOSED);
                        closed++;
                    }
                }
            }
            if (closed > 0) {
                log.info("话题翻篇兜底：{} 条 cooling 超期置 closed", closed);
            }
        } catch (Exception e) {
            log.warn("话题翻篇兜底失败降级：{}", e.getMessage());
        }
    }

    // ===== 内部工具 =====

    /**
     * 群引用（复用 selectActiveGroups 的返回形态，避免直接依赖 MemoryGroupDO 泛化）
     */
    private record MemoryGroupRef(String platform, String botId, String groupId) {
    }

    private List<MemoryGroupRef> activeTopicGroups(String lifecycle) {
        // 分群遍历来源：有 active 记忆条目的群清单（话题条目是记忆子集，覆盖安全）
        List<MemoryGroupRef> refs = new ArrayList<>();
        for (var group : memoryEntryMapper.selectActiveGroups()) {
            refs.add(new MemoryGroupRef(group.getPlatform(), group.getBotId(), group.getGroupId()));
        }
        return refs;
    }

    record TopicParams(int coolingCompressions, int closedAfterDays) {

        static TopicParams defaults() {
            return new TopicParams(2, 30);
        }
    }

    private TopicParams topicParams() {
        try {
            String raw = systemParams.getString(PARAMS_KEY, null);
            if (raw == null || raw.isBlank()) {
                return TopicParams.defaults();
            }
            JsonNode node = JsonMappers.shared().readTree(raw);
            return new TopicParams(
                    Math.max(1, node.path("coolingCompressions").asInt(2)),
                    Math.max(1, node.path("closedAfterDays").asInt(30)));
        } catch (Exception e) {
            log.warn("memory.social.topic_params 解析失败走缺省：{}", e.getMessage());
            return TopicParams.defaults();
        }
    }

    private boolean matched(List<String> keywords, List<String> topicsInSummary) {
        if (topicsInSummary == null || topicsInSummary.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            for (String topic : topicsInSummary) {
                if (topic != null && topic.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> parseKeywords(String keywordsJson) {
        if (keywordsJson == null || keywordsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = JsonMappers.shared().readValue(keywordsJson,
                    JsonMappers.shared().getTypeFactory().constructCollectionType(List.class, String.class));
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    private ObjectNode readSnapshot(GroupKey group) {
        try {
            String raw = groupStateHash.topicSnapshot(group.platform(), group.botId(), group.groupId());
            if (raw != null && !raw.isBlank()) {
                JsonNode parsed = JsonMappers.shared().readTree(raw);
                if (parsed instanceof ObjectNode objectNode) {
                    return objectNode;
                }
            }
        } catch (Exception e) {
            log.warn("话题快照读取失败按空快照处理：{}", e.getMessage());
        }
        ObjectNode fresh = JsonMappers.shared().createObjectNode();
        fresh.putArray("topics");
        return fresh;
    }

    private void writeSnapshot(GroupKey group, ObjectNode snapshot) {
        try {
            groupStateHash.updateTopicSnapshot(
                    group.platform(), group.botId(), group.groupId(),
                    JsonMappers.shared().writeValueAsString(snapshot));
        } catch (Exception e) {
            log.warn("话题快照写回失败降级（只影响计次精度不影响状态机）：{}", e.getMessage());
        }
    }

    private ObjectNode snapshotItem(ObjectNode snapshot, long entryId, List<String> keywords) {
        ArrayNode topics = (ArrayNode) snapshot.withArray("topics");
        for (JsonNode topic : topics) {
            if (topic instanceof ObjectNode item && item.path("entryId").asLong() == entryId) {
                return item;
            }
        }
        ObjectNode fresh = topics.addObject();
        fresh.put("entryId", entryId);
        ArrayNode keywordArray = fresh.putArray("keywords");
        keywords.forEach(keywordArray::add);
        fresh.put("count", 0);
        fresh.put("misses", 0);
        return fresh;
    }

    private void removeItem(ObjectNode snapshot, long entryId) {
        ArrayNode topics = (ArrayNode) snapshot.withArray("topics");
        for (int i = 0; i < topics.size(); i++) {
            if (topics.get(i).path("entryId").asLong() == entryId) {
                topics.remove(i);
                return;
            }
        }
    }
}
