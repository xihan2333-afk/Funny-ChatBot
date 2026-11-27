package cn.edu.scut.chatbot.security.gatepolicy;

import lombok.Data;

/**
 * content_filter_word 表 DO：出站内容过滤词表两级判定——
 * block 拦截（发言被拦并终止本轮生成）/ review 仅记录不拦截；
 * 来源三态 builtin 内置 / manual 管理员维护 / converted 平台拒发转化。
 */
@Data
public class ContentFilterWordDO {

    public static final String LEVEL_BLOCK = "block";
    public static final String LEVEL_REVIEW = "review";
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_CONVERTED = "converted";

    private Long id;

    private String word;

    private String level;

    private String source;

    private Integer enabled;
}
