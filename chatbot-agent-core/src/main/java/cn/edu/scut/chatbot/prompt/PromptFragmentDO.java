package cn.edu.scut.chatbot.prompt;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * prompt_fragment 表 DO：提示词片段（最小版本化单元）。
 */
@Data
public class PromptFragmentDO {

    private Long id;

    private String fragmentKey;

    /**
     * identity / group_context / protocol / task / standalone
     */
    private String layer;

    /**
     * turn / lightTask / worker / all（逗号分隔多值）
     */
    private String formFit;

    private String title;

    private String content;

    /**
     * 声明的变量清单 JSON 数组（保存时校验模板引用）
     */
    private String variables;

    /**
     * 内置锁定（骨架总则/发言协议等不可删改）
     */
    private Integer builtinLocked;

    /**
     * draft / published
     */
    private String status;

    private Integer versionNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static final String LAYER_IDENTITY = "identity";
    public static final String LAYER_GROUP_CONTEXT = "group_context";
    public static final String LAYER_PROTOCOL = "protocol";
    public static final String LAYER_TASK = "task";
    public static final String LAYER_STANDALONE = "standalone";
}
