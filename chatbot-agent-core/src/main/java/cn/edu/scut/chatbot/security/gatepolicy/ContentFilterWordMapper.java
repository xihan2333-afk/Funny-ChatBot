package cn.edu.scut.chatbot.security.gatepolicy;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * content_filter_word 表 Mapper：词库维护与两级判定查询（出站快筛数据面）
 */
@Mapper
public interface ContentFilterWordMapper {

    void insert(ContentFilterWordDO row);

    /**
     * 启用词条全量（block / review 两级由调用方按 level 分拣）
     */
    List<ContentFilterWordDO> selectEnabled();

    List<ContentFilterWordDO> selectAll();

    /**
     * 平台拒发回流：该词是否已存在（converted 补词的判重前置）
     */
    ContentFilterWordDO selectByWord(@Param("word") String word);

    void updateEnabled(@Param("id") Long id, @Param("enabled") Integer enabled);

    void deleteById(@Param("id") Long id);
}
