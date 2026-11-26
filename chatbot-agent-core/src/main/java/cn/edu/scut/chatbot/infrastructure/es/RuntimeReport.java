package cn.edu.scut.chatbot.infrastructure.es;

import java.util.List;

/**
 * 运行时报表（JVM / GC / 线程，FR-027）：读 OTel 指标数据流（metrics-*，javaagent
 * 采集经 OTLP → Collector → ES）。返回最近一批指标快照；javaagent 缺席的环境
 * 数据流为空——返回空列表（合法状态，不是错误）。
 */
public record RuntimeReport(List<MetricSnapshot> metrics) {

    /**
     * 单指标快照：指标名（OTel 语义约定名，如 process.runtime.jvm.memory.heap）、
     * 最近值、属性（如 pool / type 维度）
     */
    public record MetricSnapshot(String name,
                                 double value,
                                 java.util.Map<String, String> attributes,
                                 long timestampMillis) {
    }
}
