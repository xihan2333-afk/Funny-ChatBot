package cn.edu.scut.chatbot.memory.sediment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * sediment_job 表 Mapper：CAS 认领（唯一入口 status IN('pending','failed') 且
 * retry 未超限）、超时心跳查询、done 超期清理、兜底重投。
 */
@Mapper
public interface SedimentJobMapper {

    /**
     * 直插（幂等键撞 uk_idem 抛 DuplicateKeyException 由调用方静默吞——工单级幂等）
     */
    int insertJob(SedimentJobDO job);

    SedimentJobDO selectById(@Param("id") long id);

    /**
     * 可认领批次：pending 或 failed 未超限（worker 轮询取数；单群不要求串行——两级幂等兜底）
     */
    List<SedimentJobDO> selectClaimable(@Param("retryMax") int retryMax,
                                        @Param("limit") int limit);

    /**
     * CAS 认领：pending/failed（未超限）→ processing，判 affected rows（竞争只一方认领）
     */
    int claimJob(@Param("id") long id, @Param("retryMax") int retryMax);

    /**
     * 成功收口：done + output_ref 回填
     */
    int markDone(@Param("id") long id, @Param("outputRef") String outputRef);

    /**
     * 失败：failed + retry_count 递增（超限由兜底扫描判终态告警）
     */
    int markFailed(@Param("id") long id);

    /**
     * 兜底重投：超时 processing → pending（CAS 条件含超时判定——活着的不动）
     */
    int resetStaleProcessing(@Param("id") long id,
                             @Param("timeoutBefore") LocalDateTime timeoutBefore);

    /**
     * 超时 processing 工单（updated_at 心跳超阈值的可重投集合）
     */
    List<SedimentJobDO> selectStaleProcessing(@Param("timeoutBefore") LocalDateTime timeoutBefore,
                                              @Param("limit") int limit);

    /**
     * failed 已超限的终态工单（告警口径——管理面手动重投的对象）
     */
    List<SedimentJobDO> selectFailedTerminal(@Param("retryMax") int retryMax,
                                             @Param("limit") int limit);

    /**
     * 管理面手动重投终态工单（retry_count 归零重走）
     */
    int resetForManualRetry(@Param("id") long id);

    /**
     * done 行超期清理（表规模正比于在途速率，不正比于历史）
     */
    int deleteDoneBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 单群全部工单（兜底扫描判定转录覆盖缺口用——done 行只在 30 天窗口内）
     */
    List<SedimentJobDO> selectByGroup(@Param("platform") String platform,
                                      @Param("botId") String botId,
                                      @Param("groupId") String groupId);
}
