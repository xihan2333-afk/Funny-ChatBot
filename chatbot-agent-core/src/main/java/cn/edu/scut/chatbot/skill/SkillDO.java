package cn.edu.scut.chatbot.skill;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * skill 表 DO：管理员安装的指令文档（三级渐进披露的目录项）。
 */
@Data
public class SkillDO {

    private Long id;

    private String name;

    private String description;

    private String content;

    private Integer enabled;

    private String status;

    private Integer versionNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
