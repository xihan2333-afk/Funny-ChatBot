package cn.edu.scut.chatbot.engine.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * tool_config / http_tool_config 表 Mapper（配置域——泛化工具数据面）。
 */
@Mapper
public interface ToolConfigMapper {

    ToolConfigDO selectByName(@Param("toolName") String toolName);

    int upsertToolConfig(ToolConfigDO config);
}
