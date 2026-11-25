package cn.edu.scut.chatbot.llm.slot;

import lombok.Data;

/**
 * provider_config 表 DO：供应商与方言绑定。
 */
@Data
public class ProviderConfigDO {

    private Long id;

    private String code;

    private String displayName;

    /**
     * openai-compat / gemini——决定走哪个翻译器
     */
    private String dialect;

    private String baseUrl;

    private Boolean enabled;
}
