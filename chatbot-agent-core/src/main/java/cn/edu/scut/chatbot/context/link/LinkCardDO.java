package cn.edu.scut.chatbot.context.link;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局链接卡片持久化缓存 DO（对应 link_card 表）
 */
@Data
public class LinkCardDO {

    private Long id;
    private String normalizedUrl;
    private String urlHash;
    private String domain;
    private String title;
    private String siteName;
    private String summary;
    private String source;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
