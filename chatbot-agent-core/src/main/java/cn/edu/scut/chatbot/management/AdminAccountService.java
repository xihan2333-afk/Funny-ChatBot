package cn.edu.scut.chatbot.management;

import java.util.Optional;

/**
 * 管理平台单账号认证门面（admin-auth 契约第 1 节，T083）：
 * BCrypt 校验 + Redis 会话（TTL 默认 12 小时可配带续期上限）+ must_change 强制改密 +
 * 改密全端下线 + 全量 security_event(adminOperate) 审计。
 *
 * <p>收口纪律：接口与视图 record 在 management 包，实现在 security/credential 包
 * 触达 AdminAccountMapper，满足 ArchUnit management 不直触 DO/Mapper 规约。
 */
public interface AdminAccountService {

    /**
     * 登录：BCrypt 校验通过签发会话 token 写 Redis；失败累加防爆破计数并记审计
     */
    LoginResult login(String username, String password);

    /**
     * 校验会话 token 并按需续期（不超过最大续期上限）；无效或过期返回 empty
     */
    Optional<SessionPrincipal> verifySession(String token);

    /**
     * 登出：销毁指定会话 token 并记审计
     */
    void logout(String token);

    /**
     * 修改口令：必须校验原口令，更新 BCrypt 哈希，置 must_change=0，并删除该账号全部活跃会话（全端下线）
     */
    void changePassword(String oldRaw, String newRaw);

    /**
     * 当前单账号是否仍处于首次强制改密态（must_change=1）
     */
    boolean mustChangePending();

    record LoginResult(String token, String username, boolean mustChange, long expiresAtEpochMillis) {
    }

    record SessionPrincipal(String token, String username, long issuedAtEpochMillis, boolean mustChange) {
    }
}
