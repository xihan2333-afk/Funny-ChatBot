package cn.edu.scut.chatbot.llm.slot;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.loop.ModelCapabilities;
import cn.edu.scut.chatbot.loop.ModelPrices;
import cn.edu.scut.chatbot.loop.ModelRef;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录（contracts/slot-and-credentials.md 第 1 节）：provider_config + model_config +
 * model_slot 三表全量装载为不可变快照 volatile 发布；监听 ConfigRefreshEvent（与
 * ToolRegistry 同源事件）重建快照——新 Run 生效、进行中 Run 持旧快照不受扰。
 *
 * <p>resolveSlot 三环校验（slot 行 enabled → model 行 enabled → provider 行 enabled）
 * 产出 ModelRef；dialect 冗余自 provider、capabilities/prices 冒泡自 model 行、
 * slotCode 记来源；任一环缺失或 gen_params JSON 非法即解析失败（上层走既有
 * llm.config_missing 路径）。capability/price 非法行装载时告警跳过不拖垮其他模型。
 *
 * <p>完整性检查（活着但不可用的可见性）：九槽位未绑定逐条、有 enabled 模型但缺
 * available 凭证的供应商逐条——告警只记不阻断启动（首次部署槽位全空是预期态）。
 */
@Component
public class ModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalog.class);

    /**
     * 九槽位代号（data-model 第 8 节）——代码只引用槽位代号，绑定关系纯运营数据
     */
    public static final String SLOT_CHAT_MAIN = "chat_main";
    public static final String SLOT_TASK_WORKER = "task_worker";
    public static final String SLOT_EVAL_L1 = "eval_l1";
    public static final String SLOT_EVAL_JUDGE = "eval_judge";
    public static final String SLOT_SUMMARIZE = "summarize";
    public static final String SLOT_VISION_DESC = "vision_desc";
    public static final String SLOT_EMBEDDING = "embedding";
    public static final String SLOT_IMAGE_GEN = "image_gen";
    public static final String SLOT_VOICE_GEN = "voice_gen";

    public static final List<String> ALL_SLOT_CODES = List.of(
            SLOT_CHAT_MAIN, SLOT_TASK_WORKER, SLOT_EVAL_L1, SLOT_EVAL_JUDGE,
            SLOT_SUMMARIZE, SLOT_VISION_DESC, SLOT_EMBEDDING, SLOT_IMAGE_GEN, SLOT_VOICE_GEN);

    /**
     * 完整性检查缺项（告警只记不阻断启动）
     */
    public record ConfigGap(String kind, String detail) {

        public static final String KIND_SLOT_UNBOUND = "slot_unbound";

        public static final String KIND_PROVIDER_NO_CREDENTIAL = "provider_no_credential";
    }

    /**
     * 槽位解析失败（未绑定 / 环节停用 / 绑定缺失 / gen_params 非法）——上层走既有
     * llm.config_missing 失败路径，不发明新机制
     */
    public static final class SlotResolutionException extends RuntimeException {

        public SlotResolutionException(String message) {
            super(message);
        }
    }

    private record ProviderRow(String code, String dialect, String baseUrl, boolean enabled) {
    }

    private record ModelRow(String providerCode, String modelId,
                            ModelCapabilities capabilities, ModelPrices prices, boolean enabled) {
    }

    private record SlotRow(String providerCode, String modelId, String genParams,
                           boolean enabled, long bindingVersion) {
    }

    private record Snapshot(Map<String, ProviderRow> providers,
                            Map<String, ModelRow> models,
                            Map<String, SlotRow> slots) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of());
    }

    private final ProviderConfigMapper providerMapper;

    private final ModelConfigMapper modelMapper;

    private final ModelSlotMapper slotMapper;

    private final CredentialMapper credentialMapper;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public ModelCatalog(ProviderConfigMapper providerMapper, ModelConfigMapper modelMapper,
                        ModelSlotMapper slotMapper, CredentialMapper credentialMapper) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.slotMapper = slotMapper;
        this.credentialMapper = credentialMapper;
    }

    @PostConstruct
    public void start() {
        refreshInternal();
    }

    /**
     * 配置刷新（与 ToolRegistry 同源事件：管理面/SQL 维护后显式通知 + 周期兜底）
     */
    @EventListener(ToolRegistry.ConfigRefreshEvent.class)
    public void refresh() {
        refreshInternal();
    }

    /**
     * 槽位解析：三环校验产出 ModelRef（失败抛 {@link SlotResolutionException}——
     * gen_params 的合法性也在本方法校验，SlotResolver 侧只做参数映射）
     */
    public ModelRef resolveSlot(String slotCode) {
        Snapshot current = snapshot;
        SlotRow slot = current.slots().get(slotCode);
        if (slot == null) {
            throw new SlotResolutionException("槽位未绑定 slot=" + slotCode);
        }
        if (!slot.enabled()) {
            throw new SlotResolutionException("槽位已停用 slot=" + slotCode);
        }
        if (!isLegalGenParams(slot.genParams())) {
            throw new SlotResolutionException("槽位 gen_params 非法 slot=" + slotCode);
        }
        ModelRow model = current.models().get(modelKey(slot.providerCode(), slot.modelId()));
        if (model == null) {
            throw new SlotResolutionException(
                    "槽位绑定的模型不存在 slot=" + slotCode + " model=" + slot.modelId());
        }
        if (!model.enabled()) {
            throw new SlotResolutionException(
                    "槽位绑定的模型已停用 slot=" + slotCode + " model=" + slot.modelId());
        }
        ProviderRow provider = current.providers().get(slot.providerCode());
        if (provider == null) {
            throw new SlotResolutionException(
                    "槽位绑定的供应商不存在 slot=" + slotCode + " provider=" + slot.providerCode());
        }
        if (!provider.enabled()) {
            throw new SlotResolutionException(
                    "槽位绑定的供应商已停用 slot=" + slotCode + " provider=" + slot.providerCode());
        }
        return new ModelRef(provider.dialect(), provider.code(), model.modelId(),
                provider.baseUrl(), model.capabilities(), model.prices(), slotCode);
    }

    /**
     * 槽位 gen_params 原文（{@link #resolveSlot} 已校验其 JSON 合法性——两方法读同一
     * 快照，刷新间隙极端时序下取到的也是合法值）
     */
    public String genParamsOf(String slotCode) {
        SlotRow slot = snapshot.slots().get(slotCode);
        return slot == null ? null : slot.genParams();
    }

    /**
     * 槽位绑定版本（观测侧版本三键之一）：绑定行 updated_at 的纪元毫秒——槽位表无
     * 自增版本列，变更时间即版本；未绑定为 0
     */
    public long bindingVersionOf(String slotCode) {
        SlotRow slot = snapshot.slots().get(slotCode);
        return slot == null ? 0 : slot.bindingVersion();
    }

    /**
     * DATETIME(3) → 纪元毫秒（连接时区 Asia/Shanghai——与 3.4.5 时间规约一致）
     */
    private static long epochMillis(java.time.LocalDateTime time) {
        return time == null ? 0
                : time.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    /**
     * 模型单价（成本折算取数；未知模型返回 null——落账侧按单价缺失落 null 告警）
     */
    public ModelPrices pricesOf(String providerCode, String modelId) {
        ModelRow model = snapshot.models().get(modelKey(providerCode, modelId));
        return model == null ? null : model.prices();
    }

    /**
     * 完整性检查：槽位未绑定逐条 + 有 enabled 模型但缺 available 凭证的供应商逐条
     */
    public List<ConfigGap> configGaps() {
        List<ConfigGap> gaps = new ArrayList<>();
        Snapshot current = snapshot;
        for (String slotCode : ALL_SLOT_CODES) {
            if (!current.slots().containsKey(slotCode)) {
                gaps.add(new ConfigGap(ConfigGap.KIND_SLOT_UNBOUND, "槽位未绑定 slot=" + slotCode));
            }
        }
        for (ProviderRow provider : current.providers().values()) {
            if (!provider.enabled() || !hasEnabledModel(current, provider.code())) {
                continue;
            }
            boolean hasAvailable = credentialMapper.selectByOwner("provider", provider.code())
                    .stream().anyMatch(row -> "available".equals(row.getStatus()));
            if (!hasAvailable) {
                gaps.add(new ConfigGap(ConfigGap.KIND_PROVIDER_NO_CREDENTIAL,
                        "供应商有 enabled 模型但缺 available 凭证 provider=" + provider.code()));
            }
        }
        return gaps;
    }

    private void refreshInternal() {
        Snapshot fresh = loadSnapshot();
        this.snapshot = fresh;
        for (ConfigGap gap : configGaps()) {
            log.warn("模型配置完整性缺项 kind={} detail={}", gap.kind(), gap.detail());
        }
    }

    private Snapshot loadSnapshot() {
        Map<String, ProviderRow> providers = new LinkedHashMap<>();
        for (var row : providerMapper.selectAll()) {
            providers.put(row.getCode(), new ProviderRow(
                    row.getCode(), row.getDialect(), row.getBaseUrl(),
                    Boolean.TRUE.equals(row.getEnabled())));
        }
        Map<String, ModelRow> models = new LinkedHashMap<>();
        for (var row : modelMapper.selectAll()) {
            try {
                models.put(modelKey(row.getProviderCode(), row.getModelId()), new ModelRow(
                        row.getProviderCode(), row.getModelId(),
                        parseCapability(row.getCapability()), parsePrice(row.getPrice()),
                        Boolean.TRUE.equals(row.getEnabled())));
            } catch (Exception e) {
                // 非法行告警跳过，不拖垮其他模型（data-model 第 7 节）
                log.warn("model_config 行装载跳过（capability/price 非法） provider={} model={}：{}",
                        row.getProviderCode(), row.getModelId(), e.getMessage());
            }
        }
        Map<String, SlotRow> slots = new LinkedHashMap<>();
        for (var row : slotMapper.selectAll()) {
            slots.put(row.getSlotCode(), new SlotRow(
                    row.getProviderCode(), row.getModelId(), row.getGenParams(),
                    Boolean.TRUE.equals(row.getEnabled()),
                    epochMillis(row.getUpdatedAt())));
        }
        return new Snapshot(Map.copyOf(providers), Map.copyOf(models), Map.copyOf(slots));
    }

    private static boolean hasEnabledModel(Snapshot snapshot, String providerCode) {
        return snapshot.models().values().stream()
                .anyMatch(model -> model.providerCode().equals(providerCode) && model.enabled());
    }

    /**
     * gen_params JSON 合法性校验装载用：非 JSON 对象即非法
     */
    static boolean isLegalGenParams(String genParams) {
        try {
            return JsonMappers.shared().readTree(genParams).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private static ModelCapabilities parseCapability(String json) {
        JsonNode node = readObject(json, "capability");
        return new ModelCapabilities(
                node.path("vision").asBoolean(false),
                node.path("toolUse").asBoolean(false),
                node.path("contextWindow").asInt(0),
                node.path("maxOutputTokens").asInt(0));
    }

    private static ModelPrices parsePrice(String json) {
        JsonNode node = readObject(json, "price");
        return new ModelPrices(
                decimalOf(node.path("input")),
                decimalOf(node.path("output")),
                decimalOf(node.path("cached")));
    }

    private static BigDecimal decimalOf(JsonNode node) {
        return node.isNumber() || node.isTextual() ? node.decimalValue() : null;
    }

    private static JsonNode readObject(String json, String column) {
        JsonNode node;
        try {
            node = JsonMappers.shared().readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(column + " 列不是合法 JSON：" + e.getMessage());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(column + " 列不是 JSON 对象");
        }
        return node;
    }

    private static String modelKey(String providerCode, String modelId) {
        return providerCode + "|" + modelId;
    }
}
