package cn.edu.scut.chatbot.skill;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * skill 表 Mapper。
 */
@Mapper
public interface SkillMapper {

    List<SkillDO> selectAll();

    SkillDO selectByName(@Param("name") String name);

    int insertSkill(SkillDO skill);

    int updateSkill(SkillDO skill);

    int deleteByName(@Param("name") String name);
}
