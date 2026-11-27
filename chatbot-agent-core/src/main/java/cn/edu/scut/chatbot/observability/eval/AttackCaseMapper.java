package cn.edu.scut.chatbot.observability.eval;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * attack_case 表 Mapper（T095）：注入对抗用例库 CRUD、按通道查询与最近结论回填。
 */
@Mapper
public interface AttackCaseMapper {

    int insert(AttackCaseDO row);

    AttackCaseDO selectById(@Param("id") long id);

    List<AttackCaseDO> selectAll();

    List<AttackCaseDO> selectEnabled();

    List<AttackCaseDO> selectByChannel(@Param("channel") String channel);

    int updateVerdict(@Param("id") long id, @Param("lastVerdict") String lastVerdict);

    int updateCase(AttackCaseDO row);

    int updateEnabled(@Param("id") long id, @Param("enabled") Integer enabled);

    int deleteById(@Param("id") long id);
}
