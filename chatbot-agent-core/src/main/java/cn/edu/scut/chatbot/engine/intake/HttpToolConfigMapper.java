package cn.edu.scut.chatbot.engine.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * http_tool_config 表 Mapper。
 */
@Mapper
public interface HttpToolConfigMapper {

    List<HttpToolConfigDO> selectAll();

    HttpToolConfigDO selectByName(@Param("toolName") String toolName);

    int insertConfig(HttpToolConfigDO config);

    int deleteByName(@Param("toolName") String toolName);
}
