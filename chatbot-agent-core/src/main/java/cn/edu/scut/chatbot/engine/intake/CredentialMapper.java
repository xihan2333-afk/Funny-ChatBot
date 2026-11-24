package cn.edu.scut.chatbot.engine.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * credential 表 Mapper。
 */
@Mapper
public interface CredentialMapper {

    CredentialDO selectByName(@Param("name") String name);

    CredentialDO selectById(@Param("id") long id);

    int insertCredential(CredentialDO credential);
}
