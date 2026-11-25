package cn.edu.scut.chatbot.memory.persona;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * persona 表 + content_version 的 persona 快照读写 Mapper（T034 自 PromptFragmentMapper 承接）：
 * 人格实体的表读写归 memory/persona 域，版本快照同点持有
 * （领域包各自持 Mapper 是既定形态——同表多 Mapper 以 entityKind 隔离职责边界）。
 */
@Mapper
public interface PersonaMapper {

    // ===== persona =====

    PersonaDO selectDefaultPersona();

    PersonaDO selectPersonaByName(@Param("name") String name);

    List<PersonaDO> selectAllPersonas();

    int insertPersona(PersonaDO persona);

    int updatePersona(PersonaDO persona);

    // ===== content_version（entityKind 恒为 persona——不引 prompt 域 DO，快照以原始 JSON 往返） =====

    /**
     * 写入人格版本快照（发布 = 主表快照入版本表）
     */
    int insertPersonaVersion(@Param("entityId") long entityId,
                             @Param("versionNo") int versionNo,
                             @Param("snapshot") String snapshot);

    /**
     * 指定版本的人格快照 JSON（回滚 = 旧快照写回主表；不存在返回 null）
     */
    String selectPersonaSnapshot(@Param("entityId") long entityId,
                                 @Param("versionNo") int versionNo);

    /**
     * 人格实体最大版本号（发布递增用；无历史返回 null）
     */
    Integer selectMaxPersonaVersion(@Param("entityId") long entityId);
}
