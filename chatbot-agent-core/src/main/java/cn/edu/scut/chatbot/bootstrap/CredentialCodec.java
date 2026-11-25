package cn.edu.scut.chatbot.bootstrap;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.tool.ToolErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证加解密组件（credential 表的读写配套）：AES-256-GCM。
 *
 * <p>密文形态：base64(12 字节随机 IV + 密文 + 128 位认证标签)。每次加密 IV 随机——
 * 同一明文两次加密产生不同密文（GCM 的 nonce 复用会灾难性破坏安全性，随机 IV 是硬要求）。
 *
 * <p>密钥来源：部署层配置（.env 的 CHATBOT_CREDENTIAL_KEY，base64 编码的 32 字节），
 * 不入库不入仓——凭证引用不存明文、密钥与密文分离存放是安全底线。
 *
 * <p>硬性三项必测（宪法必测清单）：加解密往返、错误密钥解密失败、密文不含明文。
 */
public class CredentialCodec {

    private static final Logger log = LoggerFactory.getLogger(CredentialCodec.class);

    /**
     * AES 密钥字节数（256 位）
     */
    private static final int KEY_BYTES = 32;

    /**
     * GCM IV 字节数（12 字节是 NIST 推荐值）
     */
    private static final int IV_BYTES = 12;

    /**
     * GCM 认证标签位数（128 位）
     */
    private static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;

    private final SecureRandom random = new SecureRandom();

    /**
     * @param keyBytes 32 字节密钥（base64 解码后的原始字节）
     */
    public CredentialCodec(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "凭证密钥必须是 " + KEY_BYTES + " 字节（AES-256），当前 " + (keyBytes == null ? 0 : keyBytes.length) + " 字节");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 从 base64 配置串构造（BootstrapConfiguration 装配入口）
     */
    public static CredentialCodec fromBase64Key(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        return new CredentialCodec(keyBytes);
    }

    /**
     * 加密：随机 IV + GCM，输出 base64(IV + 密文 + tag)
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherBytes, 0, packed, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new NonRetryableException(ToolErrorCodes.CREDENTIAL_DECRYPT_FAILED,
                    "凭证加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解密：错误密钥或密文被篡改时 GCM 认证失败（AEADBadTagException），
     * 归一化为 NonRetryableException（数据问题重试无意义）
     */
    public String decrypt(String packedBase64) {
        try {
            byte[] packed = Base64.getDecoder().decode(packedBase64);
            return decryptPacked(packed);
        } catch (NonRetryableException e) {
            throw e;
        } catch (Exception e) {
            // 典型形态 AEADBadTagException：密钥不匹配或密文被改——密钥侧问题暴露给运维
            log.error("凭证解密失败 errorCode={}（密钥不匹配或密文损坏）", ToolErrorCodes.CREDENTIAL_DECRYPT_FAILED);
            throw new NonRetryableException(ToolErrorCodes.CREDENTIAL_DECRYPT_FAILED,
                    "凭证解密失败：密钥不匹配或密文损坏", e);
        }
    }

    /**
     * 加密（字节数组入口，V6 credential.key_cipher VARBINARY 形态）：
     * 输出 [12 字节随机 IV | 密文 + 16 字节 tag] 原始字节，无 base64 包装
     */
    public byte[] encryptToBytes(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherBytes, 0, packed, iv.length, cipherBytes.length);
            return packed;
        } catch (Exception e) {
            throw new NonRetryableException(ToolErrorCodes.CREDENTIAL_DECRYPT_FAILED,
                    "凭证加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解密（字节数组入口——key_cipher 列直读形态）
     */
    public String decryptFromBytes(byte[] packed) {
        try {
            return decryptPacked(packed);
        } catch (NonRetryableException e) {
            throw e;
        } catch (Exception e) {
            log.error("凭证解密失败 errorCode={}（密钥不匹配或密文损坏）", ToolErrorCodes.CREDENTIAL_DECRYPT_FAILED);
            throw new NonRetryableException(ToolErrorCodes.CREDENTIAL_DECRYPT_FAILED,
                    "凭证解密失败：密钥不匹配或密文损坏", e);
        }
    }

    private String decryptPacked(byte[] packed) throws Exception {
        if (packed == null || packed.length <= IV_BYTES) {
            throw new NonRetryableException(ToolErrorCodes.CREDENTIAL_DECRYPT_FAILED,
                    "凭证密文长度不合法（不足 IV + tag），可能数据损坏");
        }
        GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        byte[] plain = cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
        return new String(plain, StandardCharsets.UTF_8);
    }
}
