// 守护纪律：投影四操作——窗口档位按会话传入、evicted 条目不投、成功回执不投影、
// 摘要只投转录里最新一段；模型要知道"更早内容存在"（截断自描述）
package cn.edu.scut.chatbot.context

import cn.edu.scut.chatbot.context.media.MediaProjector
import cn.edu.scut.chatbot.context.window.WindowAssembler
import cn.edu.scut.chatbot.loop.message.DeliveryReportEntry
import cn.edu.scut.chatbot.loop.message.SummaryEntry
import cn.edu.scut.chatbot.loop.message.UserMessage
import spock.lang.Specification

import java.time.Instant

class ContextAwareProjectorTest extends Specification {

    /** 主动放大 active/quiet 档差（20000 对 1000）——档位参数化是本类的被测意图，
     *  不与真实预算数值耦合；token 估算口径是 record toString（宁高不低），消息条数只求量级 */
    ContextAwareProjector projector = new ContextAwareProjector(
            new WindowAssembler(20000, 1000), new MediaProjector())

    private UserMessage user(String id, int offsetMinutes, String text) {
        return new UserMessage(id, Instant.ofEpochSecond(1775000000 + offsetMinutes * 60L),
                50, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text", text)))
    }

    private UserMessage imageMessage(String id, int offsetMinutes) {
        return new UserMessage(id, Instant.ofEpochSecond(1775000000 + offsetMinutes * 60L),
                50, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.ImagePart(
                        "image", "https://cdn.example.com/" + id + ".jpg", null, null, null, null)))
    }

    private DeliveryReportEntry report(String id, int offsetMinutes, String status, String failureDetail) {
        return new DeliveryReportEntry(id, Instant.ofEpochSecond(1775000000 + offsetMinutes * 60L),
                50, null, "ob-" + id, status, "failed".equals(status) ? "forbidden" : null, failureDetail)
    }

    private SummaryEntry summary(String id, int offsetMinutes, String text) {
        return new SummaryEntry(id, Instant.ofEpochSecond(1775000000 + offsetMinutes * 60L),
                50, null, text, "覆盖范围" + id)
    }

    def "窗口档位按会话参数传入：quiet 档淘汰出窗的条目不投且头部自描述标注"() {
        given: "60 条中等长度消息——active 档装得下、quiet 档装不下"
        def transcript = (0..59).collect { i -> user("m" + i, i, "这是第" + i + "条比较长的测试消息内容") }

        when:
        def activeProjected = projector.convert(transcript, ContextAwareProjector.LEVEL_ACTIVE)
        def quietProjected = projector.convert(transcript, "quiet")

        then: "active 档无淘汰——无头部标注，全部条目在窗"
        activeProjected.size() == 60
        !activeProjected[0].text().contains("上下文说明")

        and: "quiet 档预算更小——头部标注『更早的 N 条未逐字展示』（模型知道还有更早内容）"
        quietProjected[0].text().startsWith("[上下文说明]")
        quietProjected[0].text().contains("未逐字展示")
        quietProjected.size() < 60
        and: "被淘汰的最旧条目不再投（筛选操作）"
        def quietTexts = quietProjected.collect { it.text() }.join("\n")
        !quietTexts.contains("这是第0条")
        !quietTexts.contains("这是第1条")
        quietTexts.contains("这是第59条")
    }

    def "无档位形态默认 active 档（Worker 无会话上下文的口径）"() {
        given: "30 条消息（active 预算内）"
        def transcript = (0..29).collect { i -> user("m" + i, i, "内容" + i) }

        when:
        def viaDefault = projector.convert(transcript)

        then:
        viaDefault.size() == 30
        !viaDefault[0].text().contains("上下文说明")
    }

    def "成功回执不投影 失败回执投一行提示（发送成功是常态不需要提醒）"() {
        given:
        def transcript = [user("m1", 1, "你好"),
                          report("r-ok", 2, "success", null),
                          report("r-bad", 3, "failed", "机器人已被禁言"),
                          user("m2", 4, "在吗")]

        when:
        def projected = projector.convert(transcript)

        then:
        projected.size() == 3
        def texts = projected.collect { it.text() }
        !texts.any { it.contains("success") }
        texts.any { it.contains("发送失败") && it.contains("机器人已被禁言") }
    }

    def "最新摘要从转录自身提取：摘要区只投 occurredAt 最大的一段 且头部标注提及摘要去向"() {
        given: "转录含两段摘要——较早的『旧摘要』与较晚的『新摘要』，总量触发淘汰"
        def transcript = [summary("s-old", 1, "旧摘要内容甲甲甲")]
        transcript += (0..44).collect { i -> user("m" + i, i + 2, "这是第" + i + "条比较长的测试消息内容") }
        transcript << summary("s-new", 60, "新摘要内容乙乙乙")

        when:
        def projected = projector.convert(transcript, "quiet")

        then:
        def texts = projected.collect { it.text() }.join("\n")
        texts.contains(SummaryEntry.PROJECTION_HEADER + "新摘要内容乙乙乙")
        !texts.contains("旧摘要内容甲甲甲")
        and: "有摘要在窗时头部标注说明更早内容已压缩为上方摘要（截断自描述）"
        projected[0].text().contains("已压缩为上方摘要")
        and: "对话区条目照常在窗（摘要是并入视图不是替换对话区）"
        texts.contains("这是第44条")
    }

    def "对话区尾部锚定区内图片投原图占位 区外降级为描述行"() {
        given: "25 条消息：首条带图（锚定区之外）、末条带图（锚定区之内）"
        def transcript = (0..24).collect { i ->
            i == 0 || i == 24 ? imageMessage("img" + i, i) : user("m" + i, i, "文字" + i)
        }

        when:
        def projected = projector.convert(transcript)

        then:
        projected.size() == 25
        and: "锚定区外（开头的图片）经 MediaProjector 降级为描述行"
        projected[0].text().contains("[图片：描述未就绪]")
        and: "锚定区内（尾部 20 条）投原图占位形态（投影层不做变形）"
        projected[24].text().contains("[图片]")
        !projected[24].text().contains("[图片：")
    }
}
