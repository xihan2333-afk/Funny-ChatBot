package cn.edu.scut.chatbot.llm.slot;

import lombok.Data;

/**
 * model_config 表 DO：模型清单（能力、单价）。
 *
 * <p>capability/price 是 JSON 原文（String）而非 TypeHandler 类型化字段：装载时
 * 逐行解析、非法行告警跳过不拖垮其他模型（data-model 第 7 节）——行级容错只有
 * Java 层 try-catch 能给，TypeHandler 抛错会中断整个查询。
 */
@Data
public class ModelConfigDO {

    private Long id;

    private String providerCode;

    private String modelId;

    private String displayName;

    /**
     * {"vision":bool,"toolUse":bool,"contextWindow":int,"maxOutputTokens":int} 原文
     */
    private String capability;

    /**
     * {"input":dec,"output":dec,"cached":dec} 每百万 token 单价原文
     */
    private String price;

    private Boolean enabled;
}
