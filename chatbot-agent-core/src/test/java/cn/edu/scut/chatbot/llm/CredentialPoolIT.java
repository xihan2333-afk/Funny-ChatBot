// 守护纪律：真表多 key 驱动轮转与反馈落库（禁 H2——GREATEST 幂等与状态迁移藏在
// 真 MySQL 行为里）；补配新 key 自然恢复无需重启（宪法第七条；spec US6 场景 1~5）
package cn.edu.scut.chatbot.llm;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.llm.slot.CredentialPool;
import cn.edu.scut.chatbot.llm.slot.JdbcCredentialPool;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭证池真表集成：多 key 轮转、429 反馈落冷却（cooldown_until 置位、并发幂等取更晚）、
 * 401 停用终态、补配新 available key 后下次解析成功自然恢复。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class CredentialPoolIT extends AbstractIntegrationTest {

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private JdbcCredentialPool pool;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CredentialCodec codec;

    @BeforeAll
    void seed() {
        codec = CredentialCodec.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        jdbcTemplate.update("DELETE FROM credential WHERE owner_kind = 'provider'");
        insertKey("sk-it-zai-0001");
        insertKey("sk-it-zai-0002");
        insertKey("sk-it-zai-0003");
    }

    private void insertKey(String plaintext) {
        CredentialDO row = new CredentialDO();
        row.setOwnerKind("provider");
        row.setOwnerCode("zai");
        row.setKeyCipher(codec.encryptToBytes(plaintext));
        row.setKeyMask(CredentialPool.maskOf(plaintext));
        row.setStatus("available");
        credentialMapper.insertCredential(row);
    }

    @Test
    @Order(1)
    void 真表三key轮转_一周内三把全出现() {
        Set<String> taken = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> pool.nextAvailable("zai"))
                .collect(Collectors.toSet());
        assertThat(taken).containsExactlyInAnyOrder(
                "sk-it-zai-0001", "sk-it-zai-0002", "sk-it-zai-0003");
    }

    @Test
    @Order(2)
    void 限流反馈落冷却_并发反馈幂等取更晚() {
        pool.feedback("zai", CredentialPool.maskOf("sk-it-zai-0001"),
                CredentialPool.FeedbackKind.RATE_LIMITED);
        // 并发反馈同把 key（重试风暴形态）——GREATEST 取更晚者，不缩短
        pool.feedback("zai", CredentialPool.maskOf("sk-it-zai-0001"),
                CredentialPool.FeedbackKind.RATE_LIMITED);

        var row = jdbcTemplate.queryForMap(
                "SELECT status, cooldown_until FROM credential WHERE owner_code = 'zai' "
                        + "AND key_mask = '" + CredentialPool.maskOf("sk-it-zai-0001") + "'");
        assertThat(row.get("status")).isEqualTo("cooldown");
        assertThat((java.time.LocalDateTime) row.get("cooldown_until"))
                .isAfter(java.time.LocalDateTime.now().plusSeconds(30));

        // 冷却中的 key 不进可用序列：连续取 5 把，不出现 0001
        Set<String> taken = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> pool.nextAvailable("zai"))
                .collect(Collectors.toSet());
        assertThat(taken).doesNotContain("sk-it-zai-0001");
    }

    @Test
    @Order(3)
    void 鉴权失败停用终态_永不回序列() {
        pool.feedback("zai", CredentialPool.maskOf("sk-it-zai-0002"),
                CredentialPool.FeedbackKind.AUTH_FAILED);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM credential WHERE owner_code = 'zai' "
                        + "AND key_mask = '" + CredentialPool.maskOf("sk-it-zai-0002") + "'",
                String.class);
        assertThat(status).isEqualTo("disabled");

        Set<String> taken = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> pool.nextAvailable("zai"))
                .collect(Collectors.toSet());
        assertThat(taken).doesNotContain("sk-it-zai-0002");
    }

    @Test
    @Order(4)
    void 全部不可用返回null_补配新key自然恢复无需重启() {
        // key1 冷却、key2 停用后把 key3 也停用——全空
        pool.feedback("zai", CredentialPool.maskOf("sk-it-zai-0003"),
                CredentialPool.FeedbackKind.AUTH_FAILED);
        assertThat(pool.nextAvailable("zai")).isNull();

        // 补配新 available key——下次解析直接成功（不重启不清缓存）
        insertKey("sk-it-zai-0004-fresh");
        assertThat(pool.nextAvailable("zai")).isEqualTo("sk-it-zai-0004-fresh");
    }
}
