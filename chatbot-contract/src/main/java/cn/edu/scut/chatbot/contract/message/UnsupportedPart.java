package cn.edu.scut.chatbot.contract.message;

import java.util.Map;

/**
 * 未建模段：一切 Agent 核心暂不关心的 OneBot 段（share / json / xml / forward / mface / file 等）
 * 原样保留（data-model.md 第 3 节）——只建模标准语义、原始参数全量保真，是「信息不丢失」的段级落点。
 *
 * @param part    段类型多态标记，恒为 "unsupported"
 * @param rawType 原段类型名（OneBot 段的 type 字符串，如 "json"）
 * @param rawData 原段参数，键值一律字符串化保真
 */
public record UnsupportedPart(
        String part,
        String rawType,
        Map<String, String> rawData
) implements MessagePart {

    public static final String TYPE_UNSUPPORTED = "unsupported";
}
