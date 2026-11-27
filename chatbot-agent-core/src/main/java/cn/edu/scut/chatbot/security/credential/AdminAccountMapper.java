package cn.edu.scut.chatbot.security.credential;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * admin_account 表 Mapper（单账号：按用户名查、首次初始化插入、改密更新哈希并清 must_change）。
 */
@Mapper
public interface AdminAccountMapper {

    long countAll();

    AdminAccountDO selectByUsername(@Param("username") String username);

    AdminAccountDO selectPrimary();

    int insert(AdminAccountDO account);

    int updatePassword(@Param("id") Integer id,
                       @Param("passwordHash") String passwordHash,
                       @Param("mustChange") Integer mustChange);
}
