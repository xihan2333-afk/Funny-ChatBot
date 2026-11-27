package cn.edu.scut.chatbot.memory.persona;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PersonaAdminFeedbackMapper {

    int insert(PersonaAdminFeedbackDO row);

    List<PersonaAdminFeedbackDO> selectByPersonaSince(@Param("personaId") long personaId,
                                                      @Param("since") LocalDateTime since);

    List<PersonaAdminFeedbackDO> selectByPersona(@Param("personaId") long personaId);
}
