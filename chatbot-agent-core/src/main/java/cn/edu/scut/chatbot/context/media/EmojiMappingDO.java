package cn.edu.scut.chatbot.context.media;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群级表情包指纹映射库 DO（对应 emoji_mapping 表）
 */
@Data
public class EmojiMappingDO {

    private Long id;
    private String platform;
    private String botId;
    private String groupId;
    private String fingerprint;
    private String description;
    private String source;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
