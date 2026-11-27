package cn.edu.scut.chatbot.security.gatepolicy;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * blacklist 表 Mapper：黑名单 CRUD 与判定查询（L0 消费与策略收紧审计的数据面）
 */
@Mapper
public interface BlacklistMapper {

    void insert(BlacklistDO row);

    /**
     * 判定查询：群级命中（scope=group 且三元组匹配）或全局命中（scope=global）
     */
    BlacklistDO selectHit(@Param("platform") String platform,
                          @Param("botId") String botId,
                          @Param("groupId") String groupId,
                          @Param("userId") String userId);

    /**
     * 全量清单（管理面展示与收紧审计比对）
     */
    List<BlacklistDO> selectAll();

    void deleteById(@Param("id") Long id);
}
