package cn.edu.scut.chatbot.memory.social;

import cn.edu.scut.chatbot.bootstrap.GroupKey;

import java.util.List;

/**
 * 话题生命周期（contracts/sediment-pipeline.md §6）：话题条目（memory_entry.topic 类）
 * 的第二状态维度 hot / cooling / closed——由压缩摘要话题段驱动、群消息再热判定唤醒。
 */
public interface TopicLifecycleService {

    /**
     * 压缩完成时驱动（复用摘要话题段零模型调用）：本轮话题段中未出现的热话题 →
     * 冷却计次 +1（连续 coolingCompressions 次压缩不含 → cooling）。
     * 话题段中出现的热话题 → 热度 +1、冷却计次清零。
     */
    void onCompactionRefreshed(GroupKey group, List<String> topicsInSummary);

    /**
     * 再热判定：群消息事件驱动顺带执行（keywords 轻量匹配零模型调用）；
     * 命中 cooling 话题关键词 → 状态回 hot + 通知 TaskWakeScanner 唤醒订阅该话题的 Task
     */
    void onGroupMessage(GroupKey group, String messageText);

    /**
     * 日批兜底：cooling 超期（closedAfterDays）→ closed（翻篇不再唤醒）。
     * 主驱动是压缩驱动的迁移，本方法兜安静群与长尾。
     */
    void runDailyCloseSweep();
}
