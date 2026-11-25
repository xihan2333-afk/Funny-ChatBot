package cn.edu.scut.chatbot.llm.slot;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * model_config 表 Mapper（全量装载——ModelCatalog 快照重建用）。
 */
@Mapper
public interface ModelConfigMapper {

    List<ModelConfigDO> selectAll();

    int insert(ModelConfigDO row);
}
