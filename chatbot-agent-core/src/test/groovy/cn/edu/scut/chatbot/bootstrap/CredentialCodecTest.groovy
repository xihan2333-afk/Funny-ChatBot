// 守护纪律：凭证引用不存明文（宪法安全纵深）；加解密三项是必测清单硬性项
package cn.edu.scut.chatbot.bootstrap

import cn.edu.scut.chatbot.exception.NonRetryableException
import spock.lang.Specification

import javax.crypto.KeyGenerator
import java.security.SecureRandom

class CredentialCodecTest extends Specification {

    def random = new SecureRandom()

    private byte[] randomKey() {
        def keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        return keyGenerator.generateKey().getEncoded()
    }

    def "加解密往返：解密结果与原文一致"() {
        given:
        def codec = new CredentialCodec(randomKey())
        def plaintext = "sk-api-token-示例凭证-" + random.nextLong()

        when:
        def packed = codec.encrypt(plaintext)
        def recovered = codec.decrypt(packed)

        then:
        recovered == plaintext
    }

    def "错误密钥解密必须失败（GCM 认证标签校验）"() {
        given: "加密用密钥 A，解密用密钥 B"
        def encryptCodec = new CredentialCodec(randomKey())
        def decryptCodec = new CredentialCodec(randomKey())

        when:
        decryptCodec.decrypt(encryptCodec.encrypt("明文"))

        then:
        thrown(NonRetryableException)
    }

    def "密文不包含明文（base64 后也搜不到）"() {
        given:
        def codec = new CredentialCodec(randomKey())
        def plaintext = "super-secret-token-1234567890"

        when:
        def packed = codec.encrypt(plaintext)

        then: "密文是 base64(IV+AES-GCM)，不含明文字节序列"
        !packed.contains(plaintext)
        !packed.contains("super-secret")
        packed.length() > plaintext.length()
    }

    def "同一明文两次加密产生不同密文（随机 IV）"() {
        given:
        def codec = new CredentialCodec(randomKey())

        when:
        def first = codec.encrypt("同一段明文")
        def second = codec.encrypt("同一段明文")

        then:
        first != second
    }

    def "字节数组入口往返：解密结果与原文一致（V6 key_cipher VARBINARY 形态）"() {
        given:
        def codec = new CredentialCodec(randomKey())
        def plaintext = "sk-byte-entry-token-" + random.nextLong()

        when:
        def packed = codec.encryptToBytes(plaintext)
        def recovered = codec.decryptFromBytes(packed)

        then:
        recovered == plaintext
        packed.length > plaintext.getBytes("UTF-8").length
    }

    def "字节数组入口错误密钥解密必须失败"() {
        given:
        def encryptCodec = new CredentialCodec(randomKey())
        def decryptCodec = new CredentialCodec(randomKey())

        when:
        decryptCodec.decryptFromBytes(encryptCodec.encryptToBytes("明文"))

        then:
        thrown(NonRetryableException)
    }

    def "字节数组入口密文不包含明文字节序列"() {
        given:
        def codec = new CredentialCodec(randomKey())
        def plaintext = "super-secret-token-1234567890"
        def plainBytes = plaintext.getBytes("UTF-8")

        when:
        def packed = codec.encryptToBytes(plaintext)

        then:
        !new String(packed, "ISO-8859-1").contains(plaintext)
        !containsSubsequence(packed, plainBytes)

        when: "短密文长度不合法直接拒绝（防截断数据）"
        codec.decryptFromBytes(new byte[5])

        then:
        thrown(NonRetryableException)
    }

    private boolean containsSubsequence(byte[] haystack, byte[] needle) {
        if (needle.length == 0) {
            return true
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean matched = true
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return true
            }
        }
        return false
    }
}
