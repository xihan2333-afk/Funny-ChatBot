package cn.edu.scut.chatbot.memory.entry;

import lombok.Data;

/**
 * memory_entry 的群引用行（distinct 三元组——衰减批任务与总量控制的分群遍历）
 */
@Data
public class MemoryGroupDO {

    private String platform;

    private String botId;

    private String groupId;
}
