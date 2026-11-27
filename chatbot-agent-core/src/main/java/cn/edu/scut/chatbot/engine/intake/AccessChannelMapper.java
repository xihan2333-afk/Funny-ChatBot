package cn.edu.scut.chatbot.engine.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AccessChannelMapper {

    List<AccessChannelDO> selectAll();

    AccessChannelDO selectById(@Param("id") long id);

    AccessChannelDO selectByPlatformBot(@Param("platform") String platform, @Param("botId") String botId);

    int insert(AccessChannelDO row);

    int update(AccessChannelDO row);

    int deleteById(@Param("id") long id);

    long countEnabled();
}
