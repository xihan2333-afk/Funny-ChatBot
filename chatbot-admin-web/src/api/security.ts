import {buildQuery, http, type PageResult} from './http'

export interface BlacklistItem {
    id: number
    scope: 'global' | 'group'
    platform?: string | null
    botId?: string | null
    groupId?: string | null
    userId: string
    reason?: string | null
    createdAt?: string | null
}

export interface FilterWordItem {
    id: number
    word: string
    level: string
    source?: string | null
    enabled: boolean
}

export interface InjectionRuleItem {
    id: number
    pattern: string
}

export interface FilterCandidateEvent {
    eventId: number
    platform?: string | null
    botId?: string | null
    groupId?: string | null
    detailJson: string
    traceId?: string | null
    createdAt?: string | null
}

export interface SystemParamItem {
    paramKey: string
    paramValue: string
    description?: string | null
    updatedAt?: string | null
}

export interface RotationLogItem {
    id: number
    operator: string
    status: string
    rotatedCount: number
    skippedCount: number
    errorMessage?: string | null
    startedAt: string
    finishedAt?: string | null
}

export const securityApi = {
    listBlacklist: () => http.get<BlacklistItem[]>('/api/management/security/blacklist'),
    addBlacklist: (body: {
        scope: string;
        platform?: string;
        botId?: string;
        groupId?: string;
        userId: string;
        reason?: string
    }) =>
        http.post<{ id: number }>('/api/management/security/blacklist', body),
    removeBlacklist: (id: number) => http.delete<void>(`/api/management/security/blacklist/${id}`),

    listFilterWords: () => http.get<FilterWordItem[]>('/api/management/security/filter-words'),
    addFilterWord: (body: { word: string; level: string; source?: string }) =>
        http.post<{ id: number }>('/api/management/security/filter-words', body),
    setFilterWordEnabled: (id: number, enabled: boolean) =>
        http.put<void>(`/api/management/security/filter-words/${id}/enabled${buildQuery({enabled})}`),
    removeFilterWord: (id: number) => http.delete<void>(`/api/management/security/filter-words/${id}`),

    getDomainBlacklist: () => http.get<string[]>('/api/management/security/domain-blacklist'),
    updateDomainBlacklist: (domains: string[]) => http.put<void>('/api/management/security/domain-blacklist', domains),

    getThresholds: () => http.get<Record<string, string>>('/api/management/security/thresholds'),
    updateThresholds: (updates: Record<string, string>) => http.put<void>('/api/management/security/thresholds', updates),

    listInjectionRules: () => http.get<InjectionRuleItem[]>('/api/management/security/injection-rules'),
    addInjectionRule: (pattern: string) => http.post<InjectionRuleItem>('/api/management/security/injection-rules', {pattern}),
    updateInjectionRule: (id: number, pattern: string) =>
        http.put<InjectionRuleItem>(`/api/management/security/injection-rules/${id}`, {pattern}),
    deleteInjectionRule: (id: number) => http.delete<void>(`/api/management/security/injection-rules/${id}`),

    listFilterCandidates: (limit = 50) =>
        http.get<FilterCandidateEvent[]>(`/api/management/security/filter-candidates${buildQuery({limit})}`),
    promoteFilterWord: (eventId: number, word?: string, level = 'block') =>
        http.post<{ id: number }>(`/api/management/security/filter-words/from-event/${eventId}`, {word, level}),

    listSystemParams: () => http.get<Record<string, SystemParamItem[]>>('/api/management/system-params'),
    updateSystemParam: (paramKey: string, paramValue: string) =>
        http.put<SystemParamItem>(`/api/management/system-params/${encodeURIComponent(paramKey)}`, {paramValue}),

    rotateCredential: (body: { newMasterKeyBase64?: string; useEnvStagedKey?: boolean; confirm: boolean }) =>
        http.post<RotationLogItem>('/api/management/ops/credential-rotation', body),
    listRotationHistory: (page = 1, pageSize = 20) =>
        http.get<PageResult<RotationLogItem>>(`/api/management/ops/credential-rotation/history${buildQuery({
            page,
            pageSize
        })}`),
    listReembedHistory: (page = 1, pageSize = 20) =>
        http.get<PageResult<any>>(`/api/management/ops/reembed-history${buildQuery({page, pageSize})}`),
}
