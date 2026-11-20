package cn.edu.scut.chatbot.contract.message;

/**
 * 多模态消息段的共同接口：GroupMessage / SendGroupMessage 载荷内的段列表元素
 * （data-model.md 第 3 节）。
 *
 * <p>线格式上每个段对象带 {@code part} 字符串字段作多态标记（与信封 type 同样的扁平多态规约），
 * 其余字段按段类型平铺——不出现 Java 类名与 @class 类型信息。
 */
public sealed interface MessagePart permits
        TextPart,
        AtPart,
        QuotePart,
        ImagePart,
        AudioPart,
        VideoPart,
        FacePart,
        UnsupportedPart {
}
