package cn.edu.scut.chatbot.llm.slot;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * credential 表 Mapper（V6 重建形态：owner_kind 三归属）。
 */
@Mapper
public interface CredentialMapper {

    CredentialDO selectById(@Param("id") long id);

    /**
     * 取某归属的全部凭证行（key 池轮转与冷却过滤在池实现侧）
     */
    List<CredentialDO> selectByOwner(@Param("ownerKind") String ownerKind,
                                     @Param("ownerCode") String ownerCode);

    int insertCredential(CredentialDO credential);

    /**
     * 限流冷却：取更晚者（并发反馈幂等）；disabled 行不再冷却（终态优先）
     */
    int markCooldownLater(@Param("id") long id, @Param("cooldownUntil") LocalDateTime cooldownUntil);

    /**
     * 鉴权失败停用（终态——补配新 key 而非复活）
     */
    int markDisabled(@Param("id") long id);
}
