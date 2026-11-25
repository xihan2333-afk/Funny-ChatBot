// 守护纪律：三能力接口同步契约（字节输出、错误归一、槽位未绑定 config_missing）、
// 字节上限截断判错（防内存放大）、空 embedding 列表不发起调用
// （宪法第七条必测清单；contracts/capability-api.md 第 1 节）
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder
import cn.edu.scut.chatbot.llm.capability.OpenAiCompatCapabilityClient
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta
import cn.edu.scut.chatbot.llm.slot.*
import cn.edu.scut.chatbot.loop.GenerationParams
import cn.edu.scut.chatbot.loop.ModelCapabilities
import cn.edu.scut.chatbot.loop.ModelConfig
import cn.edu.scut.chatbot.loop.ModelRef
import spock.lang.Specification

class CapabilityClientTest extends Specification {

    def paramService = Mock(SystemParamService) {
        getLong(_, _) >> 300L
        getInt(_, _) >> 20 * 1024 * 1024
    }
    def holder = new HttpClientHolder(paramService)
    def resolver = Mock(SlotResolver)
    def pool = Mock(CredentialPool)
    def recorder = Mock(CostRecorder)
    def client = new OpenAiCompatCapabilityClient(holder, resolver, pool, paramService, recorder)
    def servers = []

    def server() {
        def server = new ScriptedHttpUpstream()
        server.start()
        servers << server
        return server
    }

    def bindSlot(String slotCode, String baseUrl, String modelId = "cogview-x") {
        def ref = new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", modelId, baseUrl,
                new ModelCapabilities(false, false, 8192, 4096), null, slotCode)
        resolver.resolve(slotCode) >> new ModelConfig(ref, GenerationParams.defaults())
        pool.nextAvailable("zai") >> "sk-cap-key-0001"
        return ref
    }

    def cleanup() {
        servers.each { it.close() }
        servers.clear()
    }

    def "生图：同步返回字节与 MIME，请求体组装正确"() {
        given:
        def server = server()
        bindSlot(ModelCatalog.SLOT_IMAGE_GEN, server.baseUrl())
        byte[] imageBytes = [0x89, 0x50, 0x4E, 0x47, 0x0A] as byte[]
        String b64 = Base64.getEncoder().encodeToString(imageBytes)
        server.addScript("/images/generations", [new ScriptedHttpUpstream.Respond(200, "application/json",
                '{"data":[{"b64_json":"' + b64 + '","mime_type":"image/png"}]}')])

        when:
        def result = client.generate(ModelCatalog.SLOT_IMAGE_GEN,
                LlmCallMeta.of("image", null), "一只戴墨镜的猫", null)

        then:
        result.data() == imageBytes
        result.mimeType() == "image/png"

        and: "请求体断言"
        def captured = server.requestsFor("/images/generations")[0]
        captured.headers().getFirst("Authorization") == "Bearer sk-cap-key-0001"
        def body = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().readTree(captured.body())
        body.path("model").asText() == "cogview-x"
        body.path("prompt").asText() == "一只戴墨镜的猫"
        body.path("response_format").asText() == "b64_json"
    }

    def "语音合成：字节与 MIME 同步返回"() {
        given:
        def server = server()
        bindSlot(ModelCatalog.SLOT_VOICE_GEN, server.baseUrl(), "tts-x")
        server.addScript("/audio/speech", [new ScriptedHttpUpstream.Respond(200, "audio/mpeg", "fake-audio-body")])

        when:
        def result = client.synthesize(ModelCatalog.SLOT_VOICE_GEN,
                LlmCallMeta.of("voice", null), "大家好呀", null)

        then:
        new String(result.data(), "UTF-8") == "fake-audio-body"
        result.mimeType() == "audio/mpeg"
    }

    def "向量化：等长向量按 index 归位（返回乱序也能对上）"() {
        given:
        def server = server()
        bindSlot(ModelCatalog.SLOT_EMBEDDING, server.baseUrl(), "embedding-x")
        server.addScript("/embeddings", [new ScriptedHttpUpstream.Respond(200, "application/json",
                '''{"data":[{"index":1,"embedding":[0.4,0.5]},{"index":0,"embedding":[0.1,0.2]}]}''')])

        when:
        def vectors = client.embed(ModelCatalog.SLOT_EMBEDDING,
                LlmCallMeta.of("embed", null), ["第一句", "第二句"])

        then:
        vectors.size() == 2
        vectors[0] == (float[]) [0.1f, 0.2f]
        vectors[1] == (float[]) [0.4f, 0.5f]
    }

    def "空 embedding 列表返回空列表不发起调用"() {
        given:
        def server = server()

        when:
        def vectors = client.embed(ModelCatalog.SLOT_EMBEDDING,
                LlmCallMeta.of("embed", null), [])

        then:
        vectors.isEmpty()
        server.totalHits() == 0
        0 * resolver.resolve(_)
    }

    def "槽位未绑定 → CapabilityException 携带 config_missing"() {
        given:
        resolver.resolve(ModelCatalog.SLOT_IMAGE_GEN) >> {
            throw new ModelCatalog.SlotResolutionException("槽位未绑定 slot=image_gen")
        }

        when:
        client.generate(ModelCatalog.SLOT_IMAGE_GEN, LlmCallMeta.of("image", null), "x", null)

        then:
        def e = thrown(OpenAiCompatCapabilityClient.CapabilityException)
        e.errorCode() == LlmErrorCodes.CONFIG_MISSING
    }

    def "字节超上限截断判错（system_param 可调）"() {
        given: "独立小上限 client（字段级 20MB stub 压制用例级覆盖——独立 Mock 绕开）"
        def server = server()
        def smallParam = Mock(SystemParamService) {
            getLong(_, _) >> 300L
            getInt("llm.capability.max_bytes", _) >> 4
        }
        def smallClient = new OpenAiCompatCapabilityClient(
                new HttpClientHolder(smallParam), resolver, pool, smallParam, recorder)
        bindSlot(ModelCatalog.SLOT_IMAGE_GEN, server.baseUrl())
        byte[] imageBytes = [1, 2, 3, 4, 5, 6, 7, 8] as byte[]
        server.addScript("/images/generations", [new ScriptedHttpUpstream.Respond(200, "application/json",
                '{"data":[{"b64_json":"' + Base64.getEncoder().encodeToString(imageBytes) + '"}]}')])

        when:
        smallClient.generate(ModelCatalog.SLOT_IMAGE_GEN, LlmCallMeta.of("image", null), "x", null)

        then:
        def e = thrown(OpenAiCompatCapabilityClient.CapabilityException)
        e.getMessage().contains("字节上限")
    }

    def "上游 429 归一 rate_limited 异常抛出"() {
        given:
        def server = server()
        bindSlot(ModelCatalog.SLOT_IMAGE_GEN, server.baseUrl())
        server.addScript("/images/generations", [new ScriptedHttpUpstream.Respond(429, "application/json",
                '{"error":{"message":"Rate limit"}}')])

        when:
        client.generate(ModelCatalog.SLOT_IMAGE_GEN, LlmCallMeta.of("image", null), "x", null)

        then:
        def e = thrown(OpenAiCompatCapabilityClient.CapabilityException)
        e.errorCode() == LlmErrorCodes.RATE_LIMITED
    }
}
