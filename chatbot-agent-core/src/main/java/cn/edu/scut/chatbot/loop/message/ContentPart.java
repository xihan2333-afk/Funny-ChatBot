package cn.edu.scut.chatbot.loop.message;

/**
 * 工具结果与用户消息的内容段：文本或图片引用（工具产出与群消息共用的多模态段形态）。
 */
public sealed interface ContentPart permits TextContent, ImageContent {
}
