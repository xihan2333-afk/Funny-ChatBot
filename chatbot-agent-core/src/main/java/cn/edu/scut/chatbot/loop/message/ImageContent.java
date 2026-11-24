package cn.edu.scut.chatbot.loop.message;

/**
 * 图片引用段（接入层转存后的 storedRef；沙箱/生图产物同形态）。
 */
public record ImageContent(String storedRef) implements ContentPart {
}
