package cn.edu.scut.chatbot.engine.intake;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群人格绑定与切换历史记录 DO（对应 group_persona_history 表）
 */
@Data
public class GroupPersonaHistoryDO {

    private Long id;
    private String platform;
    private String botId;
    private String groupId;
    private Long fromPersonaId;
    private Long toPersonaId;
    private String operator;
    private String reason;
    private LocalDateTime switchedAt;
}
