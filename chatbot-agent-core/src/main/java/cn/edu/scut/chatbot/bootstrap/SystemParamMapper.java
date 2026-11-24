package cn.edu.scut.chatbot.bootstrap;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * system_param 表 Mapper（原生 MyBatis：接口 + XML，接口上不写注解 SQL）。
 */
@Mapper
public interface SystemParamMapper {

    /**
     * 全量装载（参数表行数有限，整表进内存缓存）
     */
    List<SystemParamDO> selectAll();

    /**
     * 单键 UPSERT：管理面写参数（热变更入口）
     */
    int upsertParam(SystemParamDO param);
}
