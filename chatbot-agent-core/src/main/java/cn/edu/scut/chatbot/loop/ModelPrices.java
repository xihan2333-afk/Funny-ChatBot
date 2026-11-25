package cn.edu.scut.chatbot.loop;

import java.math.BigDecimal;

/**
 * 模型单价（model_config.price JSON 列的装载形态）：每百万 token 口径。
 *
 * <p>三项任一为 null 时成本折算落 null 并告警（不阻塞调用——单价没配只是不可见，
 * 不是不可用）；成本公式 = (input − cached) × 输入单价 + cached × 缓存单价 +
 * output × 输出单价（命中部分不重复计输入价，DeepSeek/OpenAI 账单口径）。
 */
public record ModelPrices(
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        BigDecimal cachedPerMillion
) {
}
