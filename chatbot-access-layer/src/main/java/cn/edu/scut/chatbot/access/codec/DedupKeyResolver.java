package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * dedupKey 解析器（data-model.md 第 2 节 + research R1 唯一性事实）：
 * 消息类事件取平台 message_id；notice / request 类平台无原生事件 ID 属常态路径
 * （OneBot 规范未定义事件级唯一 ID），退化为报文内容 SHA-256 截断十六进制。
 *
 * <p>「不自造幂等键」的语义：键完全由平台数据决定（message_id 原样、其余报文内容哈希），
 * 接入层不去重——幂等发生在 Agent 消费端（宪法第二条）。
 */
@Component
public class DedupKeyResolver {

    /**
     * 内容哈希键长度（SHA-256 前 32 个十六进制字符），远小于 128 上限
     */
    private static final int CONTENT_HASH_LENGTH = 32;

    /**
     * 判定事件是否消息类（有平台原生 message_id 可用作幂等键）
     */
    public static boolean isMessageEvent(JsonNode event) {
        String postType = event.path("post_type").asText();
        return "message".equals(postType) || "message_sent".equals(postType);
    }

    /**
     * 解析事件的 dedupKey：消息类取 message_id（number/string 双形态容忍），其余取内容哈希
     */
    public String dedupKeyOf(JsonNode event, String rawFrame) {
        if (isMessageEvent(event) && event.hasNonNull("message_id")) {
            return event.path("message_id").asText();
        }
        return contentHash(rawFrame);
    }

    /**
     * 报文内容 SHA-256 截断十六进制：同一报文重推得到同键，Agent 幂等去重照常可用
     */
    public String contentHash(String rawFrame) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(rawFrame.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, CONTENT_HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
