package cn.edu.scut.chatbot.prompt;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * persona 表 DO：人格定义（definition 七字段组 JSON；完整人格系统归 2.4）。
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
