// 守护纪律：Testcontainers 真迁移真表行驱动（禁 H2 替身——Flyway V6 DDL 与
// JSON 列行为藏在真 MySQL 里）；快照刷新新 Run 生效、进行中不受扰；完整性
// 检查缺项逐类告警（宪法第七条；spec US5 场景 1~8；quickstart 场景 A）
package cn.edu.scut.chatbot.llm;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.llm.slot.*;
import cn.edu.scut.chatbot.loop.ModelConfig;
import cn.edu.scut.chatbot.loop.ModelRef;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 槽位装配链集成：夹具插行（provider 两行两方言、model 各一、slot 绑 chat_main、
 * credential 一把）驱动装配断言 ModelRef 与 GenerationParams；改绑刷新后新 Run 用
 * 新绑定、进行中 Run（旧引用）不受扰；chat_main 未绑定走 llm.config_missing 失败路径；
 * 完整性检查两类缺项逐类触发。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class SlotAssemblyIT extends AbstractIntegrationTest {

    /**
     * chat_main 槽位 gen_params 夹具（seed 与用例还原共用同一份——还原行缺项会让
     * 后续用例读到残缺快照）
     */
    private static final String CHAT_MAIN_GEN_PARAMS =
            "{\"temperature\":0.6,\"maxOutputTokens\":2048,"
                    + "\"passthrough\":{\"thinking\":{\"type\":\"disabled\"}}}";

    @Autowired
    private ProviderConfigMapper providerMapper;

    @Autowired
    private ModelConfigMapper modelMapper;

    @Autowired
    private ModelSlotMapper slotMapper;

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private ModelCatalog catalog;

    @Autowired
    private SlotResolver resolver;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private CredentialCodec codec;

    @BeforeAll
    void seed() {
        codec = CredentialCodec.fromBase64Key(
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        jdbcTemplate.update("DELETE FROM model_slot");
        jdbcTemplate.update("DELETE FROM model_config");
        jdbcTemplate.update("DELETE FROM provider_config");
        jdbcTemplate.update("DELETE FROM credential WHERE owner_kind = 'provider'");

        providerMapper.insert(providerRow("zai", "智谱", "openai-compat",
                "https://api.z.ai/api/paas/v4"));
        providerMapper.insert(providerRow("google", "Google", "gemini",
                "https://generativelanguage.googleapis.com/v1beta"));
        modelMapper.insert(modelRow("zai", "glm-4.6",
                "{\"vision\":true,\"toolUse\":true,\"contextWindow\":128000,\"maxOutputTokens\":4096}",
                "{\"input\":1.0,\"output\":2.0,\"cached\":0.4}"));
        modelMapper.insert(modelRow("google", "gemini-2.0-flash",
                "{\"vision\":true,\"toolUse\":true,\"contextWindow\":1000000,\"maxOutputTokens\":8192}",
                "{\"input\":0.7,\"output\":2.5,\"cached\":0.2}"));
        slotMapper.insert(slotRow("chat_main", "zai", "glm-4.6", CHAT_MAIN_GEN_PARAMS));
        credentialMapper.insertCredential(credentialRow("zai", "sk-live-zai-key-0001"));

        catalog.refresh();
    }

    @Test
    void 槽位装配产出六要素ModelRef与GenerationParams() {
        ModelConfig config = resolver.resolve("chat_main");

        ModelRef ref = config.model();
        assertThat(ref.dialect()).isEqualTo("openai-compat");
        assertThat(ref.providerCode()).isEqualTo("zai");
        assertThat(ref.modelId()).isEqualTo("glm-4.6");
        assertThat(ref.baseUrl()).isEqualTo("https://api.z.ai/api/paas/v4");
        assertThat(ref.capabilities().contextWindow()).isEqualTo(128000);
        assertThat(ref.prices().inputPerMillion()).isEqualByComparingTo("1.0");
        assertThat(ref.slotCode()).isEqualTo("chat_main");

        assertThat(config.params().temperature()).isEqualTo(0.6);
        assertThat(config.params().maxOutputTokens()).isEqualTo(2048);
        assertThat(config.params().structuredOutput()).isFalse();
        assertThat(config.params().passthrough())
                .containsEntry("thinking", "{\"type\":\"disabled\"}");
    }

    @Test
    void 改绑刷新后新Run用新绑定_进行中Run持快照不受扰() {
        ModelConfig before = resolver.resolve("chat_main");

        jdbcTemplate.update(
                "UPDATE model_slot SET provider_code = 'google', model_id = 'gemini-2.0-flash' "
                        + "WHERE slot_code = 'chat_main'");
        eventPublisher.publishEvent(new ToolRegistry.ConfigRefreshEvent("IT 改绑"));

        ModelConfig after = resolver.resolve("chat_main");
        assertThat(after.model().dialect()).isEqualTo("gemini");
        assertThat(after.model().modelId()).isEqualTo("gemini-2.0-flash");

        // 进行中 Run 持旧快照：改绑前解析出的引用不受刷新影响
        assertThat(before.model().dialect()).isEqualTo("openai-compat");
        assertThat(before.model().modelId()).isEqualTo("glm-4.6");

        // 还原夹具（后续用例的基线）
        jdbcTemplate.update(
                "UPDATE model_slot SET provider_code = 'zai', model_id = 'glm-4.6' "
                        + "WHERE slot_code = 'chat_main'");
        eventPublisher.publishEvent(new ToolRegistry.ConfigRefreshEvent("IT 还原"));
    }

    @Test
    void chatMain未绑定走ConfigMissing失败路径() {
        jdbcTemplate.update("DELETE FROM model_slot WHERE slot_code = 'chat_main'");
        eventPublisher.publishEvent(new ToolRegistry.ConfigRefreshEvent("IT 解绑"));

        assertThatThrownBy(() -> resolver.resolve(ModelCatalog.SLOT_CHAT_MAIN))
                .isInstanceOf(ModelCatalog.SlotResolutionException.class)
                .hasMessageContaining("槽位未绑定");

        // 还原夹具
        slotMapper.insert(slotRow("chat_main", "zai", "glm-4.6", CHAT_MAIN_GEN_PARAMS));
        eventPublisher.publishEvent(new ToolRegistry.ConfigRefreshEvent("IT 还原"));
    }

    @Test
    void 完整性检查两类缺项逐类告警() {
        // google 供应商有 enabled 模型但无凭证（seed 只给 zai 配了 key）
        List<ModelCatalog.ConfigGap> gaps = catalog.configGaps();

        assertThat(gaps)
                .anyMatch(gap -> gap.kind().equals(ModelCatalog.ConfigGap.KIND_SLOT_UNBOUND)
                        && gap.detail().contains("task_worker"))
                .anyMatch(gap -> gap.kind().equals(ModelCatalog.ConfigGap.KIND_PROVIDER_NO_CREDENTIAL)
                        && gap.detail().contains("google"));
        assertThat(gaps).noneMatch(gap -> gap.detail().contains("provider=zai"));

        // 补配凭证后缺项消失（补配自然恢复无需重启）
        credentialMapper.insertCredential(credentialRow("google", "goog-key-0002"));
        assertThat(catalog.configGaps())
                .noneMatch(gap -> gap.kind().equals(ModelCatalog.ConfigGap.KIND_PROVIDER_NO_CREDENTIAL)
                        && gap.detail().contains("google"));
    }

    private ProviderConfigDO providerRow(String code, String displayName,
                                         String dialect, String baseUrl) {
        ProviderConfigDO row = new ProviderConfigDO();
        row.setCode(code);
        row.setDisplayName(displayName);
        row.setDialect(dialect);
        row.setBaseUrl(baseUrl);
        row.setEnabled(true);
        return row;
    }

    private ModelConfigDO modelRow(String providerCode, String modelId,
                                   String capability, String price) {
        ModelConfigDO row = new ModelConfigDO();
        row.setProviderCode(providerCode);
        row.setModelId(modelId);
        row.setDisplayName(modelId);
        row.setCapability(capability);
        row.setPrice(price);
        row.setEnabled(true);
        return row;
    }

    private ModelSlotDO slotRow(String slotCode, String providerCode,
                                String modelId, String genParams) {
        ModelSlotDO row = new ModelSlotDO();
        row.setSlotCode(slotCode);
        row.setProviderCode(providerCode);
        row.setModelId(modelId);
        row.setGenParams(genParams);
        row.setEnabled(true);
        return row;
    }

    private CredentialDO credentialRow(String providerCode, String plaintext) {
        CredentialDO row = new CredentialDO();
        row.setOwnerKind("provider");
        row.setOwnerCode(providerCode);
        row.setKeyCipher(codec.encryptToBytes(plaintext));
        row.setKeyMask(CredentialPool.maskOf(plaintext));
        row.setStatus("available");
        return row;
    }
}
