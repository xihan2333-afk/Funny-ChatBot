package cn.edu.scut.chatbot.security.audit;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * security_event 表 Mapper：统一入口写入 + 告警探针与审计查询取数。
 */
@Mapper
public interface SecurityEventMapper {

    int insert(SecurityEventDO row);

    /**
     * 某类事件在窗口内的条数（告警探针：沙箱异常 / 熔断 / 越权突增的取数源）
     */
    long countByKindSince(String eventKind, LocalDateTime since);

    /**
     * 全部安全事件在窗口内的条数（拦截率突增探针：近 1 小时计数 vs 7 日基线）
     */
    long countAllSince(LocalDateTime since);

    /**
     * 事件总数在时间区间内的条数（基线窗口取数：7 日总量 → 小时均值）
     */
    long countAllBetween(LocalDateTime from, LocalDateTime to);

    /**
     * 窗口内 mandateDenied 按用户计数最高的一位（越权尝试集中探针——同用户高频维度）
     */
    UserDeniedCount selectTopMandateUser(LocalDateTime since);

    /**
     * 按 traceId 取事件（链路页还原现场：security_event 与 ES Span 互相引用）
     */
    List<SecurityEventDO> selectByTraceId(String traceId);
}
