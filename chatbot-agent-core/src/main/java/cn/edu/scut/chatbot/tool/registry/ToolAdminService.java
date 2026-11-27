package cn.edu.scut.chatbot.tool.registry;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.HttpToolConfigDO;
import cn.edu.scut.chatbot.engine.intake.HttpToolConfigMapper;
import cn.edu.scut.chatbot.engine.intake.ToolConfigDO;
import cn.edu.scut.chatbot.engine.intake.ToolConfigMapper;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.management.ToolAdminFacades;
import cn.edu.scut.chatbot.observability.flywheel.PublishGateService;
import cn.edu.scut.chatbot.prompt.ContentVersionDO;
import cn.edu.scut.chatbot.prompt.ContentVersioningService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 工具管理门面实现（位于 tool.registry，满足 ArchUnit notIntoTool 规则）。
 */
@Service
public class ToolAdminService implements ToolAdminFacades {

    private static final Pattern PRIVATE_HOST_PATTERN = Pattern.compile(
            "(?i)(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|169\\.254\\.\\d+\\.\\d+)"
    );

    private final List<AgentTool> builtinTools;
    private final ToolConfigMapper toolConfigMapper;
    private final HttpToolConfigMapper httpToolConfigMapper;
    private final CredentialMapper credentialMapper;
    private final ContentVersioningService versioningService;
    private final PublishGateService publishGateService;

    public ToolAdminService(List<AgentTool> builtinTools,
                            ToolConfigMapper toolConfigMapper,
                            HttpToolConfigMapper httpToolConfigMapper,
                            CredentialMapper credentialMapper,
                            ContentVersioningService versioningService,
                            PublishGateService publishGateService) {
        this.builtinTools = builtinTools;
        this.toolConfigMapper = toolConfigMapper;
        this.httpToolConfigMapper = httpToolConfigMapper;
        this.credentialMapper = credentialMapper;
        this.versioningService = versioningService;
        this.publishGateService = publishGateService;
    }

    @Override
    public List<BuiltinToolView> listBuiltinTools() {
        List<BuiltinToolView> result = new ArrayList<>();
        for (AgentTool tool : builtinTools) {
            ToolDescriptor d = tool.descriptor();
            ToolConfigDO cfg = toolConfigMapper.selectByName(d.name());
            String paramsJson = d.parametersJsonSchema();
            result.add(new BuiltinToolView(
                    d.name(),
                    d.description(),
                    paramsJson != null ? paramsJson : "{}",
                    d.speed() != null ? d.speed().name() : "FAST",
                    cfg == null || cfg.getEnabled() == null || cfg.getEnabled() == 1,
                    cfg != null ? cfg.getParams() : "{}",
                    cfg != null && cfg.getStatus() != null ? cfg.getStatus() : "published",
                    cfg != null && cfg.getVersionNo() != null ? cfg.getVersionNo() : 1,
                    cfg != null && cfg.getUpdatedAt() != null ? cfg.getUpdatedAt().toString() : null
            ));
        }
        return result;
    }

    @Override
    public DraftSaveResult saveBuiltinDraft(String toolName, BuiltinToolUpdateRequest request) {
        boolean exists = builtinTools.stream().anyMatch(t -> t.descriptor().name().equals(toolName));
        if (!exists) {
            throw new ChatBotException("mgmt.not_found", "内置工具不存在：" + toolName);
        }
        ToolConfigDO saved = versioningService.saveToolConfigDraft(
                toolName, request.enabled(), request.paramsJson() != null ? request.paramsJson() : "{}");
        return new DraftSaveResult(saved.getId(), saved.getStatus(), saved.getVersionNo(), List.of());
    }

    @Override
    public void publishBuiltin(String toolName, String overrideReason) {
        publishGateService.publish("tool_config:" + toolName, overrideReason);
    }

    @Override
    public void rollbackBuiltin(String toolName, int targetVersion, String overrideReason) {
        versioningService.rollbackToolConfig(toolName, targetVersion);
    }

    @Override
    public List<VersionItemView> builtinVersions(String toolName) {
        return toVersionViews(versioningService.toolConfigVersions(toolName));
    }

    @Override
    public List<HttpToolView> listHttpTools() {
        return httpToolConfigMapper.selectAll().stream()
                .map(this::toHttpToolView)
                .toList();
    }

    @Override
    public DraftSaveResult saveHttpToolDraft(String toolName, HttpToolUpsertRequest request) {
        String name = (toolName != null && !toolName.isBlank()) ? toolName : request.toolName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        boolean collidesBuiltin = builtinTools.stream().anyMatch(t -> t.descriptor().name().equals(name));
        if (collidesBuiltin) {
            throw new CommandRejectedException("工具名与内置工具重名：" + name);
        }
        validateJsonObject(request.parametersJson(), "parameters");

        List<String> warnings = new ArrayList<>();
        if (request.urlTemplate() != null && PRIVATE_HOST_PATTERN.matcher(request.urlTemplate()).find()) {
            warnings.add("urlTemplate 命中私有网段或本地回环地址字面量，运行时将被 SafeFetcher SSRF 防线拦截");
        }

        HttpToolConfigDO row = new HttpToolConfigDO();
        row.setToolName(name);
        row.setDescription(request.description());
        row.setParameters(request.parametersJson() != null ? request.parametersJson() : "{}");
        row.setMethod(request.method() != null ? request.method().toUpperCase(Locale.ROOT) : "GET");
        row.setUrlTemplate(request.urlTemplate());
        row.setBodyTemplate(request.bodyTemplate());
        row.setHeaders(request.headersJson());
        row.setCredentialId(request.credentialId());
        row.setExtractPath(request.extractPath());
        row.setMaxResponseBytes(request.maxResponseBytes() != null ? request.maxResponseBytes() : 4096);
        row.setTimeoutMillis(request.timeoutMillis() != null ? request.timeoutMillis() : 3000);
        row.setSpeed("SLOW".equalsIgnoreCase(request.speed()) ? "SLOW" : "FAST");
        row.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);

        HttpToolConfigDO saved = versioningService.saveHttpToolConfigDraft(row);
        return new DraftSaveResult(saved.getId(), saved.getStatus(), saved.getVersionNo(), warnings);
    }

    @Override
    public void publishHttpTool(String toolName, String overrideReason) {
        publishGateService.publish("http_tool_config:" + toolName, overrideReason);
    }

    @Override
    public void rollbackHttpTool(String toolName, int targetVersion, String overrideReason) {
        versioningService.rollbackHttpToolConfig(toolName, targetVersion);
    }

    @Override
    public List<VersionItemView> httpToolVersions(String toolName) {
        return toVersionViews(versioningService.httpToolConfigVersions(toolName));
    }

    @Override
    public void deleteHttpTool(String toolName) {
        HttpToolConfigDO existing = httpToolConfigMapper.selectByName(toolName);
        if (existing == null) {
            throw new ChatBotException("mgmt.not_found", "HTTP 工具不存在：" + toolName);
        }
        if (existing.getEnabled() != null && existing.getEnabled() == 1
                && "published".equalsIgnoreCase(existing.getStatus())) {
            throw new CommandRejectedException("请先停用该 HTTP 工具后再删除");
        }
        httpToolConfigMapper.deleteByName(toolName);
    }

    private HttpToolView toHttpToolView(HttpToolConfigDO c) {
        String masked = null;
        if (c.getCredentialId() != null) {
            CredentialDO cred = credentialMapper.selectById(c.getCredentialId());
            if (cred != null) {
                masked = cred.getKeyMask();
            }
        }
        return new HttpToolView(
                c.getId(), c.getToolName(), c.getDescription(), c.getParameters(),
                c.getMethod(), c.getUrlTemplate(), c.getBodyTemplate(), c.getHeaders(),
                c.getCredentialId(), masked, c.getExtractPath(), c.getMaxResponseBytes(),
                c.getTimeoutMillis(), c.getSpeed() != null ? c.getSpeed() : "FAST",
                c.getEnabled() != null && c.getEnabled() == 1,
                c.getStatus() != null ? c.getStatus() : "published",
                c.getVersionNo() != null ? c.getVersionNo() : 1,
                c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null
        );
    }

    private static List<VersionItemView> toVersionViews(List<ContentVersionDO> versions) {
        return versions.stream()
                .map(v -> new VersionItemView(
                        v.getId(), v.getVersionNo(), v.getSnapshot(),
                        v.getCreatedAt() != null ? v.getCreatedAt().toString() : null))
                .toList();
    }

    private static void validateJsonObject(String json, String fieldName) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode node = JsonMappers.shared().readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException(fieldName + " 必须是合法 JSON 对象");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " 不是合法 JSON：" + e.getMessage());
        }
    }
}
