package cn.edu.scut.chatbot.loop;

/**
 * Model 运行时对象（设计方案 2.3.4、data-model 第 2 节）：槽位解析产出的模型快照，
 * 翻译器路由（dialect）、URL 拼装（baseUrl/modelId）与成本折算（prices）的依据。
 *
 * <p>dialect 只允许 {@link #DIALECT_OPENAI_COMPAT} 与 {@link #DIALECT_GEMINI} 两值
 * （方言是协议能力随版本发布，注册表未知方言不可达）；装载侧（ModelCatalog）负责校验。
 * 放 loop 包是 2.3.4 既定语义的直译：prepareNextTurn 模型降级替换的就是
 * {@link ModelConfig} 里成对的这一对（内核纯净约束依赖边不约束数据形态）。
 */
public record ModelRef(
        String dialect,
        String providerCode,
        String modelId,
        String baseUrl,
        ModelCapabilities capabilities,
        ModelPrices prices,
        String slotCode
) {

    public static final String DIALECT_OPENAI_COMPAT = "openai-compat";
    public static final String DIALECT_GEMINI = "gemini";

    /**
     * dialect 合法性判定（装载侧校验用：非法 dialect 的配置行直接解析失败）
     */
    public static boolean isKnownDialect(String dialect) {
        return DIALECT_OPENAI_COMPAT.equals(dialect) || DIALECT_GEMINI.equals(dialect);
    }
}
