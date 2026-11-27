package cn.edu.scut.chatbot.llm.slot;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * model_slot 表 Mapper（全量装载——ModelCatalog 快照重建用）。
 */
@Mapper
public interface ModelSlotMapper {

    List<ModelSlotDO> selectAll();

    int insert(ModelSlotDO row);

    int upsert(ModelSlotDO row);
}
