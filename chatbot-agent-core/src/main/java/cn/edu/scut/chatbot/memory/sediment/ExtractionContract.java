package cn.edu.scut.chatbot.memory.sediment;

import java.util.List;

/**
 * 沉淀抽取契约（data-model §4.3、contracts/sediment-pipeline.md §4）：
 * callOnce(summarize 槽位) 的结构化输出三段——候选条目 / 画像信号 / 冲突。
 */
public final class ExtractionContract {

    private ExtractionContract() {
    }

    /**
     * 抽取输出三段
     */
    public record ExtractionOutput(List<CandidateEntry> candidateEntries,
                                   List<ProfileSignal> profileSignals,
                                   List<Conflict> conflicts) {

        public ExtractionOutput {
            candidateEntries = candidateEntries == null ? List.of() : List.copyOf(candidateEntries);
            profileSignals = profileSignals == null ? List.of() : List.copyOf(profileSignals);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    /**
     * 候选记忆条目
     */
    public record CandidateEntry(String memoryType, String content, String userId,
                                 String occurredAtHint, List<String> keywords,
                                 String mergedContent) {

        public CandidateEntry {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    /**
     * 画像信号（只采信直接互动——转述打折，提示词明示）
     */
    public record ProfileSignal(String userId, String callName,
                                List<String> interests, String relationClue) {

        public ProfileSignal {
            interests = interests == null ? List.of() : List.copyOf(interests);
        }
    }

    /**
     * 冲突：候选与既有记忆矛盾（落库时旧条 superseded + 互链）
     */
    public record Conflict(String existingContent, String newContent, String reason) {
    }
}
