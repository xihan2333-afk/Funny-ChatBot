// 守护纪律：SSE 边界逐项——多行拼接、CRLF 双容忍、空 data 行、[DONE] 哨兵与流关闭
// 两种终止（宪法第七条必测清单「方言差异逐项」的公共底座部分；contracts/dialect-translators.md 第 6 节）
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.llm.sse.SseParser
import spock.lang.Specification

class SseParserTest extends Specification {

    def parser = new SseParser()
    def received = []

    def parse(String raw) {
        received.clear()
        parser.parse(new ByteArrayInputStream(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                { data -> received.add(data) }, null)
        return received
    }

    def "两个事件各自完整回调（空行切分）"() {
        expect:
        parse("data: 你好\n\ndata: 世界\n\n") == ["你好", "世界"]
    }

    def "同一事件多行 data 以换行拼接"() {
        expect:
        parse("data: 第一行\ndata: 第二行\ndata: 第三行\n\n") == ["第一行\n第二行\n第三行"]
    }

    def "CRLF 行尾与 LF 行尾双容忍"() {
        expect:
        parse("data: 甲\r\n\r\ndata: 乙\n\n") == ["甲", "乙"]
    }

    def "CRLF 事件内多行拼接不残留回车"() {
        expect:
        parse("data: 行一\r\ndata: 行二\r\n\r\n") == ["行一\n行二"]
    }

    def "冒号后零空格与一空格都剥前缀"() {
        expect:
        parse("data:无空格\n\ndata: 有一空格\n\n") == ["无空格", "有一空格"]
    }

    def "data 后为空的行跳过不产出事件"() {
        expect:
        parse("data:\n\ndata: 实际内容\n\n") == ["实际内容"]
    }

    def "注释行跳过不产出事件"() {
        expect:
        parse(": keep-alive 注释\ndata: 内容\n\n") == ["内容"]
    }

    def "event 与 id 字段忽略不报错"() {
        expect:
        parse("event: message\nid: 42\ndata: 载荷\n\n") == ["载荷"]
    }

    def "DONE 哨兵作为普通 data 回调（识别归翻译器）"() {
        expect:
        parse("data: [DONE]\n\n") == ["[DONE]"]
    }

    def "流关闭且末尾事件无空行终结时也回调（宽容收尾）"() {
        expect:
        parse("data: 无终结行") == ["无终结行"]
    }

    def "空流无回调"() {
        expect:
        parse("") == []
    }

    def "取消信号置位即停止读流（后续行不再回调）"() {
        given:
        def signal = new cn.edu.scut.chatbot.loop.CancellationSignal()
        received.clear()
        def stream = new ByteArrayInputStream("data: 前半\n\ndata: 后半\n\n".getBytes("UTF-8"))

        when: "解析中途置位取消"
        parser.parse(stream, { data ->
            received.add(data)
            if (data == "前半") {
                signal.cancel()
            }
        }, signal)

        then: "已到达的事件保留、后续事件不再读"
        received == ["前半"]
    }
}
