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

    /**
     * 各供应商的可用 key 数（告警探针：凭证全冷却判定——available=0 即命中；
     * 条件计数保证全冷却的供应商也出现在结果里，值为 0）
     */
    List<OwnerAvailableCount> countAvailableByProvider();

    /**
     * 全量凭证行（主密钥轮换运维工具遍历对象，T085）
     */
    List<CredentialDO> selectAll();

    /**
     * 更新单行密文（主密钥轮换按行事务写回，T085）
     */
    int updateKeyCipher(@Param("id") long id, @Param("keyCipher") byte[] keyCipher);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int deleteById(@Param("id") long id);
}
