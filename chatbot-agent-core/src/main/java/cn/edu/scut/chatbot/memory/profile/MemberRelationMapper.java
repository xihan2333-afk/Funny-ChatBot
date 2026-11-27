package cn.edu.scut.chatbot.memory.profile;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * member_relation 表 Mapper：沉淀统计的写入面（类型覆盖最新观察、强度累计）与读取面。
 */
@Mapper
public interface MemberRelationMapper {

    /**
     * 建边或并轨累计：relation_kind 取最新观察覆盖、strength 累加（uk_pair 幂等锚点）
     */
    int upsertRelation(MemberRelationDO relation);

    List<MemberRelationDO> selectByGroup(@Param("platform") String platform,
                                         @Param("botId") String botId,
                                         @Param("groupId") String groupId);

    /**
     * 单对成员的关系行（a/b 已字典序归一化的查询）
     */
    MemberRelationDO selectPair(@Param("platform") String platform,
                                @Param("botId") String botId,
                                @Param("groupId") String groupId,
                                @Param("userIdA") String userIdA,
                                @Param("userIdB") String userIdB);

    /**
     * 个体数据清除（US12）：删除指定群内涉及该用户的全部关系边（user_id_a 或 user_id_b 命中）
     */
    int deleteByGroupAndUser(@Param("platform") String platform,
                             @Param("botId") String botId,
                             @Param("groupId") String groupId,
                             @Param("userId") String userId);
}
