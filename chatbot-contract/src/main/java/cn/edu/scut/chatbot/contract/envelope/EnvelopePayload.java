package cn.edu.scut.chatbot.contract.envelope;

/**
 * 信封载荷原语的共同接口：入 8 种 + 出 5 种，共 13 个 record（data-model.md 第 1 节）。
 *
 * <p>刻意不用 sealed 收口：Java 语言规定 sealed 类型与其子类必须同包（或同命名模块），
 * 而本契约的原语分居 in / out 两个包——跨进程多态由信封顶层 type 短码查映射表解释
 * （平铺多态，不使用 Jackson 默认多态注解），编译期的实现清单由 EnvelopeCodec 的
 * 类型映射表与 PrimitiveTypeCodes 常量共同收口，不依赖语言机制。
 *
 * <p>本接口只是结构标记，不参与序列化——它本身不会出现在 JSON 载荷里。
 */
public interface EnvelopePayload {
}
