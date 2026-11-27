package cn.edu.scut.chatbot.security.audit;

import java.util.Map;

/**
 * 安全事件统一入口（security-gate 契约第 8 节）：八类事件与管理操作审计的唯一写入面，
 * US7 注入留痕 / US8 硬防线拦截 / US10 沙箱审计 / US9 管理操作四处消费。
 *
 * <p>长期保留不滚动清理；全部带 traceId（凭它关联 ES Span 还原现场）。
 */
public interface SecurityEventRecorder {

    /**
     * 记一条安全事件。
     *
     * @param kind    八类事件之一
     * @param dims    隔离维度（可空——管理操作无群语境时传 {@link SecurityDims#none()}）
     * @param detail  事件载荷（命中规则、参数摘要、变更摘要、理由等——序列化为 JSON 落库）
     * @param traceId 链路 id（可空）
     */
    void record(SecurityEventKind kind, SecurityDims dims, Map<String, Object> detail, String traceId);
}
