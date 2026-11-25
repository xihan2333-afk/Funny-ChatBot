package cn.edu.scut.chatbot.loop;

/**
 * 模型能力声明（model_config.capability JSON 列的装载形态）。
 *
 * <p>vision 是转录投影「主模型无视觉 → 窗口内一律描述行」的判定依据；
 * toolUse 供装配参考与告警，不做硬闸（配错模型让它跑一次比静默拒掉更可见）。
 */
public record ModelCapabilities(
        boolean vision,
        boolean toolUse,
        int contextWindow,
        int maxOutputTokens
) {
}
