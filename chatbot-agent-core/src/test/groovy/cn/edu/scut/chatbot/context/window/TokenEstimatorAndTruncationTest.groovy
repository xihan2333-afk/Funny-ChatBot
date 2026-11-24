// 守护纪律：中文 token 估算宁高不低（低估即缺陷）；截断双限制先触者胜（spec US7 验收场景 6/9）
package cn.edu.scut.chatbot.context.window

import spock.lang.Specification

class TokenEstimatorAndTruncationTest extends Specification {

    def "中文估算宁高不低"() {
        expect: "纯中文：每字 1.5 token（高于主流分词器的实际用量）"
        TokenEstimator.estimate("你好世界") >= 4
        TokenEstimator.estimate("这是一段用来测试估算的中文文本") >= 15
    }

    def "中英混合与代码样本估算合理"() {
        expect:
        def mixed = TokenEstimator.estimate("hello 你好 world 世界")
        mixed > 0
        def code = TokenEstimator.estimate("public static void main(String[] args) { }")
        code >= 10
    }

    def "空与null估算为零"() {
        expect:
        TokenEstimator.estimate(null) == 0
        TokenEstimator.estimate("") == 0
    }

    def "群友消息宽松截断保头部尾部注明原文长度"() {
        given:
        def longText = "啊" * 2500

        when:
        def truncated = InputTruncator.truncateGroupMessage(longText)

        then:
        truncated.length() < longText.length()
        truncated.startsWith("啊啊")
        truncated.contains("2500")
        truncated.contains("截断")
    }

    def "单行400字符上限"() {
        given:
        def longLine = "x" * 500

        when:
        def capped = InputTruncator.lineCap(longLine)

        then:
        capped.length() < 500
        capped.contains("单行超长截断")
    }

    def "条目行数触限截断且自描述标注"() {
        given: "2100 行文本（行数触限先于字节）"
        def manyLines = (["行"] * 2100).join("\n")

        when:
        def truncated = InputTruncator.truncateEntry(manyLines)

        then:
        truncated.contains("行数触限")
    }

    def "条目字节触限截断且自描述标注"() {
        given: "多行文本（每行远小于单行上限、总量超 6KB）"
        def bigBytes = (["中文内容行数据" * 10] * 300).join("\n")

        when:
        def bytesTruncated = InputTruncator.truncateEntry(bigBytes)

        then:
        bigBytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > InputTruncator.MAX_BYTES
        bytesTruncated.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= InputTruncator.MAX_BYTES + 200
        bytesTruncated.contains("字节触限")
    }
}
