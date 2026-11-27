package cn.edu.scut.chatbot.memory.persona;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人格演进第五类信号源：管理员人工反馈标记 DO（对应 persona_admin_feedback 表）
 */
@Data
public class PersonaAdminFeedbackDO {

    private Long id;
    private Long personaId;
    private String platform;
    private String botId;
    private String groupId;
    private String tag;
    private String comment;
    private String traceId;
    private String operator;
    private LocalDateTime createdAt;
}
