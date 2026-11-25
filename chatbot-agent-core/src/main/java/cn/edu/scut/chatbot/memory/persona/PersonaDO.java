package cn.edu.scut.chatbot.memory.persona;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * persona 表 DO（自 prompt 包正位迁入——3.1.3 既定：人格实体建模归 memory/persona）：
 * definition 七字段组 JSON（解析归 PersonaRuntime，字段缺失回默认不整体失败）。
 */
@Data
public class PersonaDO {

    private Long id;

    private String name;

    private String definition;

    private Integer isDefault;

    private String status;

    private Integer versionNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
