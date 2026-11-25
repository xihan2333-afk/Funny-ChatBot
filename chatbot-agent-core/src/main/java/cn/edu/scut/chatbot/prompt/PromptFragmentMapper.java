package cn.edu.scut.chatbot.prompt;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * prompt_fragment / content_version 表 Mapper。
 * persona 表读写已迁 memory/persona/PersonaMapper（T034——人格实体归位，
 * 依赖方向 prompt → memory）。
 */
@Mapper
public interface PromptFragmentMapper {

    List<PromptFragmentDO> selectPublishedByLayer(@Param("layer") String layer);

    PromptFragmentDO selectByKey(@Param("fragmentKey") String fragmentKey);

    int insertFragment(PromptFragmentDO fragment);

    int updateFragment(PromptFragmentDO fragment);

    int deleteByKey(@Param("fragmentKey") String fragmentKey);

    // ===== content_version =====

    int insertVersion(ContentVersionDO version);

    ContentVersionDO selectVersion(@Param("entityKind") String entityKind,
                                   @Param("entityId") long entityId,
                                   @Param("versionNo") int versionNo);

    List<ContentVersionDO> selectVersions(@Param("entityKind") String entityKind,
                                          @Param("entityId") long entityId);

    /**
     * 实体最大版本号（发布递增用；无历史返回 0）
     */
    Integer selectMaxVersion(@Param("entityKind") String entityKind, @Param("entityId") long entityId);
}
