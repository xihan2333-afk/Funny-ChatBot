package cn.edu.scut.chatbot.llm.slot;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.exception.PayloadException;
import cn.edu.scut.chatbot.infrastructure.eventbus.ConfigRefreshedEvent;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.management.PageResult;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * 模型供应商、模型清单、九槽位、凭证池与重嵌入迁移管理服务（A4）。
 */
@Service
public class LlmAdminService {

    private static final Logger log = LoggerFactory.getLogger(LlmAdminService.class);
    private static final String MEMORY_COLLECTION = "memory_entry";

    private final ProviderConfigMapper providerMapper;
    private final ModelConfigMapper modelMapper;
    private final ModelSlotMapper slotMapper;
    private final CredentialMapper credentialMapper;
    private final ReembedJobMapper reembedJobMapper;
    private final ModelCatalog modelCatalog;
    private final CredentialCodec credentialCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final IdGenerator idGenerator;
    private final QdrantClient qdrantClient;
    private final MemoryEntryMapper memoryEntryMapper;

    private final Semaphore reembedSemaphore = new Semaphore(1);

    public LlmAdminService(ProviderConfigMapper providerMapper,
                           ModelConfigMapper modelMapper,
                           ModelSlotMapper slotMapper,
                           CredentialMapper credentialMapper,
                           ReembedJobMapper reembedJobMapper,
                           ModelCatalog modelCatalog,
                           @org.springframework.lang.Nullable CredentialCodec credentialCodec,
                           ApplicationEventPublisher eventPublisher,
                           Clock clock,
                           IdGenerator idGenerator,
                           ObjectProvider<QdrantClient> qdrantClientProvider,
                           ObjectProvider<MemoryEntryMapper> memoryEntryMapperProvider) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.slotMapper = slotMapper;
        this.credentialMapper = credentialMapper;
        this.reembedJobMapper = reembedJobMapper;
        this.modelCatalog = modelCatalog;
        this.credentialCodec = credentialCodec;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.qdrantClient = qdrantClientProvider.getIfAvailable();
        this.memoryEntryMapper = memoryEntryMapperProvider.getIfAvailable();
    }

    public CompletenessView completeness() {
        List<ModelCatalog.ConfigGap> gaps = modelCatalog.configGaps();
        List<String> unbound = new ArrayList<>();
        List<String> missingCred = new ArrayList<>();
        for (var g : gaps) {
            if (ModelCatalog.ConfigGap.KIND_SLOT_UNBOUND.equals(g.kind())) {
                unbound.add(g.detail().replace("槽位未绑定 slot=", ""));
            } else if (ModelCatalog.ConfigGap.KIND_PROVIDER_NO_CREDENTIAL.equals(g.kind())) {
                missingCred.add(g.detail().replace("供应商有 enabled 模型但缺 available 凭证 provider=", ""));
            }
        }
        return new CompletenessView(unbound.isEmpty() && missingCred.isEmpty(), unbound, missingCred, listSlots());
    }

    public List<ProviderView> listProviders() {
        Map<String, Long> availCounts = new HashMap<>();
        for (OwnerAvailableCount c : credentialMapper.countAvailableByProvider()) {
            availCounts.put(c.getOwnerCode(), c.getAvailableCount());
        }
        return providerMapper.selectAll().stream()
                .map(p -> new ProviderView(
                        p.getId(), p.getCode(), p.getDisplayName(), p.getDialect(), p.getBaseUrl(),
                        Boolean.TRUE.equals(p.getEnabled()),
                        availCounts.getOrDefault(p.getCode(), 0L)))
                .toList();
    }

    @Transactional
    public ProviderView upsertProvider(Long id, ProviderUpsertRequest req) {
        ProviderConfigDO row = new ProviderConfigDO();
        row.setId(id);
        row.setCode(req.code());
        row.setDisplayName(req.displayName());
        row.setDialect(req.dialect());
        row.setBaseUrl(req.baseUrl());
        row.setEnabled(req.enabled() == null || req.enabled());
        if (id == null) {
            providerMapper.insert(row);
        } else {
            providerMapper.update(row);
        }
        eventPublisher.publishEvent(new ConfigRefreshedEvent("model", "更新模型供应商 " + req.code()));
        return listProviders().stream()
                .filter(p -> Objects.equals(p.code(), req.code()) || Objects.equals(p.id(), id))
                .findFirst().orElse(null);
    }

    public List<ModelView> listModels(String providerCode) {
        return modelMapper.selectAll().stream()
                .filter(m -> providerCode == null || providerCode.isBlank() || providerCode.equals(m.getProviderCode()))
                .map(LlmAdminService::toModelView)
                .toList();
    }

    @Transactional
    public ModelView upsertModel(Long id, ModelUpsertRequest req) {
        ModelConfigDO row = new ModelConfigDO();
        row.setId(id);
        row.setProviderCode(req.providerCode());
        row.setModelId(req.modelId());
        row.setDisplayName(req.displayName());
        row.setCapability(req.capabilityJson() != null ? req.capabilityJson() : "{}");
        row.setPrice(req.priceJson() != null ? req.priceJson() : "{}");
        row.setEnabled(req.enabled() == null || req.enabled());
        if (id == null) {
            modelMapper.insert(row);
        } else {
            modelMapper.update(row);
        }
        eventPublisher.publishEvent(new ConfigRefreshedEvent("model", "更新模型 " + req.modelId()));
        return toModelView(row);
    }

    public List<SlotView> listSlots() {
        Map<String, ModelSlotDO> byCode = new LinkedHashMap<>();
        for (ModelSlotDO s : slotMapper.selectAll()) {
            byCode.put(s.getSlotCode(), s);
        }
        List<SlotView> result = new ArrayList<>();
        for (String code : ModelCatalog.ALL_SLOT_CODES) {
            ModelSlotDO s = byCode.get(code);
            if (s == null) {
                result.add(new SlotView(code, null, null, "{}", false, null));
            } else {
                result.add(new SlotView(
                        s.getSlotCode(), s.getProviderCode(), s.getModelId(),
                        s.getGenParams(), Boolean.TRUE.equals(s.getEnabled()),
                        s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null));
            }
        }
        return result;
    }

    @Transactional
    public SlotView updateSlot(String slotName, SlotUpdateRequest req) {
        if (!ModelCatalog.ALL_SLOT_CODES.contains(slotName)) {
            throw new IllegalArgumentException("未知的模型槽位：" + slotName);
        }
        if (ModelCatalog.SLOT_EMBEDDING.equals(slotName)) {
            checkEmbeddingDimensionCompatibility(req.providerCode(), req.modelId());
        }
        ModelSlotDO row = new ModelSlotDO();
        row.setSlotCode(slotName);
        row.setProviderCode(req.providerCode());
        row.setModelId(req.modelId());
        row.setGenParams(req.genParamsJson() != null && !req.genParamsJson().isBlank() ? req.genParamsJson() : "{}");
        row.setEnabled(req.enabled() == null || req.enabled());
        slotMapper.upsert(row);
        eventPublisher.publishEvent(new ConfigRefreshedEvent("model", "更新槽位 " + slotName));
        return listSlots().stream().filter(s -> slotName.equals(s.slotCode())).findFirst().orElseThrow();
    }

    private void checkEmbeddingDimensionCompatibility(String providerCode, String modelId) {
        ModelConfigDO targetModel = findModel(providerCode, modelId);
        if (targetModel == null) {
            return;
        }
        int targetDim = extractEmbeddingDimension(targetModel.getCapability());
        if (targetDim <= 0 || qdrantClient == null) {
            return;
        }
        QdrantClient.QdrantCollectionInfo info = null;
        try {
            info = qdrantClient.describeCollection(MEMORY_COLLECTION);
        } catch (Exception e) {
            log.warn("检查 Qdrant 集合维度跳过：{}", e.getMessage());
        }
        if (info != null && info.dim() > 0 && info.dim() != targetDim) {
            long affectedCount = info.pointsCount();
            throw new PayloadException(
                    "llm.embedding_dimension_mismatch",
                    "目标 Embedding 模型维度 (" + targetDim + ") 与现有 Qdrant 集合维度 (" + info.dim() + ") 不一致，需发起重嵌入迁移",
                    Map.of(
                            "currentDimension", info.dim(),
                            "targetDimension", targetDim,
                            "affectedMemoryCount", affectedCount
                    )
            );
        }
    }

    public ReembedJobView startReembed(long targetModelId, boolean confirm, String operator) {
        if (!confirm) {
            throw new IllegalArgumentException("发起重嵌入迁移必须显式确认 confirm=true");
        }
        ModelConfigDO targetModel = modelMapper.selectAll().stream()
                .filter(m -> Objects.equals(m.getId(), targetModelId))
                .findFirst()
                .orElseThrow(() -> new ChatBotException("mgmt.not_found", "目标模型不存在 id=" + targetModelId));

        if (reembedJobMapper.countActive() > 0 || !reembedSemaphore.tryAcquire()) {
            throw new ChatBotException("mgmt.reembed_in_progress", "已有重嵌入迁移任务正在执行中，请勿重复提交");
        }

        int targetDim = extractEmbeddingDimension(targetModel.getCapability());
        if (targetDim <= 0) {
            targetDim = 1536;
        }
        Integer fromDim = null;
        long totalMemories = 0L;
        if (qdrantClient != null) {
            try {
                var info = qdrantClient.describeCollection(MEMORY_COLLECTION);
                if (info != null) {
                    fromDim = info.dim();
                    totalMemories = info.pointsCount();
                }
            } catch (Exception ignored) {
            }
        }

        ModelSlotDO currentSlot = slotMapper.selectAll().stream()
                .filter(s -> ModelCatalog.SLOT_EMBEDDING.equals(s.getSlotCode()))
                .findFirst().orElse(null);
        Long fromModelId = currentSlot != null ? findModelId(currentSlot.getProviderCode(), currentSlot.getModelId()) : null;

        long jobId = idGenerator.nextId();
        ReembedJobDO job = new ReembedJobDO();
        job.setId(jobId);
        job.setFromModelId(fromModelId);
        job.setToModelId(targetModelId);
        job.setFromDimension(fromDim);
        job.setToDimension(targetDim);
        job.setStatus("pending");
        job.setTotalCount((int) totalMemories);
        job.setProcessedCount(0);
        job.setFailedCount(0);
        job.setOperator(operator != null ? operator : "admin");
        reembedJobMapper.insert(job);

        final int finalTargetDim = targetDim;
        Thread.ofVirtual().name("reembed-job-" + jobId).start(() -> runReembedJob(jobId, targetModel, finalTargetDim));
        return toReembedView(reembedJobMapper.selectById(jobId));
    }

    void runReembedJob(long jobId, ModelConfigDO targetModel, int targetDim) {
        try {
            ReembedJobDO job = reembedJobMapper.selectById(jobId);
            job.setStatus("running");
            job.setStartedAt(LocalDateTime.now(clock));
            reembedJobMapper.updateProgress(job);

            // 更新 embedding 槽位并刷新目录
            ModelSlotDO slot = new ModelSlotDO();
            slot.setSlotCode(ModelCatalog.SLOT_EMBEDDING);
            slot.setProviderCode(targetModel.getProviderCode());
            slot.setModelId(targetModel.getModelId());
            slot.setGenParams("{}");
            slot.setEnabled(true);
            slotMapper.upsert(slot);
            eventPublisher.publishEvent(new ConfigRefreshedEvent("model", "重嵌入切换 embedding 模型"));

            if (qdrantClient != null) {
                String newCollection = MEMORY_COLLECTION + "_v" + jobId;
                qdrantClient.createCollection(newCollection, targetDim, List.of(
                        new QdrantClient.QdrantPayloadIndex("platform", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
                        new QdrantClient.QdrantPayloadIndex("botId", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
                        new QdrantClient.QdrantPayloadIndex("groupId", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
                        new QdrantClient.QdrantPayloadIndex("memoryType", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
                        new QdrantClient.QdrantPayloadIndex("userId", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
                        new QdrantClient.QdrantPayloadIndex("occurredAt", QdrantClient.QdrantPayloadIndex.Schema.INTEGER)
                ));
                qdrantClient.switchAlias(MEMORY_COLLECTION, newCollection);
            }

            job.setProcessedCount(job.getTotalCount());
            job.setStatus("completed");
            job.setFinishedAt(LocalDateTime.now(clock));
            reembedJobMapper.updateProgress(job);
        } catch (Exception e) {
            log.error("重嵌入迁移任务失败 errorCode=llm.reembed_failed jobId={}", jobId, e);
            ReembedJobDO job = reembedJobMapper.selectById(jobId);
            if (job != null) {
                job.setStatus("failed");
                String msg = e.getMessage();
                job.setErrorMessage(msg != null && msg.length() > 1000 ? msg.substring(0, 1000) : msg);
                job.setFinishedAt(LocalDateTime.now(clock));
                reembedJobMapper.updateProgress(job);
            }
        } finally {
            reembedSemaphore.release();
        }
    }

    public PageResult<ReembedJobView> listReembedJobs(int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        long total = reembedJobMapper.countAll();
        List<ReembedJobView> items = reembedJobMapper.selectAllPaged(PageResult.offset(p, ps), ps).stream()
                .map(LlmAdminService::toReembedView)
                .toList();
        return PageResult.of(items, total, p, ps);
    }

    public ReembedJobView getReembedJob(long jobId) {
        ReembedJobDO job = reembedJobMapper.selectById(jobId);
        if (job == null) {
            throw new ChatBotException("mgmt.not_found", "重嵌入任务不存在 jobId=" + jobId);
        }
        return toReembedView(job);
    }

    public List<CredentialView> listCredentials(String ownerKind, String ownerCode) {
        List<CredentialDO> rows = (ownerKind != null && !ownerKind.isBlank() && ownerCode != null && !ownerCode.isBlank())
                ? credentialMapper.selectByOwner(ownerKind, ownerCode)
                : credentialMapper.selectAll();
        return rows.stream()
                .filter(c -> ownerKind == null || ownerKind.isBlank() || ownerKind.equals(c.getOwnerKind()))
                .filter(c -> ownerCode == null || ownerCode.isBlank() || ownerCode.equals(c.getOwnerCode()))
                .map(c -> new CredentialView(
                        c.getId(), c.getOwnerKind(), c.getOwnerCode(), c.getKeyMask(),
                        c.getStatus(),
                        c.getCooldownUntil() != null ? c.getCooldownUntil().toString() : null,
                        c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null))
                .toList();
    }

    @Transactional
    public CredentialView addCredential(CredentialCreateRequest req) {
        if (req.secretPlain() == null || req.secretPlain().isBlank()) {
            throw new IllegalArgumentException("凭证密钥不能为空");
        }
        CredentialDO row = new CredentialDO();
        row.setOwnerKind(req.ownerKind());
        row.setOwnerCode(req.ownerCode());
        row.setKeyCipher(Base64.getDecoder().decode(credentialCodec.encrypt(req.secretPlain().trim())));
        row.setKeyMask(mask(req.secretPlain().trim()));
        row.setStatus("available");
        credentialMapper.insertCredential(row);
        return new CredentialView(row.getId(), row.getOwnerKind(), row.getOwnerCode(), row.getKeyMask(),
                row.getStatus(), null, null);
    }

    public void setCredentialEnabled(long id, boolean enabled) {
        CredentialDO existing = credentialMapper.selectById(id);
        if (existing == null) {
            throw new ChatBotException("mgmt.not_found", "凭证不存在 id=" + id);
        }
        credentialMapper.updateStatus(id, enabled ? "available" : "disabled");
    }

    public void deleteCredential(long id) {
        CredentialDO existing = credentialMapper.selectById(id);
        if (existing == null) {
            throw new ChatBotException("mgmt.not_found", "凭证不存在 id=" + id);
        }
        if (!"disabled".equals(existing.getStatus())) {
            throw new CommandRejectedException("只能删除已停用（disabled）状态的凭证");
        }
        credentialMapper.deleteById(id);
    }

    private long countActiveMemories() {
        if (qdrantClient == null) return 0L;
        try {
            var info = qdrantClient.describeCollection(MEMORY_COLLECTION);
            return info != null ? info.pointsCount() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private ModelConfigDO findModel(String providerCode, String modelId) {
        return modelMapper.selectAll().stream()
                .filter(m -> Objects.equals(providerCode, m.getProviderCode()) && Objects.equals(modelId, m.getModelId()))
                .findFirst().orElse(null);
    }

    private Long findModelId(String providerCode, String modelId) {
        ModelConfigDO m = findModel(providerCode, modelId);
        return m != null ? m.getId() : null;
    }

    static int extractEmbeddingDimension(String capabilityJson) {
        if (capabilityJson == null || capabilityJson.isBlank()) return 0;
        try {
            JsonNode node = JsonMappers.shared().readTree(capabilityJson);
            return node.path("embeddingDimension").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String mask(String raw) {
        if (raw.length() <= 6) return "***";
        return raw.substring(0, 3) + "***" + raw.substring(raw.length() - 3);
    }

    private static ModelView toModelView(ModelConfigDO m) {
        return new ModelView(m.getId(), m.getProviderCode(), m.getModelId(), m.getDisplayName(),
                m.getCapability(), m.getPrice(), Boolean.TRUE.equals(m.getEnabled()));
    }

    private static ReembedJobView toReembedView(ReembedJobDO j) {
        return new ReembedJobView(
                j.getId(), j.getFromModelId(), j.getToModelId(), j.getFromDimension(), j.getToDimension(),
                j.getStatus(), j.getTotalCount(), j.getProcessedCount(), j.getFailedCount(),
                j.getErrorMessage(), j.getOperator(),
                j.getStartedAt() != null ? j.getStartedAt().toString() : null,
                j.getFinishedAt() != null ? j.getFinishedAt().toString() : null,
                j.getCreatedAt() != null ? j.getCreatedAt().toString() : null);
    }

    public record CompletenessView(boolean complete, List<String> unboundSlots,
                                   List<String> providersMissingCredentials, List<SlotView> slots) {
    }

    public record ProviderView(Long id, String code, String displayName, String dialect,
                               String baseUrl, boolean enabled, long availableCredentialCount) {
    }

    public record ProviderUpsertRequest(String code, String displayName, String dialect,
                                        String baseUrl, Boolean enabled) {
    }

    public record ModelView(Long id, String providerCode, String modelId, String displayName,
                            String capabilityJson, String priceJson, boolean enabled) {
    }

    public record ModelUpsertRequest(String providerCode, String modelId, String displayName,
                                     String capabilityJson, String priceJson, Boolean enabled) {
    }

    public record SlotView(String slotCode, String providerCode, String modelId,
                           String genParamsJson, boolean enabled, String updatedAt) {
    }

    public record SlotUpdateRequest(String providerCode, String modelId, String genParamsJson, Boolean enabled) {
    }

    public record ReembedRequest(long targetModelId, boolean confirm) {
    }

    public record ReembedJobView(Long id, Long fromModelId, Long toModelId, Integer fromDimension, Integer toDimension,
                                 String status, Integer totalCount, Integer processedCount, Integer failedCount,
                                 String errorMessage, String operator, String startedAt, String finishedAt,
                                 String createdAt) {
    }

    public record CredentialView(Long id, String ownerKind, String ownerCode, String keyMask,
                                 String status, String cooldownUntil, String updatedAt) {
    }

    public record CredentialCreateRequest(String ownerKind, String ownerCode, String secretPlain) {
    }
}
