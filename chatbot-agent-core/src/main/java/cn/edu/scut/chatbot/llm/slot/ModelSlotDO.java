package cn.edu.scut.chatbot.llm.slot;

import lombok.Data;

/**
 * model_slot 表 DO：用途槽位绑定与默认生成参数。
 *
 * <p>genParams 是 JSON 原文：非法只拖垮本槽位（SlotResolver 层告警），不能在
 * 查询层中断其他槽位的装载。
 */
@Data
public class ModelSlotDO {

    private Long id;

    private String slotCode;

    private String providerCode;

    private String modelId;

    /**
     * {"temperature":num,"maxOutputTokens":int,"structuredOutput":bool,"passthrough":{...}} 原文
     */
    private String genParams;

    private Boolean enabled;

    /**
     * 绑定行最后变更时间（无自增版本列——变更时间即"绑定版本"，观测侧版本三键取数）
     */
    private java.time.LocalDateTime updatedAt;
}
