package cn.edu.scut.chatbot.infrastructure.es;

import java.util.List;

/**
 * 链路查询（observe-query 契约第 1 节）：管理平台链路页经主服务转发取数，
 * 浏览器不直连 ES。
 */
public interface TraceQueryService {

    /**
     * 按 traceId 取 Span 列表渲染瀑布图（名、父子、起止、属性）。
     */
    List<SpanView> getTrace(String traceId);

    /**
     * 按群 / 时间 / 槽位反查 traceId 列表（最近优先，上限 200 条）。
     */
    List<String> searchTraces(TraceFilter filter);
}
