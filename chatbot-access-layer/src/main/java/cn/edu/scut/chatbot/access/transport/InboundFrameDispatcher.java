package cn.edu.scut.chatbot.access.transport;

import cn.edu.scut.chatbot.access.codec.InboundPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 入站文本帧三路分发器（契约 onebot-ws-adapter.md 第 2 节）。
 *
 * <pre>
 * 入站文本帧
 *   ├── JSON 解析失败 → 兜底管线（留档 + raw_event[illegal_json]）
 *   ├── 含 echo 字段 → API 响应：echo 命中待匹配表则路由给等待方；
 *   │     不命中表 → 留档 + 警告日志丢弃（迟到的响应无主）
 *   └── 不含 echo → 平台事件：事件管线（留档 → 翻译 → 入流 XADD）
 * </pre>
 */
@Component
public class InboundFrameDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InboundFrameDispatcher.class);

    private final ObjectMapper json = new ObjectMapper();
    private final ApiResponseRouter apiResponseRouter;
    private final InboundPipeline inboundPipeline;

    /**
     * ApiResponseRouter 的实现是 OneBotWsClient，而 WsClient 又依赖本分发器——构造期循环。
     * 用 @Lazy 注入延迟代理打破：路由只发生在帧到达时（WsClient 早已完成构造），语义无损失。
     */
    public InboundFrameDispatcher(@Lazy ApiResponseRouter apiResponseRouter, InboundPipeline inboundPipeline) {
        this.apiResponseRouter = apiResponseRouter;
        this.inboundPipeline = inboundPipeline;
    }

    /**
     * 处理一帧协议端入站文本帧
     */
    public void dispatch(String frame) {
        JsonNode node;
        try {
            node = json.readTree(frame);
        } catch (Exception e) {
            inboundPipeline.handleUnparseableFrame(frame);
            return;
        }
        if (node == null || !node.isObject()) {
            inboundPipeline.handleUnparseableFrame(frame);
            return;
        }
        if (node.has("echo") && !node.get("echo").isNull()) {
            if (!apiResponseRouter.route(node)) {
                // 无主响应：留档 + 警告后丢弃——迟到或陌生的 echo 不应让进程报错
                inboundPipeline.handleOrphanApiResponse(frame);
                log.warn("收到无主 API 响应帧（echo 不命中待匹配表），已留档丢弃：{}",
                        node.path("echo").asText());
            }
            return;
        }
        inboundPipeline.handlePlatformEvent(frame, node);
    }
}
