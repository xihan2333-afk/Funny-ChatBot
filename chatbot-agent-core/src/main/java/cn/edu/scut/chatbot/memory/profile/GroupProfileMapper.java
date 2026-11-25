package cn.edu.scut.chatbot.memory.profile;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * group_profile 表 Mapper：每日统计任务的唯一写入面（建行或并轨刷新）与读取面。
 */
@Mapper
public interface GroupProfileMapper {

    /**
     * 建行或并轨刷新（ON DUPLICATE：非空字段覆盖——统计任务产什么刷什么）
     */
    int upsertProfile(GroupProfileDO profile);

    GroupProfileDO selectByGroup(@Param("platform") String platform,
                                 @Param("botId") String botId,
                                 @Param("groupId") String groupId);
}
