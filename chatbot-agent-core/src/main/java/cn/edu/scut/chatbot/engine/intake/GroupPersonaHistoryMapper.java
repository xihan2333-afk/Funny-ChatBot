package cn.edu.scut.chatbot.engine.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GroupPersonaHistoryMapper {

    int insert(GroupPersonaHistoryDO row);

    List<GroupPersonaHistoryDO> selectByGroup(@Param("platform") String platform,
                                              @Param("botId") String botId,
                                              @Param("groupId") String groupId,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    long countByGroup(@Param("platform") String platform,
                      @Param("botId") String botId,
                      @Param("groupId") String groupId);
}
