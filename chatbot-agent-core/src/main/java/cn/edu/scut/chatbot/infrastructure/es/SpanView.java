package cn.edu.scut.chatbot.infrastructure.es;

import java.util.Map;

/**
 * 链路页瀑布图的单个 Span 视图（observe-query 契约第 1 节）：名、父子、起止、属性。
 * 属性保持 ES 文档的原始键（chatbot.* 领域属性与 gen_ai.* 生态属性两级命名，报表只依赖 chatbot.*）。
 */
public record SpanView(String name,
                       String spanId,
                       String parentSpanId,
                       long startMillis,
                       long durationMillis,
                       Map<String, Object> attributes) {
}
