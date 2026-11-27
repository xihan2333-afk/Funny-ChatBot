package cn.edu.scut.chatbot.security.credential;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CredentialRotationLogMapper {

    int insert(CredentialRotationLogDO row);

    int updateFinished(CredentialRotationLogDO row);

    List<CredentialRotationLogDO> selectAllPaged(@Param("offset") int offset, @Param("limit") int limit);

    long countAll();
}
