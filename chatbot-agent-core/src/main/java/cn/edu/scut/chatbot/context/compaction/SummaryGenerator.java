package cn.edu.scut.chatbot.context.compaction;

import cn.edu.scut.chatbot.loop.message.AgentMessage;

import java.util.List;

/**
 * 压缩摘要生成端口（003-llm-api US7 端口化）：旧摘要文本 + 待压缩条目 → 五段摘要文本。
 * 压缩切点/水位/指针机制在 {@link TranscriptCompactor} 不动——只有摘要文本的生成
 * 是本端口的职责。
 *
 * <p>默认实现 {@link DeterministicSummaryGenerator}（002 既有确定性拼装）；
 * 真模型实现 {@link ModelSummarizer}（summarize 槽位 callOnce）就位后替换。
 */
public interface SummaryGenerator {

    /**
     * @param oldSummaryText    压缩区内的旧摘要（增量合并；可空）
     * @param compactingEntries 本次压缩区条目（旧摘要条目已剔除）
     * @return 五段摘要文本（讨论参与者/主要话题/结论与共识/情绪氛围/待办与悬念）
     */
    String generate(String oldSummaryText, List<AgentMessage> compactingEntries);
}
