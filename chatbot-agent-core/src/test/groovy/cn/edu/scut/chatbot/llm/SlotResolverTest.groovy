// 守护纪律：gen_params 四项映射与缺省、成对 ModelConfig、解析失败只拖垮本槽位
// （宪法第七条必测清单；contracts/slot-and-credentials.md 第 2 节）
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.llm.slot.ModelCatalog
import cn.edu.scut.chatbot.llm.slot.SlotResolver
import cn.edu.scut.chatbot.loop.GenerationParams
import cn.edu.scut.chatbot.loop.ModelCapabilities
import cn.edu.scut.chatbot.loop.ModelConfig
import cn.edu.scut.chatbot.loop.ModelRef
import spock.lang.Specification

class SlotResolverTest extends Specification {

    def catalog = Mock(ModelCatalog)
    def resolver = new SlotResolver(catalog)

    def modelRef() {
        return new ModelRef("openai-compat", "zai", "glm-4.6", "https://api.z.ai/api/paas/v4",
                new ModelCapabilities(true, true, 128000, 4096), null, "chat_main")
    }

    def "gen_params 四项全给时逐项映射、与 ModelRef 成对"() {
        given:
        catalog.resolveSlot("chat_main") >> modelRef()
        catalog.genParamsOf("chat_main") >>
                '''{"temperature":0.3,"maxOutputTokens":1024,"structuredOutput":true,
                   "passthrough":{"thinking":{"type":"disabled"},"top_k":"50"}}'''

        when:
        def config = resolver.resolve("chat_main")

        then:
        config instanceof ModelConfig
        config.model().modelId() == "glm-4.6"
        config.params().temperature() == 0.3
        config.params().maxOutputTokens() == 1024
        config.params().structuredOutput()
        config.params().passthrough()["thinking"] == '{"type":"disabled"}'
        config.params().passthrough()["top_k"] == "50"
    }

    def "gen_params 缺项走缺省（0.8 / 2048 / 关 / 空 passthrough）"() {
        given:
        catalog.resolveSlot("chat_main") >> modelRef()
        catalog.genParamsOf("chat_main") >> "{}"

        when:
        def params = resolver.resolve("chat_main").params()

        then:
        params.temperature() == 0.8
        params.maxOutputTokens() == 2048
        !params.structuredOutput()
        params.passthrough().isEmpty()
    }

    def "gen_params 缺省值与 GenerationParams.defaults() 一致"() {
        expect:
        SlotResolver.parseGenParams("{}") == GenerationParams.defaults()
        SlotResolver.parseGenParams(null) == GenerationParams.defaults()
    }

    def "槽位解析失败上抛（上层走 llm.config_missing 既有路径）"() {
        given:
        catalog.resolveSlot("chat_main") >> {
            throw new ModelCatalog.SlotResolutionException("槽位未绑定 slot=chat_main")
        }

        when:
        resolver.resolve("chat_main")

        then:
        thrown(ModelCatalog.SlotResolutionException)
    }
}
