package cn.edu.scut.chatbot.memory.sediment;

import cn.edu.scut.chatbot.bootstrap.GroupKey;

/**
 * 工单落库契约（contracts/sediment-pipeline.md §1）：压缩流程在写摘要的
 * 同一数据库事务内调用（@Transactional 边界覆盖摘要三写 + 本调用——R1）。
 */
public interface SedimentJobWriter {

    /**
     * 压缩事务内直插工单。
     * 幂等键 = summaryEntryId（每次压缩唯一）；重复投递由 uk_idem 唯一索引拒插
     * （DuplicateKeyException 静默吞——工单级幂等）。
     * payload = 本批滚出转录区间（首尾 entryId）+ 最新摘要 entryId。
     */
    void submitOnCompaction(GroupKey group, long summaryEntryId,
                            TranscriptRange compactedRange);

    /**
     * 转录区间引用（工单 payload 的取数锚——逐级回查：工单 → 区间 → 转录）
     */
    record TranscriptRange(long firstEntryId, long lastEntryId) {
    }
}
