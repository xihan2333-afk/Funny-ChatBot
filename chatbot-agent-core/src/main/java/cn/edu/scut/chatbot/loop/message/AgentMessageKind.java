package cn.edu.scut.chatbot.loop.message;

/**
 * 转录条目种类（data-model 第 2 节）：决定投影分派键与默认投影形态。
 *
 * <p>字符串短码形态与 transcript_entry.kind 列一致（入库映射用 {@link #code()}）。
 */
public enum AgentMessageKind {

    USER("user"),
    ASSISTANT("assistant"),
    TOOL_RESULT("toolResult"),
    EVENT("event"),
    DELIVERY_REPORT("deliveryReport"),
    SYSTEM_NOTE("systemNote"),
    LINK_CARD("linkCard"),
    SUMMARY("summary");

    private final String code;

    AgentMessageKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 按列值还原（未知值抛 IllegalArgumentException——数据损坏即暴露）
     */
    public static AgentMessageKind fromCode(String code) {
        for (AgentMessageKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知转录条目 kind：" + code);
    }
}
