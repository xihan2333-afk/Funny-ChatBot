package cn.edu.scut.chatbot.llm.sse;

import cn.edu.scut.chatbot.loop.CancellationSignal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 自写 SSE 解析器（contracts/dialect-translators.md 第 6 节、research R6）：两方言共用，
 * 只做行协议切分、不理解 JSON——「逐完整事件回调（data 内容），JSON 解释归翻译器」。
 *
 * <p>解析规则（SSE 规范子集）：
 * <ul>
 *   <li>剥 {@code data:} 前缀，容忍冒号后零或一空格</li>
 *   <li>同一事件多行 data 以 {@code \n} 拼接（事件以空行终结）</li>
 *   <li>CRLF / LF / CR 双容忍（BufferedReader.readLine 原生处理三种行尾）</li>
 *   <li>注释行（{@code :} 开头）跳过；{@code data:} 后为空跳过该行</li>
 *   <li>{@code event:} / {@code id:} 等其他字段忽略不报错（本两方言不用）</li>
 * </ul>
 *
 * <p>终止语义：两种——{@code data: [DONE]} 哨兵（openai-compat 阵营，作为普通 data
 * 回调，识别归翻译器）与流关闭（readLine 返回 null，末尾未终结的 data 也回调——
 * 容忍缺失终结空行）。取消信号在每行读取点检查：置位即停止读流（增量保留在调用方）。
 */
public final class SseParser {

    /**
     * 逐完整事件回调（[DONE] 哨兵也经此——解析器不解释内容）
     */
    public interface DataListener {

        void onData(String data);
    }

    /**
     * 解析一个 SSE 响应体流（同步阻塞——调用方在虚拟线程上驱动）。
     *
     * @param body     响应体输入流
     * @param listener 逐事件 data 回调
     * @param signal   取消信号（每行读取点检查，置位即停止；null 表示不可取消）
     * @throws IOException 读流失败（连接中断等——调用方归一化为流中断错误）
     */
    public void parse(InputStream body, DataListener listener, CancellationSignal signal)
            throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder dataBuffer = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (signal != null && signal.isCancelled()) {
                return;
            }
            if (line.isEmpty()) {
                // 空行 = 事件终结：聚合的 data 整体回调一次
                flushEvent(dataBuffer, listener);
            } else if (line.startsWith(":")) {
                // 注释行（心跳 keep-alive 等）：跳过
            } else if (line.startsWith("data:")) {
                String payload = line.substring("data:".length());
                if (payload.startsWith(" ")) {
                    payload = payload.substring(1);
                }
                if (!payload.isEmpty()) {
                    if (dataBuffer.length() > 0) {
                        dataBuffer.append('\n');
                    }
                    dataBuffer.append(payload);
                }
            }
            // 其余字段（event:/id:/retry: 等）本两方言不用，忽略不报错
        }
        // 流关闭：末尾未空行终结的 data 也回调（宽容收尾——哨兵丢失不吞数据）
        flushEvent(dataBuffer, listener);
    }

    private void flushEvent(StringBuilder dataBuffer, DataListener listener) {
        if (dataBuffer.length() > 0) {
            listener.onData(dataBuffer.toString());
            dataBuffer.setLength(0);
        }
    }
}
