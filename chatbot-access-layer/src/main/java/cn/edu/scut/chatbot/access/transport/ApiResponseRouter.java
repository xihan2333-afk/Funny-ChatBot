package cn.edu.scut.chatbot.access.transport;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * API 响应路由接口（契约 onebot-ws-adapter.md 第 2 节帧分发的 echo 分支）。
 *
 * <p>Universal 连接上「含 echo 字段的入站帧」是 API 调用的响应，按 echo 匹配待匹配调用表，
 * 命中即把响应交给对应的等待方。US1 由 OneBotWsClient 的内置登记表实现（连接自检用），
 * US2 的 OneBotApiClient 沿用同一张表扩展正式调用。
 */
public interface ApiResponseRouter {

    /**
     * 尝试路由一帧 API 响应。
     *
     * @param responseFrame 已判定含 echo 字段的入站帧
     * @return true 表示 echo 命中待匹配表并完成路由；false 表示无主响应
     * （迟到或陌生的 echo，由帧分发器留档并记警告日志后丢弃）
     */
    boolean route(JsonNode responseFrame);
}
