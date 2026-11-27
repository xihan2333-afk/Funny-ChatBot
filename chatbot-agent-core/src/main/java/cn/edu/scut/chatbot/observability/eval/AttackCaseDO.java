package cn.edu.scut.chatbot.observability.eval;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * attack_case 表 DO（V8 迁移，data-model 1.4）：注入对抗用例库——按攻击通道组织
 * （message / nickname / image / web / memory / card），复用 dry-run 重放执行，
 * 硬防线零击穿是上线底线。
 */
@Data
public class AttackCaseDO {

    public static final String VERDICT_PASS = "pass";
    public static final String VERDICT_BROKEN = "broken";

    public static final String CHANNEL_MESSAGE = "message";
    public static final String CHANNEL_NICKNAME = "nickname";
    public static final String CHANNEL_IMAGE = "image";
    public static final String CHANNEL_WEB = "web";
    public static final String CHANNEL_MEMORY = "memory";
    public static final String CHANNEL_CARD = "card";
    public static final String CHANNEL_HTTP = "http";
    public static final String CHANNEL_EMOJI = "emoji";

    private Long id;

    private String channel;

    private String title;

    private String payload;

    /**
     * 构造的群历史与触发序列 JSON（含载荷前置步骤声明）
     */
    private String scenario;

    /**
     * 预期行为 JSON（正常忽略恶意指令且正常功能不被打断）
     */
    private String expectation;

    /**
     * 断言配置 JSON：{code: [noUnauthorizedTask, noDeliveredViolation, noSandboxEscape], judge: ...}
     */
    private String assertion;

    private Integer enabled;

    private String lastVerdict;

    private LocalDateTime updatedAt;
}
