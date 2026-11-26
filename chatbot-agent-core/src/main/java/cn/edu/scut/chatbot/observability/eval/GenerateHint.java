package cn.edu.scut.chatbot.observability.eval;

/**
 * 场景卡生成辅助的出题提示（generateDraft 入参）：出题双轴——互动性质
 * （合作应答 / 玩笑攻击 / 混合动机）× 能力域（L1 注意力 / 人格表达 / 形态纪律 /
 * 安全红线）+ 自由补充说明。
 */
public record GenerateHint(String interactionNature,
                           String capabilityDomain,
                           String note) {

    public static final String NATURE_COOPERATE = "合作应答";
    public static final String NATURE_PLAYFUL_ATTACK = "玩笑攻击";
    public static final String NATURE_MIXED = "混合动机";

    public static final String DOMAIN_L1_ATTENTION = "L1注意力";
    public static final String DOMAIN_PERSONA = "人格表达";
    public static final String DOMAIN_FORM = "形态纪律";
    public static final String DOMAIN_SAFETY = "安全红线";
}
