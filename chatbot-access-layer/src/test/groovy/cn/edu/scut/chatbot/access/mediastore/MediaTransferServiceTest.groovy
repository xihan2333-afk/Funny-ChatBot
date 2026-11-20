package cn.edu.scut.chatbot.access.mediastore

import cn.edu.scut.chatbot.access.fixtures.SteadyClock
import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException
import cn.edu.scut.chatbot.contract.error.ErrorCodes
import cn.edu.scut.chatbot.contract.message.ImagePart
import cn.edu.scut.chatbot.contract.message.MediaTransferStatusCodes
import cn.edu.scut.chatbot.contract.message.TextPart
import spock.lang.Specification

import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 守护纪律：媒体转存三分支时序（data-model.md 第 3 节，事件一次成型是 Agent 幂等契约的必然要求）。
 * 限时内成功 → transferred + storedRef；超时 / 失败 → failed + 原 URL 立即入流不阻塞主流程。
 * 固定 Clock 驱动限时计算（限时值来自注入时钟的 deadline，不写死系统时间）。
 */
class MediaTransferServiceTest extends Specification {

    def safeFetcher = Mock(SafeFetcher)
    /** 每任务一条新虚拟线程（生产同款执行器形态）：下载异步跑，主线程的限时 get 才有超时分支可测 */
    ExecutorService downloadExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("media-transfer-test-", 0).factory())
    def clock = new SteadyClock(1775001600000L)
    def tempStoreRoot = Files.createTempDirectory("media-transfer-test")

    def service = new MediaTransferService(safeFetcher, downloadExecutor, clock, 3000, tempStoreRoot.toString())

    private ImagePart pendingImage(String url = "https://multimedia.nt.qq.com.cn/x.jpg") {
        new ImagePart(ImagePart.TYPE_IMAGE, url, null, MediaTransferStatusCodes.PENDING, "X.jpg", 10L)
    }

    def cleanup() {
        downloadExecutor.shutdownNow()
        tempStoreRoot.toFile().deleteDir()
    }

    def "转存成功段状态transferred且storedRef指向落卷文件"() {
        given:
        safeFetcher.fetch(_ as String) >> new SafeFetcher.FetchedMedia("image/jpeg", [0xAB, 0xCD] as byte[])

        when:
        def parts = service.transferInbound([pendingImage()])

        then:
        def image = parts[0] as ImagePart
        image.transferStatus() == MediaTransferStatusCodes.TRANSFERRED
        image.storedRef() != null
        image.originalUrl() == "https://multimedia.nt.qq.com.cn/x.jpg"
        Files.exists(java.nio.file.Path.of(image.storedRef()))
        Files.readAllBytes(java.nio.file.Path.of(image.storedRef())) == [0xAB, 0xCD] as byte[]
    }

    def "下载超时段状态failed原URL立即保留"() {
        given: "下载动作耗时 200ms，限时 3ms——固定时钟下 remaining 恒 3ms，必定超时"
        def slowService = new MediaTransferService(safeFetcher, downloadExecutor, clock, 3, tempStoreRoot.toString())
        safeFetcher.fetch(_ as String) >> {
            Thread.sleep(200)
            new SafeFetcher.FetchedMedia("image/jpeg", [0x01] as byte[])
        }

        when:
        def parts = slowService.transferInbound([pendingImage()])

        then: "段带 failed 与原 URL 入流，不抛异常不阻塞"
        def image = parts[0] as ImagePart
        image.transferStatus() == MediaTransferStatusCodes.FAILED
        image.storedRef() == null
        image.originalUrl() == "https://multimedia.nt.qq.com.cn/x.jpg"
    }

    def "下载失败段状态failed不抛异常"() {
        given:
        safeFetcher.fetch(_ as String) >> {
            throw new NonRetryableException(ErrorCodes.ADAPTER_MEDIA_FETCH_FAILED, "私有网段拒绝")
        }

        when:
        def parts = service.transferInbound([pendingImage()])

        then:
        (parts[0] as ImagePart).transferStatus() == MediaTransferStatusCodes.FAILED
    }

    def "无URL与已failed的段原样保留不触发下载"() {
        given:
        def noUrl = new ImagePart(ImagePart.TYPE_IMAGE, null, null, MediaTransferStatusCodes.FAILED, "N.jpg", null)
        def text = new TextPart(TextPart.TYPE_TEXT, "不是媒体")

        when:
        def parts = service.transferInbound([noUrl, text])

        then:
        parts[0].is(noUrl)
        parts[1].is(text)
        0 * safeFetcher.fetch(_ as String)
    }

    def "转存成功后段的其余字段原样保留且列表顺序不变"() {
        given: "文本段 + 图片段 + 语音段的混合列表"
        safeFetcher.fetch(_ as String) >> new SafeFetcher.FetchedMedia("image/jpeg", [0xAB] as byte[])
        def text = new TextPart(TextPart.TYPE_TEXT, "前面有字")
        def image = new ImagePart(ImagePart.TYPE_IMAGE, "https://cdn.example.com/a.jpg", null,
                MediaTransferStatusCodes.PENDING, "A.jpg", 204800L)
        def audio = new cn.edu.scut.chatbot.contract.message.AudioPart("audio", "https://cdn.example.com/b.silk",
                null, MediaTransferStatusCodes.PENDING, "B.silk", 4096L)

        when:
        def parts = service.transferInbound([text, image, audio])

        then: "顺序与段位次不变"
        parts.size() == 3
        parts[0].is(text)
        parts[1] instanceof ImagePart
                parts[2] instanceof cn.edu.scut.chatbot.contract.message.AudioPart

                and : "转存只替换 transferStatus 与 storedRef——文件名与大小原样保留"
        def transferredImage = parts[1] as ImagePart
        transferredImage.fileName() == "A.jpg"
        transferredImage.fileSize() == 204800L
        transferredImage.originalUrl() == "https://cdn.example.com/a.jpg"
        def transferredAudio = parts[2] as cn.edu.scut.chatbot.contract.message.AudioPart
        transferredAudio.fileName() == "B.silk"
        transferredAudio.fileSize() == 4096L
    }

    def "落卷文件扩展名按Content-Type映射且jpeg归一为jpg"() {
        given: "Content-Type image/jpeg——扩展名归一为 jpg（不是 jpeg）"
        safeFetcher.fetch(_ as String) >> new SafeFetcher.FetchedMedia("image/jpeg", [0x01] as byte[])

        when:
        def storedRef = (service.transferInbound([pendingImage()])[0] as ImagePart).storedRef()

        then:
        storedRef.endsWith(".jpg")
    }

    def "无类型直链按URL路径扩展名落卷"() {
        given: "octet-stream + URL 以 .png 结尾"
        safeFetcher.fetch(_ as String) >> new SafeFetcher.FetchedMedia("application/octet-stream", [0x02] as byte[])

        when:
        def storedRef = (service.transferInbound([pendingImage("https://8.8.8.8/pic.png")])[0] as ImagePart).storedRef()

        then:
        storedRef.endsWith(".png")
    }

    def "无类型且URL无扩展名时兜底bin后缀"() {
        given:
        safeFetcher.fetch(_ as String) >> new SafeFetcher.FetchedMedia("application/octet-stream", [0x03] as byte[])

        when:
        def storedRef = (service.transferInbound([pendingImage("https://8.8.8.8/naked")])[0] as ImagePart).storedRef()

        then:
        storedRef.endsWith(".bin")
    }
}
