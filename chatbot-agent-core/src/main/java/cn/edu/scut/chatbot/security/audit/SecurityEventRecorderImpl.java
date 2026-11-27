package cn.edu.scut.chatbot.security.audit;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@link SecurityEventRecorder} 实现：同步落库（审计不丢是底线，不走异步缓冲）；
 * 写入失败吞异常记 ERROR（带 errorCode 与 traceId）——审计失败不反噬触发它的业务路径，
 * 但必须有日志可查（宪法「异常与错误码」节：ERROR 日志必带 errorCode）。
 */
@Component
public class SecurityEventRecorderImpl implements SecurityEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(SecurityEventRecorderImpl.class);

    private final SecurityEventMapper mapper;
    private final Clock clock;

    public SecurityEventRecorderImpl(SecurityEventMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void record(SecurityEventKind kind, SecurityDims dims, Map<String, Object> detail, String traceId) {
        SecurityEventDO row = new SecurityEventDO();
        row.setEventKind(kind.code());
        if (dims != null) {
            row.setPlatform(dims.platform());
            row.setBotId(dims.botId());
            row.setGroupId(dims.groupId());
            row.setUserId(dims.userId());
        }
        row.setDetail(serialize(detail));
        row.setTraceId(traceId);
        row.setCreatedAt(LocalDateTime.now(clock));
        try {
            mapper.insert(row);
        } catch (Exception e) {
            log.error("安全事件落库失败（kind={}） errorCode={} traceId={}",
                    kind.code(), ErrorCodes.SECURITY_AUDIT_WRITE_FAILED, traceId, e);
        }
    }

    private String serialize(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return "{}";
        }
        try {
            return JsonMappers.shared().writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            // 载荷序列化失败不能丢事件本身：落一条标记说明，正文留在异常日志里
            log.error("安全事件 detail 序列化失败（降级为占位对象） errorCode={}",
                    ErrorCodes.SECURITY_AUDIT_WRITE_FAILED, e);
            return "{\"serializationFailed\":true}";
        }
    }
}
