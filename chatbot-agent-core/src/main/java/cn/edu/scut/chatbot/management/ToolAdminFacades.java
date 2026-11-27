package cn.edu.scut.chatbot.management;

import java.util.List;

/**
 * 工具管理门面接口与视图 record（依赖反转守卫：management 不依赖 tool 内部实现）。
 */
public interface ToolAdminFacades {

    List<BuiltinToolView> listBuiltinTools();

    DraftSaveResult saveBuiltinDraft(String toolName, BuiltinToolUpdateRequest request);

    void publishBuiltin(String toolName, String overrideReason);

    void rollbackBuiltin(String toolName, int targetVersion, String overrideReason);

    List<VersionItemView> builtinVersions(String toolName);

    List<HttpToolView> listHttpTools();

    DraftSaveResult saveHttpToolDraft(String toolName, HttpToolUpsertRequest request);

    void publishHttpTool(String toolName, String overrideReason);

    void rollbackHttpTool(String toolName, int targetVersion, String overrideReason);

    List<VersionItemView> httpToolVersions(String toolName);

    void deleteHttpTool(String toolName);

    record BuiltinToolView(
            String name,
            String description,
            String parametersJson,
            String speed,
            boolean enabled,
            String paramsJson,
            String status,
            int versionNo,
            String updatedAt
    ) {
    }

    record BuiltinToolUpdateRequest(boolean enabled, String paramsJson) {
    }

    record HttpToolView(
            Long id,
            String toolName,
            String description,
            String parametersJson,
            String method,
            String urlTemplate,
            String bodyTemplate,
            String headersJson,
            Long credentialId,
            String maskedCredential,
            String extractPath,
            Integer maxResponseBytes,
            Integer timeoutMillis,
            String speed,
            boolean enabled,
            String status,
            int versionNo,
            String updatedAt
    ) {
    }

    record HttpToolUpsertRequest(
            String toolName,
            String description,
            String parametersJson,
            String method,
            String urlTemplate,
            String bodyTemplate,
            String headersJson,
            Long credentialId,
            String extractPath,
            Integer maxResponseBytes,
            Integer timeoutMillis,
            String speed,
            Boolean enabled
    ) {
    }

    record DraftSaveResult(Long id, String status, int versionNo, List<String> warnings) {
    }

    record VersionItemView(long id, int versionNo, String snapshotJson, String createdAt) {
    }
}
