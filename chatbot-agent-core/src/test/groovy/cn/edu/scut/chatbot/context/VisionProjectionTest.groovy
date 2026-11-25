// 守护纪律：多模态投影三规则——锚定区内装原图 / 滚出锚定区维持描述行 /
// vision=false 一律描述行零 images（data-model 第 4 节；spec US8 场景 7/8）
package cn.edu.scut.chatbot.context

import cn.edu.scut.chatbot.context.media.MediaProjector
import cn.edu.scut.chatbot.context.window.WindowAssembler
import cn.edu.scut.chatbot.contract.message.ImagePart
import cn.edu.scut.chatbot.contract.message.MediaTransferStatusCodes
import cn.edu.scut.chatbot.contract.message.TextPart
import cn.edu.scut.chatbot.loop.LlmMessage
import cn.edu.scut.chatbot.loop.message.UserMessage
import spock.lang.Specification

class VisionProjectionTest extends Specification {

    def projector = new ContextAwareProjector(
            new WindowAssembler(20000, 1000), new MediaProjector({ ref -> null }))

    def imageMessage(String sender, String storedRef) {
        return new UserMessage("m-" + System.nanoTime(), java.time.Instant.now(), 60, null,
                sender, [new TextPart(TextPart.TYPE_TEXT, "看图"),
                         new ImagePart(ImagePart.TYPE_IMAGE, null, storedRef,
                                 MediaTransferStatusCodes.TRANSFERRED, "f.png", 10L)])
    }

    def transcriptWithImages() {
        // 25 条：前 5 条带图（滚出锚定区——锚定区是尾部 20 条），后 2 条带图（区内）
        def messages = []
        (1..5).each { messages << imageMessage("u-old-" + it, "media-old-" + it) }
        (1..18).each { messages << imageMessage("u-plain-" + it, null) }
        messages << imageMessage("u-in-anchor-1", "media-anchor-1")
        messages << imageMessage("u-in-anchor-2", "media-anchor-2")
        return messages
    }

    def "vision=true：锚定区内装原图（images 带 storedRef）、滚出锚定区维持描述行"() {
        when:
        def projected = projector.convert(transcriptWithImages(),
                ContextAwareProjector.LEVEL_ACTIVE, true)

        then: "锚定区内两条带 storedRef 的消息装进 images"
        def anchorMessages = projected.findAll {
            it.role() == LlmMessage.ROLE_USER && !it.images().isEmpty()
        }
        anchorMessages.collect { it.images()[0].storedRef() } == ["media-anchor-1", "media-anchor-2"]

        and: "滚出锚定区的图片不装 images（描述行文本形态）"
        projected.every { msg ->
            msg.images().every { img -> !img.storedRef().startsWith("media-old-") }
        }
        def oldLine = projected.find { it.text() != null && it.text().contains("u-old-1") }
        oldLine.images().isEmpty()
        oldLine.text().contains("[图片")
    }

    def "vision=false：窗口内一律描述行、不装任何 images（即使锚定区内）"() {
        when:
        def projected = projector.convert(transcriptWithImages(),
                ContextAwareProjector.LEVEL_ACTIVE, false)

        then:
        projected.every { it.images().isEmpty() }

        and: "锚定区内图片也是描述行文本"
        def anchorLine = projected.find { it.text() != null && it.text().contains("u-in-anchor-1") }
        anchorLine.text().contains("[图片")
    }

    def "无 storedRef 的图片（仅时效 URL）不装载——转存引用是装载前提"() {
        given:
        def messages = (1..21).collect {
            new UserMessage("m-" + it, java.time.Instant.now(), 60, null, "u" + it,
                    [new ImagePart(ImagePart.TYPE_IMAGE, "https://expire.example/x.png",
                            null, MediaTransferStatusCodes.PENDING, "x.png", 5L)])
        }

        when:
        def projected = projector.convert(messages, ContextAwareProjector.LEVEL_ACTIVE, true)

        then:
        projected.every { it.images().isEmpty() }
    }
}
