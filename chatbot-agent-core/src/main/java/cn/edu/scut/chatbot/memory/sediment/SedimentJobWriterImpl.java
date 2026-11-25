package cn.edu.scut.chatbot.memory.sediment;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 工单落库实现：INSERT 撞 uk_idem 抛 DuplicateKeyException 静默吞——
 * 压缩事务重放（如人工重跑同区间）不会产生第二张工单。
 */
@Component
public class SedimentJobWriterImpl implements SedimentJobWriter {

    private static final Logger log = LoggerFactory.getLogger(SedimentJobWriterImpl.class);

    private final SedimentJobMapper jobMapper;

    public SedimentJobWriterImpl(SedimentJobMapper jobMapper) {
        this.jobMapper = jobMapper;
    }

    @Override
    public void submitOnCompaction(GroupKey group, long summaryEntryId,
                                   TranscriptRange compactedRange) {
        SedimentJobDO job = new SedimentJobDO();
        job.setPlatform(group.platform());
        job.setBotId(group.botId());
        job.setGroupId(group.groupId());
        job.setTriggerKind(SedimentJobDO.KIND_COMPRESS);
        job.setIdempotentKey(String.valueOf(summaryEntryId));
        job.setPayload(payloadJson(summaryEntryId, compactedRange));
        job.setStatus(SedimentJobDO.STATUS_PENDING);
        job.setRetryCount(0);
        try {
            jobMapper.insertJob(job);
            log.info("沉淀工单落库（压缩事务内） group={} 幂等键={} 区间=[{}, {}]",
                    group.asMapKey(), summaryEntryId,
                    compactedRange.firstEntryId(), compactedRange.lastEntryId());
        } catch (DuplicateKeyException duplicate) {
            // 工单级幂等：同 summaryEntryId 的工单已存在（压缩重放），静默吞
            log.debug("沉淀工单幂等拒插（已存在） group={} 幂等键={}",
                    group.asMapKey(), summaryEntryId);
        }
    }

    private String payloadJson(long summaryEntryId, TranscriptRange range) {
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                JsonMappers.shared().createObjectNode();
        payload.put("summaryEntryId", summaryEntryId);
        payload.put("firstEntryId", range.firstEntryId());
        payload.put("lastEntryId", range.lastEntryId());
        return payload.toString();
    }
}
