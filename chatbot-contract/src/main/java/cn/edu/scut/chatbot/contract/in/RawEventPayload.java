package cn.edu.scut.chatbot.contract.in;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.envelope.RawEventRef;

/**
 * 兜底原语载荷（data-model.md 第 4 节）：留档了但不认识的报文收敛于此，Agent 可忽略。
 *
 * <p>三种触发：非法 JSON、未知结构、翻译内部异常——原文均已先留档，rawRef 必填回指留档行。
 *
 * @param reason 失败原因短码，取值见 {@link RawEventReasonCodes}
 * @param rawRef 留档行引用，非空
 */
public record RawEventPayload(
        String reason,
        RawEventRef rawRef
) implements EnvelopePayload {
}
