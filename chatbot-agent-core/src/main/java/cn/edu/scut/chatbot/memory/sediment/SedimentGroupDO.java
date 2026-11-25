package cn.edu.scut.chatbot.memory.sediment;

import lombok.Data;

/**
 * transcript_entry 的群引用行（distinct 三元组——兜底扫描的遍历面）
 */
@Data
public class SedimentGroupDO {

    private String platform;

    private String botId;

    private String groupId;
}
