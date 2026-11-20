package cn.edu.scut.chatbot.contract.in;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;

/**
 * 群维度事件载荷（data-model.md 第 4 节）。OneBot 11 对群信息变更无标准事件，
 * 主要承接 NapCat 扩展事件 group_name。
 *
 * @param subType 群事件子类型短码：info_changed（信息变更）/ other
 */
public record GroupEventPayload(
        String subType
) implements EnvelopePayload {

    /**
     * 群信息变更（新群名等透传 extension）
     */
    public static final String SUB_INFO_CHANGED = "info_changed";

    /**
     * 未列举的群维度事件
     */
    public static final String SUB_OTHER = "other";
}
