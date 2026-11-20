package cn.edu.scut.chatbot.access.codec

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * 守护纪律：OneBot message 字段双形态解析（契约 onebot-ws-adapter.md 第 5 节 + research R1）。
 * message 可能是 array（段数组，NapCat 默认）也可能是 string（CQ 码，规范默认）——配置漂移不炸进程；
 * CQ 码 & / [ / ] / , 四字符转义必须还原，否则消息内容失真。
 */
class OneBotMessageParserTest extends Specification {

    def parser = new OneBotMessageParser()
    def mapper = new ObjectMapper()

    def "array段格式解析为统一段表示"() {
        given:
        def messageNode = mapper.readTree('''[
            {"type": "at", "data": {"qq": "10000"}},
            {"type": "text", "data": {"text": " 看看这张图"}},
            {"type": "image", "data": {"url": "https://cdn.example.com/a.jpg", "file": "A.jpg", "file_size": 1024}}
        ]''')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.size() == 3
        segments[0].type() == "at"
        segments[0].data() == [qq: "10000"]
        segments[1].type() == "text"
        segments[1].data() == [text: " 看看这张图"]
        segments[2].type() == "image"
        segments[2].data().url == "https://cdn.example.com/a.jpg"
        segments[2].data().file_size == "1024"
    }

    def "CQ码string格式解析为与array同一内部表示"() {
        given:
        def messageNode = mapper.readTree('"[CQ:at,qq=10000] 看看这张图[CQ:image,file=A.jpg]"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.size() == 3
        segments[0] == new OneBotMessageParser.ParsedSegment("at", [qq: "10000"])
        segments[1] == new OneBotMessageParser.ParsedSegment("text", [text: " 看看这张图"])
        segments[2] == new OneBotMessageParser.ParsedSegment("image", [file: "A.jpg"])
    }

    def "CQ码四字符转义全部还原"() {
        given: "参数值含 & [ ] , 四个字符的转义形态"
        def messageNode = mapper.readTree('"[CQ:text]x[CQ:share,url=https://a.io/?x=1&amp;y=2&#44;3,title=标题&#91;带方括号&#93;]"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.size() == 3
        def share = segments[2]
        share.type() == "share"
        share.data().url == "https://a.io/?x=1&y=2,3"
        share.data().title == "标题[带方括号]"
    }

    def "转义后的逗号与方括号不干扰CQ码分割"() {
        given: "值内的转义逗号不是参数分隔符、转义右方括号不是码结束符"
        def messageNode = mapper.readTree('"[CQ:image,file=a&#44;b.jpg,url=https://c/d?e=1&#93;end]"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.size() == 1
        segments[0].data() == [file: "a,b.jpg", url: "https://c/d?e=1]end"]
    }

    def "纯文本消息解析为单个文本段"() {
        given:
        def messageNode = mapper.readTree('"你好，世界[不是CQ]"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.size() == 1
        segments[0].type() == "text"
        segments[0].data().text == "你好，世界[不是CQ]"
    }

    def "段参数值非字符串容忍转为字符串"() {
        given: "data 值出现数字与布尔（OneBot 规范允许实现端偏差）"
        def messageNode = mapper.readTree('''[
            {"type": "image", "data": {"file_size": 204800, "subType": 0, "as_face": false}}
        ]''')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments[0].data() == [file_size: "204800", subType: "0", as_face: "false"]
    }

    def "message字段缺失时返回空段列表"() {
        given:
        def messageNode = mapper.readTree('null')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.isEmpty()
    }

    def "message字段为空字符串返回空段列表"() {
        given:
        def messageNode = mapper.readTree('""')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.isEmpty()
    }

    def "message字段为非预期形态按空消息处理不炸进程"() {
        given: "协议端严重偏差——message 给了数字或对象"
        def numberForm = mapper.readTree('12345')
        def objectForm = mapper.readTree('{"unexpected": true}')

        when:
        def fromNumber = parser.parseMessageField(numberForm)
        def fromObject = parser.parseMessageField(objectForm)

        then: "按空消息处理，由上层信封校验兜住"
        fromNumber.isEmpty()
        fromObject.isEmpty()
    }

    def "没有结束符的残缺CQ码剩余全部按文本保真"() {
        given: "半截码（网络截断或协议端 bug）"
        def messageNode = mapper.readTree('"前文本[CQ:image,file=a.jpg' + '"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then: "残缺部分不做段解析，原文保真为文本段"
        segments.size() == 2
        segments[0].type() == "text"
        segments[0].data().text == "前文本"
        segments[1].type() == "text"
        segments[1].data().text == "[CQ:image,file=a.jpg"
    }

    def "无值参数的CQ码键保留值为空字符串"() {
        given: "如 [CQ:shake,flag] 这类无等号参数——键保留、值为空串"
        def messageNode = mapper.readTree('"[CQ:shake,flag]抖你一下"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.size() == 2
        segments[0].type() == "shake"
        segments[0].data() == [flag: ""]
        segments[1].type() == "text"
        segments[1].data().text == "抖你一下"
    }

    def "无参数的纯类型CQ码解析为空参数表"() {
        given:
        def messageNode = mapper.readTree('"[CQ:shake]"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments.size() == 1
        segments[0].type() == "shake"
        segments[0].data().isEmpty()
    }

    def "段参数值为对象时序列化为JSON字符串容忍保留"() {
        given: "NapCat 扩展段常见嵌套对象参数"
        def messageNode = mapper.readTree('''[
            {"type": "mface", "data": {"summary": {"key": "v"}, "urls": [1, 2]}}
        ]''')

        when:
        def segments = parser.parseMessageField(messageNode)

        then: "对象 / 数组序列化为 JSON 字符串，信息不丢"
        segments[0].data().summary == '{"key":"v"}'
        segments[0].data().urls == '[1,2]'
    }

    def "转义序列字面量不发生二次反转义走样"() {
        given: "原始文本本身就是 &amp;#44; 这串字符——反转义一次得到 &#44;，不得再变成逗号"
        def messageNode = mapper.readTree('"a&amp;#44;b"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then: "单遍替换：&amp; → &，其后的 #44; 保持字面"
        segments.size() == 1
        segments[0].data().text == "a&#44;b"
    }

    def "at全体成员的CQ码qq参数为all原样保留"() {
        given:
        def messageNode = mapper.readTree('"[CQ:at,qq=all] 注意看"')

        when:
        def segments = parser.parseMessageField(messageNode)

        then:
        segments[0].type() == "at"
        segments[0].data() == [qq: "all"]
    }
}
