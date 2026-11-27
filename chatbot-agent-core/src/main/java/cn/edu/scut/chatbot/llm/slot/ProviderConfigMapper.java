package cn.edu.scut.chatbot.llm.slot;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * provider_config 表 Mapper（全量装载——ModelCatalog 快照重建用）。
 */
@Mapper
public interface ProviderConfigMapper {

    List<ProviderConfigDO> selectAll();

    int insert(ProviderConfigDO row);

    int update(ProviderConfigDO row);
}
