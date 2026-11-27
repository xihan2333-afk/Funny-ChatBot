package cn.edu.scut.chatbot.security.credential;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * admin_account 表 DO（V8 迁移，data-model 2.6）：管理平台单账号——
 * 只存用户名、BCrypt 哈希与首次强制改密标记（must_change）；会话存 Redis。
 */
@Data
public class AdminAccountDO {

    private Integer id;

    private String username;

    private String passwordHash;

    /**
     * 1 = 首次登录强制改密（未改前除登录与改密外全接口 403 拒绝）；0 = 正常
     */
    private Integer mustChange;

    private LocalDateTime updatedAt;
}
