// 守护纪律：多 key 轮转、反馈式冷却到期恢复（固定时钟拨动）、鉴权停用终态、
// 全部不可用返回 null、脱敏（日志无明文无密文——宪法第七条 + 宪法「时间与 ID」节；
// contracts/slot-and-credentials.md 第 3 节 + data-model 第 9 节状态迁移）
package cn.edu.scut.chatbot.llm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cn.edu.scut.chatbot.bootstrap.CredentialCodec
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.llm.slot.CredentialDO
import cn.edu.scut.chatbot.llm.slot.CredentialMapper
import cn.edu.scut.chatbot.llm.slot.CredentialPool
import cn.edu.scut.chatbot.llm.slot.JdbcCredentialPool
import org.slf4j.LoggerFactory
import spock.lang.Specification

import java.time.LocalDateTime
import java.time.ZoneId

class CredentialPoolTest extends Specification {

    def credentialMapper = Mock(CredentialMapper)
    def clock = new FixedClock()
    def params = Mock(SystemParamService) {
        getLong(_, _) >> 60L
    }
    def codec = CredentialCodec.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]))
    def pool = new JdbcCredentialPool(credentialMapper, Optional.of(codec), params, clock)

    def nowLocal() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
    }

    def credentialRow(long id, String plaintext, String status = "available",
                      LocalDateTime cooldownUntil = null) {
        def row = new CredentialDO()
        row.setId(id)
        row.setOwnerKind("provider")
        row.setOwnerCode("zai")
        row.setKeyCipher(codec.encryptToBytes(plaintext))
        row.setKeyMask(CredentialPool.maskOf(plaintext))
        row.setStatus(status)
        row.setCooldownUntil(cooldownUntil)
        return row
    }

    def "三把 key 按 id 轮转 1→2→3→1"() {
        given:
        credentialMapper.selectByOwner("provider", "zai") >> [
                credentialRow(1, "sk-live-zai-0001"),
                credentialRow(2, "sk-live-zai-0002"),
                credentialRow(3, "sk-live-zai-0003")]

        expect:
        pool.nextAvailable("zai") == "sk-live-zai-0001"
        pool.nextAvailable("zai") == "sk-live-zai-0002"
        pool.nextAvailable("zai") == "sk-live-zai-0003"
        pool.nextAvailable("zai") == "sk-live-zai-0001"
    }

    def "key2 冷却中跳过（1→3→1）、固定时钟推进到期后回到全序列"() {
        given:
        credentialMapper.selectByOwner("provider", "zai") >> [
                credentialRow(1, "sk-live-zai-0001"),
                credentialRow(2, "sk-live-zai-0002", "cooldown", nowLocal().plusSeconds(30)),
                credentialRow(3, "sk-live-zai-0003")]

        expect: "冷却未到期被跳过——轮转在可用序列上进行"
        pool.nextAvailable("zai") == "sk-live-zai-0001"
        pool.nextAvailable("zai") == "sk-live-zai-0003"
        pool.nextAvailable("zai") == "sk-live-zai-0001"

        when: "拨钟快进 31 秒——到期即视作恢复（不依赖后台任务复位）"
        clock.advanceMillis(31_000)

        then: "全序列恢复——key2 回到轮转（指针连续，一周内必出现）"
        def sequence = (1..3).collect { pool.nextAvailable("zai") }
        sequence.contains("sk-live-zai-0002")
    }

    def "auth 反馈停用终态——disabled 永不回序列"() {
        given: "调用序：feedback 定位一次；停用落库后的表读两次"
        def beforeDisable = [credentialRow(1, "sk-live-zai-0001"),
                             credentialRow(2, "sk-live-zai-0002", "disabled")]
        def afterDisable = [credentialRow(1, "sk-live-zai-0001", "disabled"),
                            credentialRow(2, "sk-live-zai-0002")]
        credentialMapper.selectByOwner("provider", "zai") >>>
                [beforeDisable, afterDisable, afterDisable]

        when: "停用反馈（终态）"
        pool.feedback("zai", "sk-***001", CredentialPool.FeedbackKind.AUTH_FAILED)

        then:
        1 * credentialMapper.markDisabled(1L)

        expect: "disabled 行不进可用序列（表内只剩 key2 可用——轮转恒取它）"
        pool.nextAvailable("zai") == "sk-live-zai-0002"
        pool.nextAvailable("zai") == "sk-live-zai-0002"
    }

    def "限流反馈按 system_param 时长置冷却"() {
        given:
        credentialMapper.selectByOwner("provider", "zai") >> [credentialRow(7, "sk-live-zai-0007")]

        when:
        pool.feedback("zai", "sk-***007", CredentialPool.FeedbackKind.RATE_LIMITED)

        then: "缺省 60 秒（Mock getLong 双参缺省值语义由 IT 验真表）"
        1 * credentialMapper.markCooldownLater(7L, nowLocal().plusSeconds(60))
    }

    def "全部冷却或停用 → null（无凭证转错误结果、Effects 重试）"() {
        given:
        credentialMapper.selectByOwner("provider", "zai") >> [
                credentialRow(1, "sk-live-zai-0001", "cooldown", nowLocal().plusSeconds(300)),
                credentialRow(2, "sk-live-zai-0002", "disabled")]

        expect:
        pool.nextAvailable("zai") == null

        when: "无行同样 null"
        credentialMapper.selectByOwner("provider", "empty") >> []
        def result = pool.nextAvailable("empty")

        then:
        result == null
    }

    def "解密失败按能力不可用降级——不抛明文不抛异常"() {
        given: "密文被篡改的行"
        def broken = credentialRow(1, "sk-live-zai-0001")
        broken.setKeyCipher(new byte[20])
        credentialMapper.selectByOwner("provider", "zai") >> [broken]

        when:
        def result = pool.nextAvailable("zai")

        then:
        result == null
        noExceptionThrown()
    }

    def "脱敏纪律：反馈路径日志只暴露 key_mask——无明文无密文"() {
        given:
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        def poolLogger = (Logger) LoggerFactory.getLogger(JdbcCredentialPool.class)
        poolLogger.addAppender(appender)
        credentialMapper.selectByOwner("provider", "zai") >> [credentialRow(9, "sk-live-zai-0009")]

        when:
        pool.feedback("zai", "sk-***009", CredentialPool.FeedbackKind.AUTH_FAILED)

        then:
        appender.list.any { it.level == Level.ERROR && it.formattedMessage.contains("sk-***009") }
        appender.list.every { !it.formattedMessage.contains("sk-live-zai-0009") }

        cleanup:
        poolLogger.detachAppender(appender)
    }

    def "加密密钥未配置（codec 缺席）——能力不可用返回 null"() {
        given:
        def barePool = new JdbcCredentialPool(credentialMapper, Optional.empty(), params, clock)

        expect:
        barePool.nextAvailable("zai") == null
    }
}
