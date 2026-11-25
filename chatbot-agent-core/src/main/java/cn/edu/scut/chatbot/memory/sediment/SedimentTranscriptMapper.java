package cn.edu.scut.chatbot.memory.sediment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * transcript_entry 只读投影 Mapper（memory 域专用读取面）：
 * 沉淀抽取的输入是「转录区间条目投影行」——本 Mapper 只做区间读取与尾部扫描，
 * 不写不删（写路径永远归 engine/session 的 AgentSessionMapper）。
 * 领域包各自持 Mapper 是既定形态（PromptFragmentMapper 亦跨表持有 content_version）。
 */
@Mapper
public interface SedimentTranscriptMapper {

    /**
     * 按点 id 区间取条目（升序——抽取输入的时间序）
     */
    List<SedimentTranscriptDO> selectByGroupAndIdRange(@Param("platform") String platform,
                                                       @Param("botId") String botId,
                                                       @Param("groupId") String groupId,
                                                       @Param("firstEntryId") long firstEntryId,
                                                       @Param("lastEntryId") long lastEntryId);

    /**
     * 指定 id 之后的条目（兜底扫描的转录尾部——安静群无压缩的积压）
     */
    List<SedimentTranscriptDO> selectAfterId(@Param("platform") String platform,
                                             @Param("botId") String botId,
                                             @Param("groupId") String groupId,
                                             @Param("afterId") long afterId,
                                             @Param("limit") int limit);

    /**
     * 该群最大条目 id（尾部扫描的终点判定；无条目返回 null）
     */
    Long selectMaxEntryId(@Param("platform") String platform,
                          @Param("botId") String botId,
                          @Param("groupId") String groupId);

    /**
     * 有转录的群清单（兜底扫描遍历——安静群长期不压缩也要补投）
     */
    List<SedimentGroupDO> selectDistinctGroups();

    /**
     * 指定时刻之后的 user 条目（画像统计的互动信号源——只取群友侧、近窗扫描）
     */
    List<SedimentTranscriptDO> selectUserSince(@Param("platform") String platform,
                                               @Param("botId") String botId,
                                               @Param("groupId") String groupId,
                                               @Param("since") java.time.LocalDateTime since,
                                               @Param("limit") int limit);
}
