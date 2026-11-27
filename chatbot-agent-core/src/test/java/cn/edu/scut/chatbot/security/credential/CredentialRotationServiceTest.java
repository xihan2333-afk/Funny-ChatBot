// 守护纪律：主密钥轮换幂等重加密与双密钥过渡探测（admin-auth 契约第 2 节，R17、FR-020）
package cn.edu.scut.chatbot.security.credential;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CredentialRotationServiceTest {

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void 轮换重加密幂等_中断重跑跳过已处理行且双密钥过渡可解新旧密文() {
        byte[] oldKey = randomKey();
        byte[] newKey = randomKey();
        CredentialCodec oldCodec = new CredentialCodec(oldKey);
        CredentialCodec newCodec = new CredentialCodec(newKey);
        CredentialCodec dualCodec = new CredentialCodec(newKey, oldKey);

        CredentialDO row1 = new CredentialDO();
        row1.setId(1L);
        row1.setKeyMask("sk-1***");
        row1.setKeyCipher(oldCodec.encryptToBytes("sk-secret-one"));

        CredentialDO row2 = new CredentialDO();
        row2.setId(2L);
        row2.setKeyMask("sk-2***");
        row2.setKeyCipher(oldCodec.encryptToBytes("sk-secret-two"));

        // 模拟中断前 row1 已轮换、row2 仍为旧密文
        CredentialDO row1AlreadyRotated = new CredentialDO();
        row1AlreadyRotated.setId(1L);
        row1AlreadyRotated.setKeyMask("sk-1***");
        row1AlreadyRotated.setKeyCipher(newCodec.encryptVersionedToBytes("sk-secret-one", CredentialCodec.VERSION_V2));

        // 过渡期双密钥探测：新密文与旧密文都可无感解密
        assertEquals("sk-secret-one", dualCodec.decryptFromBytes(row1AlreadyRotated.getKeyCipher()));
        assertEquals("sk-secret-two", dualCodec.decryptFromBytes(row2.getKeyCipher()));

        CredentialMapper mapper = mock(CredentialMapper.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        when(mapper.selectAll()).thenReturn(List.of(row1AlreadyRotated, row2));
        doAnswer(inv -> {
            long id = inv.getArgument(0);
            byte[] cipher = inv.getArgument(1);
            if (id == 2L) {
                row2.setKeyCipher(cipher);
            }
            return 1;
        }).when(mapper).updateKeyCipher(anyLong(), any(byte[].class));

        CredentialRotationServiceImpl rotation = new CredentialRotationServiceImpl(
                mapper, Optional.of(oldCodec), recorder, Optional.empty());

        // 第一次补跑：row1 幂等跳过，row2 重加密
        CredentialRotationService.RotationReport first = rotation.rotate(newKey);
        assertEquals(2, first.total());
        assertEquals(1, first.rotated());
        assertEquals(1, first.skipped());
        assertEquals(0, first.failed());
        assertEquals("sk-secret-two", newCodec.decryptFromBytes(row2.getKeyCipher()));

        // 第二次重跑：全部跳过，零更新
        CredentialRotationService.RotationReport second = rotation.rotate(newKey);
        assertEquals(0, second.rotated());
        assertEquals(2, second.skipped());
        assertEquals(0, second.failed());

        verify(recorder, times(2)).record(eq(SecurityEventKind.ADMIN_OPERATE), any(), any(), isNull());
    }
}
