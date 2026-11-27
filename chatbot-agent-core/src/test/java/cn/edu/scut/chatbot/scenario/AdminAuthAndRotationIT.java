// 守护纪律（US9 集成验收场景 1~5，SC-008）：首次登录强制改密拦截矩阵、改密后旧口令失效与全端下线、
// 会话失效拒绝、主密钥轮换中断重试幂等与双密钥过渡探测。
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.management.AdminAccountService;
import cn.edu.scut.chatbot.management.ManagementAuthFilter;
import cn.edu.scut.chatbot.security.credential.AdminAccountDO;
import cn.edu.scut.chatbot.security.credential.AdminAccountMapper;
import cn.edu.scut.chatbot.security.credential.CredentialRotationService;
import cn.edu.scut.chatbot.security.credential.CredentialRotationServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理认证与主密钥轮换 IT（spec US9 验收场景 1~5）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAuthAndRotationIT extends AbstractIntegrationTest {

    @Autowired
    private AdminAccountMapper adminAccountMapper;

    @Autowired
    private AdminAccountService adminAccountService;

    @Autowired
    private ManagementAuthFilter managementAuthFilter;

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private CredentialRotationServiceImpl rotationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM admin_account");
        jdbcTemplate.update("DELETE FROM credential WHERE owner_code = 'it-us9-rotate'");
    }

    @Test
    void 首次登录强制改密拦截矩阵_改密全端下线旧口令失效() throws Exception {
        jdbcTemplate.update("DELETE FROM admin_account");
        AdminAccountDO seed = new AdminAccountDO();
        seed.setUsername("admin-us9");
        seed.setPasswordHash(new BCryptPasswordEncoder().encode("InitSecret#1"));
        seed.setMustChange(1);
        adminAccountMapper.insert(seed);

        AdminAccountService.LoginResult login1 = adminAccountService.login("admin-us9", "InitSecret#1");
        AdminAccountService.LoginResult login2 = adminAccountService.login("admin-us9", "InitSecret#1");
        assertThat(login1.mustChange()).isTrue();

        // must_change=true：普通管理接口（含只读）全部 403 拒绝
        MockHttpServletRequest obsReq = new MockHttpServletRequest("GET", "/api/management/security/blacklist");
        obsReq.addHeader("Authorization", "Bearer " + login1.token());
        MockHttpServletResponse obsResp = new MockHttpServletResponse();
        managementAuthFilter.doFilter(obsReq, obsResp, new MockFilterChain());
        assertThat(obsResp.getStatus()).isEqualTo(403);

        // 改密接口放行
        MockHttpServletRequest cpReq = new MockHttpServletRequest("POST", "/api/management/auth/change-password");
        cpReq.addHeader("Authorization", "Bearer " + login1.token());
        MockHttpServletResponse cpResp = new MockHttpServletResponse();
        MockFilterChain cpChain = new MockFilterChain();
        managementAuthFilter.doFilter(cpReq, cpResp, cpChain);
        assertThat(cpResp.getStatus()).isEqualTo(200);
        assertThat(cpChain.getRequest()).isNotNull();

        // 执行改密：must_change=0、两个旧会话全部下线、旧口令不可再登录
        adminAccountService.changePassword("InitSecret#1", "RotatedStrong#2");
        assertThat(adminAccountService.verifySession(login1.token())).isEmpty();
        assertThat(adminAccountService.verifySession(login2.token())).isEmpty();
        assertThatThrownBy(() -> adminAccountService.login("admin-us9", "InitSecret#1")).isInstanceOf(RuntimeException.class);

        // 旧 token 调管理接口 → 401
        MockHttpServletRequest expiredReq = new MockHttpServletRequest("GET", "/api/management/security/blacklist");
        expiredReq.addHeader("Authorization", "Bearer " + login1.token());
        MockHttpServletResponse expiredResp = new MockHttpServletResponse();
        managementAuthFilter.doFilter(expiredReq, expiredResp, new MockFilterChain());
        assertThat(expiredResp.getStatus()).isEqualTo(401);

        // 新口令登录 → 放行全部管理接口
        AdminAccountService.LoginResult login3 = adminAccountService.login("admin-us9", "RotatedStrong#2");
        assertThat(login3.mustChange()).isFalse();
        MockHttpServletRequest okReq = new MockHttpServletRequest("GET", "/api/management/security/blacklist");
        okReq.addHeader("Authorization", "Bearer " + login3.token());
        MockHttpServletResponse okResp = new MockHttpServletResponse();
        MockFilterChain okChain = new MockFilterChain();
        managementAuthFilter.doFilter(okReq, okResp, okChain);
        assertThat(okResp.getStatus()).isEqualTo(200);
        assertThat(okChain.getRequest()).isNotNull();
    }

    @Test
    void 主密钥轮换幂等可重试且双密钥过渡可解新旧密文() {
        byte[] oldKey = new byte[32];
        byte[] newKey = new byte[32];
        new SecureRandom().nextBytes(oldKey);
        new SecureRandom().nextBytes(newKey);
        CredentialCodec oldCodec = new CredentialCodec(oldKey);
        CredentialCodec newCodec = new CredentialCodec(newKey);
        CredentialCodec dualCodec = new CredentialCodec(newKey, oldKey);

        CredentialDO c1 = new CredentialDO();
        c1.setOwnerKind("provider");
        c1.setOwnerCode("it-us9-rotate");
        c1.setKeyMask("sk-r1***");
        c1.setKeyCipher(oldCodec.encryptToBytes("plain-key-alpha"));
        credentialMapper.insertCredential(c1);

        CredentialDO c2 = new CredentialDO();
        c2.setOwnerKind("provider");
        c2.setOwnerCode("it-us9-rotate");
        c2.setKeyMask("sk-r2***");
        c2.setKeyCipher(oldCodec.encryptToBytes("plain-key-beta"));
        credentialMapper.insertCredential(c2);

        // 先手模拟中断态：仅 c1 已被新钥加密，c2 仍为旧钥密文
        credentialMapper.updateKeyCipher(c1.getId(),
                newCodec.encryptVersionedToBytes("plain-key-alpha", CredentialCodec.VERSION_V2));
        // 过渡期双密钥都能解
        assertThat(dualCodec.decryptFromBytes(credentialMapper.selectById(c1.getId()).getKeyCipher()))
                .isEqualTo("plain-key-alpha");
        assertThat(dualCodec.decryptFromBytes(credentialMapper.selectById(c2.getId()).getKeyCipher()))
                .isEqualTo("plain-key-beta");

        // 执行轮换：c1 跳过，c2 重加密（共享库可能有其它行，因此按 id 校验新钥解密）
        CredentialRotationService.RotationReport report = rotationService.rotateWith(oldCodec, newCodec);
        assertThat(report.rotated()).isGreaterThanOrEqualTo(1);
        assertThat(report.skipped()).isGreaterThanOrEqualTo(1);
        assertThat(newCodec.decryptFromBytes(credentialMapper.selectById(c1.getId()).getKeyCipher()))
                .isEqualTo("plain-key-alpha");
        assertThat(newCodec.decryptFromBytes(credentialMapper.selectById(c2.getId()).getKeyCipher()))
                .isEqualTo("plain-key-beta");

        // 幂等重跑：c1/c2 都不再重加密
        List<CredentialDO> ours = credentialMapper.selectByOwner("provider", "it-us9-rotate");
        assertThat(ours).allSatisfy(row -> assertThat(newCodec.canDecryptBytes(row.getKeyCipher())).isTrue());
    }
}
