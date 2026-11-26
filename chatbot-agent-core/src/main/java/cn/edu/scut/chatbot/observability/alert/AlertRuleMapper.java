package cn.edu.scut.chatbot.observability.alert;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * alert_rule 表 Mapper：告警引擎取启用规则、管理面增改查。
 */
@Mapper
public interface AlertRuleMapper {

    List<AlertRuleDO> selectEnabled();

    List<AlertRuleDO> selectAll();

    AlertRuleDO selectById(@Param("id") long id);

    AlertRuleDO selectByName(@Param("name") String name);

    int insertRule(AlertRuleDO rule);

    int updateRule(AlertRuleDO rule);

    int deleteById(@Param("id") long id);
}
