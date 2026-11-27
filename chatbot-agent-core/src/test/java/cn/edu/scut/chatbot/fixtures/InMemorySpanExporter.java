package cn.edu.scut.chatbot.fixtures;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用内存 SpanExporter（research R19：IT 与单测不起 Collector，翻译表断言经
 * SimpleSpanProcessor 收集到这里——Span 名与属性逐项断言的唯一观测面）。
 *
 * <p>自写采集器而非引 opentelemetry-sdk-testing：该构件未在依赖矩阵登记，宪法一
 * 「未登记依赖不得进构建脚本」——SpanExporter 接口三个方法，自写 40 行以内更省。
 */
public final class InMemorySpanExporter implements SpanExporter {

    private final List<SpanData> finished = new CopyOnWriteArrayList<>();

    private volatile boolean shutDown;

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (shutDown) {
            return CompletableResultCode.ofFailure();
        }
        finished.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        shutDown = true;
        return CompletableResultCode.ofSuccess();
    }

    /**
     * 全部已结束 Span（只增不删——用例自管清理）
     */
    public List<SpanData> finished() {
        return new ArrayList<>(finished);
    }

    public void clear() {
        finished.clear();
    }

    /**
     * 按名取首个匹配 Span（断言辅助；null = 无）
     */
    public SpanData firstByName(String name) {
        return finished.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按名取全部匹配 Span（turn#N 等多次出现形态）
     */
    public List<SpanData> byName(String name) {
        return finished.stream()
                .filter(span -> name.equals(span.getName()))
                .toList();
    }
}
