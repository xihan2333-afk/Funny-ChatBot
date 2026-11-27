import {buildQuery, http, type PageResult} from './http'

// ===== LLM / Slots / Credentials / Reembed =====

export interface ProviderItem {
    id: number
    code: string
    displayName: string
    dialect: string
    baseUrl: string
    enabled: boolean
    availableCredentialCount: number
}

export interface ModelItem {
    id: number
    providerCode: string
    modelId: string
    displayName: string
    capabilityJson: string
    priceJson: string
    enabled: boolean
}

export interface SlotItem {
    slotCode: string
    providerCode?: string | null
    modelId?: string | null
    genParamsJson: string
    enabled: boolean
    updatedAt?: string | null
}

export interface CompletenessView {
    complete: boolean
    unboundSlots: string[]
    providersMissingCredentials: string[]
    slots: SlotItem[]
}

export interface CredentialItem {
    id: number
    ownerKind: string
    ownerCode: string
    keyMask: string
    status: 'available' | 'cooldown' | 'disabled' | string
    cooldownUntil?: string | null
    updatedAt?: string | null
}

export interface ReembedJobItem {
    id: number
    fromModelId?: number | null
    toModelId: number
    fromDimension?: number | null
    toDimension: number
    status: string
    totalCount: number
    processedCount: number
    failedCount: number
    errorMessage?: string | null
    operator: string
    startedAt?: string | null
    finishedAt?: string | null
    createdAt?: string | null
}

export const llmApi = {
    completeness: () => http.get<CompletenessView>('/api/management/llm/completeness'),
    listProviders: () => http.get<ProviderItem[]>('/api/management/llm/providers'),
    createProvider: (body: Partial<ProviderItem>) => http.post<ProviderItem>('/api/management/llm/providers', body),
    updateProvider: (id: number, body: Partial<ProviderItem>) => http.put<ProviderItem>(`/api/management/llm/providers/${id}`, body),
    listModels: (providerCode?: string) => http.get<ModelItem[]>(`/api/management/llm/models${buildQuery({providerCode})}`),
    createModel: (body: Partial<ModelItem>) => http.post<ModelItem>('/api/management/llm/models', body),
    updateModel: (id: number, body: Partial<ModelItem>) => http.put<ModelItem>(`/api/management/llm/models/${id}`, body),
    listSlots: () => http.get<SlotItem[]>('/api/management/llm/slots'),
    updateSlot: (slotName: string, body: {
        providerCode: string;
        modelId: string;
        genParamsJson: string;
        enabled: boolean
    }) =>
        http.put<SlotItem>(`/api/management/llm/slots/${encodeURIComponent(slotName)}`, body, {silent: true}),
    startReembed: (targetModelId: number, confirm: boolean) =>
        http.post<ReembedJobItem>('/api/management/llm/embedding/reembed', {targetModelId, confirm}),
    listReembedJobs: (page = 1, pageSize = 20) =>
        http.get<PageResult<ReembedJobItem>>(`/api/management/llm/embedding/reembed/jobs${buildQuery({
            page,
            pageSize
        })}`),
    getReembedJob: (jobId: number) => http.get<ReembedJobItem>(`/api/management/llm/embedding/reembed/jobs/${jobId}`),
    listCredentials: (ownerKind?: string, ownerCode?: string) =>
        http.get<CredentialItem[]>(`/api/management/llm/credentials${buildQuery({ownerKind, ownerCode})}`),
    addCredential: (body: { ownerKind: string; ownerCode: string; secretPlain: string }) =>
        http.post<CredentialItem>('/api/management/llm/credentials', body),
    setCredentialEnabled: (id: number, enabled: boolean) =>
        http.put<void>(`/api/management/llm/credentials/${id}/enabled${buildQuery({enabled})}`),
    deleteCredential: (id: number) => http.delete<void>(`/api/management/llm/credentials/${id}`),
}

// ===== Tools =====

export interface BuiltinToolItem {
    name: string
    description: string
    parametersJson: string
    speed: string
    enabled: boolean
    paramsJson?: string | null
    status: string
    versionNo: number
    updatedAt?: string | null
}

export interface HttpToolItem {
    id?: number
    toolName: string
    description: string
    parametersJson: string
    method: string
    urlTemplate: string
    bodyTemplate?: string | null
    headersJson?: string | null
    credentialId?: number | null
    maskedCredential?: string | null
    extractPath?: string | null
    maxResponseBytes: number
    timeoutMillis: number
    speed: string
    enabled: boolean
    status: string
    versionNo: number
}

export interface DraftSaveResult {
    id: number
    status: string
    versionNo: number
    warnings?: string[]
}

export interface VersionItem {
    id: number
    versionNo: number
    snapshotJson: string
    createdAt?: string | null
}

export const toolApi = {
    listBuiltin: () => http.get<BuiltinToolItem[]>('/api/management/tools/builtin'),
    saveBuiltinDraft: (toolName: string, body: { enabled: boolean; paramsJson: string }) =>
        http.put<DraftSaveResult>(`/api/management/tools/builtin/${encodeURIComponent(toolName)}`, body),
    publishBuiltin: (toolName: string, overrideReason: string | null) =>
        http.post<void>(`/api/management/tools/builtin/${encodeURIComponent(toolName)}/publish`, {overrideReason}),
    rollbackBuiltin: (toolName: string, targetVersion: number) =>
        http.post<void>(`/api/management/tools/builtin/${encodeURIComponent(toolName)}/rollback`, {targetVersion}),
    builtinVersions: (toolName: string) =>
        http.get<VersionItem[]>(`/api/management/tools/builtin/${encodeURIComponent(toolName)}/versions`),

    listHttp: () => http.get<HttpToolItem[]>('/api/management/tools/http'),
    createHttpDraft: (body: Partial<HttpToolItem>) => http.post<DraftSaveResult>('/api/management/tools/http', body),
    saveHttpDraft: (toolName: string, body: Partial<HttpToolItem>) =>
        http.put<DraftSaveResult>(`/api/management/tools/http/${encodeURIComponent(toolName)}`, body),
    publishHttp: (toolName: string, overrideReason: string | null) =>
        http.post<void>(`/api/management/tools/http/${encodeURIComponent(toolName)}/publish`, {overrideReason}),
    rollbackHttp: (toolName: string, targetVersion: number) =>
        http.post<void>(`/api/management/tools/http/${encodeURIComponent(toolName)}/rollback`, {targetVersion}),
    httpVersions: (toolName: string) =>
        http.get<VersionItem[]>(`/api/management/tools/http/${encodeURIComponent(toolName)}/versions`),
    deleteHttp: (toolName: string) => http.delete<void>(`/api/management/tools/http/${encodeURIComponent(toolName)}`),
}

// ===== Skills =====

export interface SkillSummary {
    id: number
    name: string
    description: string
    enabled: boolean
    status: string
    versionNo: number
    referencedGroupCount: number
    updatedAt?: string | null
}

export interface SkillDetail extends SkillSummary {
    content: string
}

export const skillApi = {
    list: (page = 1, pageSize = 20) =>
        http.get<PageResult<SkillSummary>>(`/api/management/skills${buildQuery({page, pageSize})}`),
    get: (name: string) => http.get<SkillDetail>(`/api/management/skills/${encodeURIComponent(name)}`),
    createDraft: (body: { name: string; description: string; content: string; enabled: boolean }) =>
        http.post<DraftSaveResult>('/api/management/skills', body),
    saveDraft: (name: string, body: { name: string; description: string; content: string; enabled: boolean }) =>
        http.put<DraftSaveResult>(`/api/management/skills/${encodeURIComponent(name)}`, body),
    preview: (name: string) =>
        http.post<{
            directoryLine: string;
            fullContent: string
        }>(`/api/management/skills/${encodeURIComponent(name)}/preview`),
    publish: (name: string, overrideReason: string | null) =>
        http.post<void>(`/api/management/skills/${encodeURIComponent(name)}/publish`, {overrideReason}),
    rollback: (name: string, targetVersion: number) =>
        http.post<void>(`/api/management/skills/${encodeURIComponent(name)}/rollback`, {targetVersion}),
    versions: (name: string) =>
        http.get<VersionItem[]>(`/api/management/skills/${encodeURIComponent(name)}/versions`),
    setEnabled: (name: string, enabled: boolean) =>
        http.put<void>(`/api/management/skills/${encodeURIComponent(name)}/enabled${buildQuery({enabled})}`),
    delete: (name: string) => http.delete<void>(`/api/management/skills/${encodeURIComponent(name)}`),
}

// ===== Prompts =====

export interface PromptFragmentItem {
    id: number
    fragmentKey: string
    layer: string
    formFit: string
    title: string
    content: string
    variables: string[]
    builtinLocked: boolean
    lockReason?: string | null
    status: string
    versionNo: number
    updatedAt?: string | null
}

export const promptApi = {
    listGrouped: () => http.get<Record<string, PromptFragmentItem[]>>('/api/management/prompts'),
    get: (key: string) => http.get<PromptFragmentItem>(`/api/management/prompts/${encodeURIComponent(key)}`),
    createDraft: (body: Partial<PromptFragmentItem>) => http.post<DraftSaveResult>('/api/management/prompts', body),
    saveDraft: (key: string, body: Partial<PromptFragmentItem>) =>
        http.put<DraftSaveResult>(`/api/management/prompts/${encodeURIComponent(key)}`, body),
    publish: (key: string, overrideReason: string | null) =>
        http.post<void>(`/api/management/prompts/${encodeURIComponent(key)}/publish`, {overrideReason}),
    rollback: (key: string, targetVersion: number) =>
        http.post<void>(`/api/management/prompts/${encodeURIComponent(key)}/rollback`, {targetVersion}),
    versions: (key: string) => http.get<VersionItem[]>(`/api/management/prompts/${encodeURIComponent(key)}/versions`),
    delete: (key: string) => http.delete<void>(`/api/management/prompts/${encodeURIComponent(key)}`),
}

// ===== Personas =====

export interface PersonaSummary {
    id: number
    name: string
    isDefault: boolean
    status: string
    versionNo: number
    boundGroupCount: number
    updatedAt?: string | null
}

export interface PersonaDetail {
    id: number
    name: string
    definitionJson: string
    isDefault: boolean
    status: string
    versionNo: number
    updatedAt?: string | null
}

export interface PersonaProposalItem {
    id: number
    personaId: number
    status: string
    periodStart?: string | null
    periodEnd?: string | null
    signalsDigestJson: string
    proposalItemsJson: string
    reviewer?: string | null
    reviewRemark?: string | null
    reviewedAt?: string | null
    createdAt?: string | null
}

export interface PersonaFeedbackItem {
    id: number
    personaId: number
    tag: string
    comment: string
    traceId?: string | null
    operator: string
    createdAt?: string | null
}

export const personaApi = {
    list: () => http.get<PersonaSummary[]>('/api/management/personas'),
    get: (id: number) => http.get<PersonaDetail>(`/api/management/personas/${id}`),
    createDraft: (body: { name: string; definitionJson: string; isDefault?: boolean }) =>
        http.post<DraftSaveResult>('/api/management/personas', body),
    saveDraft: (id: number, body: { name?: string; definitionJson: string; isDefault?: boolean }) =>
        http.put<DraftSaveResult>(`/api/management/personas/${id}`, body),
    publish: (id: number, overrideReason: string | null) =>
        http.post<void>(`/api/management/personas/${id}/publish`, {overrideReason}),
    rollback: (id: number, targetVersion: number) =>
        http.post<void>(`/api/management/personas/${id}/rollback`, {targetVersion}),
    versions: (id: number) => http.get<VersionItem[]>(`/api/management/personas/${id}/versions`),
    bindings: (id: number) =>
        http.get<{ boundGroups: any[]; histories: any[] }>(`/api/management/personas/${id}/bindings`),
    listProposals: (id: number, status?: string) =>
        http.get<PersonaProposalItem[]>(`/api/management/personas/${id}/proposals${buildQuery({status})}`),
    triggerEvolution: (id: number) => http.post<void>(`/api/management/personas/${id}/evolution/trigger`),
    reviewProposal: (proposalId: number, body: { decision: string; editedItemsJson?: string; remark?: string }) =>
        http.post<void>(`/api/management/personas/proposals/${proposalId}/review`, body),
    listFeedbacks: (id: number) => http.get<PersonaFeedbackItem[]>(`/api/management/personas/${id}/feedback`),
    addFeedback: (
        id: number,
        body: { platform?: string; botId?: string; groupId?: string; tag: string; comment: string; traceId?: string },
    ) => http.post<PersonaFeedbackItem>(`/api/management/personas/${id}/feedback`, body),
}
