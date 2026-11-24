// 守护纪律：URL 是压缩信息截断即销毁——单行截断时被丢掉的 URL 追加保留（宁可越界）
package cn.edu.scut.chatbot.context.window

import spock.lang.Specification

class InputTruncatorUrlExemptionTest extends Specification {

    def "单行截断丢掉的URL追加保留（豁免越界）"() {
        given: "400 字符正文 + 一条落在截断点之外的 URL"
        def url = "https://example.com/docs/very/long/path?chapter=12&section=3"
        def line = "前" * 400 + " 详见 " + url

        when:
        def capped = InputTruncator.lineCap(line)

        then: "保头 400 字符 + 截断标注 + URL 完整保留在尾部"
        capped.startsWith("前" * 400)
        capped.contains("…[单行超长截断]")
        capped.endsWith(url)
        and: "截断行总长仍受控（URL 豁免只豁免信息不豁免可读性上限的量级）"
        capped.length() < line.length() + InputTruncator.MAX_LINE_CHARS
    }

    def "URL已在保留头部内不重复追加"() {
        given: "URL 位于行首（在前 400 字符内），后接超长正文"
        def url = "https://example.com/page"
        def line = url + "后" * 500

        when:
        def capped = InputTruncator.lineCap(line)

        then:
        capped.count(url) == 1
        capped.contains("…[单行超长截断]")
    }

    def "多行文本逐行截断 豁免对各行独立生效"() {
        given: "第一行短而不动；第二行超长且 URL 在尾部"
        def url = "https://example.com/reference?id=9527"
        def text = "第一行短内容\n" + "正" * 450 + " " + url

        when:
        def capped = InputTruncator.lineCap(text)

        then:
        capped.startsWith("第一行短内容\n")
        capped.contains("…[单行超长截断]")
        capped.endsWith(url)
    }

    def "行内多个被丢URL都保留"() {
        given: "两个 URL 都在 400 字符之后"
        def first = "https://example.com/a"
        def second = "https://example.com/b?x=1"
        def line = "头" * 420 + first + " 中间 " + "尾" * 100 + " " + second

        when:
        def capped = InputTruncator.lineCap(line)

        then:
        capped.contains(first)
        capped.contains(second)
    }
}
