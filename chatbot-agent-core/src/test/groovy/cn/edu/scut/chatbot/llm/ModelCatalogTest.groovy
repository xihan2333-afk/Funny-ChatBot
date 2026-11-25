// 守护纪律：槽位三环启停校验、非法行只拖垮自身不拖垮其他、快照刷新隔离
// （宪法第七条必测清单；contracts/slot-and-credentials.md 第 1 节）
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.llm.slot.*
import spock.lang.Specification

class ModelCatalogTest extends Specification {

    def providerMapper = Mock(ProviderConfigMapper)
    def modelMapper = Mock(ModelConfigMapper)
    def slotMapper = Mock(ModelSlotMapper)
    def credentialMapper = Mock(CredentialMapper)
    def catalog = new ModelCatalog(providerMapper, modelMapper, slotMapper, credentialMapper)

    def providerRow(Map overrides = [:]) {
        def row = new ProviderConfigDO()
        row.setCode(overrides.code ?: "zai")
        row.setDisplayName(overrides.code ?: "zai")
        row.setDialect(overrides.dialect ?: "openai-compat")
        row.setBaseUrl(overrides.baseUrl ?: "https://api.z.ai/api/paas/v4")
        row.setEnabled(overrides.enabled != null ? overrides.enabled : true)
        return row
    }

    def modelRow(Map overrides = [:]) {
        def row = new ModelConfigDO()
        row.setProviderCode(overrides.providerCode ?: "zai")
        row.setModelId(overrides.modelId ?: "glm-4.6")
        row.setDisplayName(overrides.modelId ?: "glm-4.6")
        row.setCapability(overrides.capability
                ?: '{"vision":true,"toolUse":true,"contextWindow":128000,"maxOutputTokens":4096}')
        row.setPrice(overrides.price ?: '{"input":1.0,"output":2.0,"cached":0.5}')
        row.setEnabled(overrides.enabled != null ? overrides.enabled : true)
        return row
    }

    def slotRow(Map overrides = [:]) {
        def row = new ModelSlotDO()
        row.setSlotCode(overrides.slotCode ?: "chat_main")
        row.setProviderCode(overrides.providerCode ?: "zai")
        row.setModelId(overrides.modelId ?: "glm-4.6")
        row.setGenParams(overrides.genParams ?: '{"temperature":0.7,"maxOutputTokens":2048}')
        row.setEnabled(overrides.enabled != null ? overrides.enabled : true)
        return row
    }

    def load(rows) {
        providerMapper.selectAll() >> rows.providers
        modelMapper.selectAll() >> rows.models
        slotMapper.selectAll() >> rows.slots
        credentialMapper.selectByOwner(_, _) >> []
        catalog.start()
    }

    def "三环齐备解析产出六要素 ModelRef（dialect 冗余、能力与单价冒泡、slotCode 记来源）"() {
        given:
        load(providers: [providerRow()], models: [modelRow()], slots: [slotRow()])

        when:
        def ref = catalog.resolveSlot("chat_main")

        then:
        ref.dialect() == "openai-compat"
        ref.providerCode() == "zai"
        ref.modelId() == "glm-4.6"
        ref.baseUrl() == "https://api.z.ai/api/paas/v4"
        ref.capabilities().vision()
        ref.capabilities().contextWindow() == 128000
        ref.prices().inputPerMillion() == 1.0
        ref.slotCode() == "chat_main"
    }

    def "三环启停校验：任一环 disabled 或缺失即解析失败"() {
        given:
        load(providers: providers, models: models, slots: slots)

        when:
        catalog.resolveSlot("chat_main")

        then:
        thrown(ModelCatalog.SlotResolutionException)

        where:
        providers                     | models                     | slots
        [providerRow(enabled: false)] | [modelRow()]               | [slotRow()]
        [providerRow()]               | [modelRow(enabled: false)] | [slotRow()]
        [providerRow()]               | [modelRow()]               | [slotRow(enabled: false)]
        []                            | [modelRow()]               | [slotRow()]
        [providerRow()]               | []                         | [slotRow()]
        [providerRow()]               | [modelRow()]               | []
    }

    def "gen_params 非法只拖垮本槽位（其他槽位照常解析）"() {
        given:
        load(providers: [providerRow()],
                models: [modelRow(), modelRow(modelId: "glm-4.5-air")],
                slots: [slotRow(genParams: "{broken"),
                        slotRow(slotCode: "task_worker", modelId: "glm-4.5-air")])

        when:
        catalog.resolveSlot("chat_main")

        then:
        thrown(ModelCatalog.SlotResolutionException)

        and: "同目录其他槽位不受影响"
        catalog.resolveSlot("task_worker").modelId() == "glm-4.5-air"
    }

    def "capability 非法的模型行装载跳过不拖垮其他模型"() {
        given:
        load(providers: [providerRow()],
                models: [modelRow(modelId: "broken-one", capability: "not-json"),
                         modelRow(modelId: "glm-4.6")],
                slots: [slotRow()])

        when:
        def ref = catalog.resolveSlot("chat_main")

        then:
        ref.modelId() == "glm-4.6"
        catalog.pricesOf("zai", "broken-one") == null
    }

    def "快照刷新隔离：刷新后新解析用新绑定（新 Run 生效）"() {
        given: "首轮装载绑 glm-4.6；刷新后的第二次装载改绑 glm-4.5-air"
        providerMapper.selectAll() >> [providerRow()]
        modelMapper.selectAll() >> [modelRow(), modelRow(modelId: "glm-4.5-air")]
        slotMapper.selectAll() >>> [[slotRow()], [slotRow(modelId: "glm-4.5-air")]]
        credentialMapper.selectByOwner(_, _) >> []
        catalog.start()

        when: "发刷新事件"
        catalog.refresh()

        then: "新 Run 解析到新绑定"
        catalog.resolveSlot("chat_main").modelId() == "glm-4.5-air"
    }

    def "完整性检查：未绑定槽位逐条、有模型无凭证的供应商逐条"() {
        given:
        def available = new CredentialDO()
        available.setStatus("available")
        providerMapper.selectAll() >> [providerRow(), providerRow(code: "deepseek")]
        modelMapper.selectAll() >> [modelRow(), modelRow(providerCode: "deepseek", modelId: "deepseek-chat")]
        slotMapper.selectAll() >> [slotRow()]
        credentialMapper.selectByOwner("provider", "zai") >> [available]
        credentialMapper.selectByOwner("provider", "deepseek") >> []
        catalog.start()

        when:
        def gaps = catalog.configGaps()

        then:
        gaps.count { it.kind() == ModelCatalog.ConfigGap.KIND_SLOT_UNBOUND } == 8
        gaps.count {
            it.kind() == ModelCatalog.ConfigGap.KIND_PROVIDER_NO_CREDENTIAL
                    && it.detail().contains("deepseek")
        } == 1
        !gaps.any {
            it.kind() == ModelCatalog.ConfigGap.KIND_PROVIDER_NO_CREDENTIAL
                    && it.detail().contains("zai")
        }
    }
}
