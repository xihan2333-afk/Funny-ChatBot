package cn.edu.scut.chatbot.access.archive;

/**
 * raw_event_log 写入接口（SQL 全部在 mapper/RawEventLogMapper.xml，接口上不写注解 SQL——方案 3.2.1 规约）。
 *
 * <p>接入层对留档表只有写入，没有回读（读取方是管理平台与排障查询，在 agent-core 侧）。
 */
public interface RawEventLogMapper {

    /**
     * 插入一行留档记录，id 经 useGeneratedKeys 回填到入参 DO。
     *
     * @return 影响行数（成功恒 1）
     */
    int insertRawEventLog(RawEventLogDO row);
}
