import {buildQuery, http, type PageResult} from './http'

export interface AccessChannel {
    id: number
    platform: string
    botId: string
    displayName: string
    transportType: string
    endpointUrl?: string | null
    credentialId?: number | null
    maskedCredential?: string | null
    credentialStatus?: string | null
    enabled: boolean
    updatedAt?: string | null
}

export interface ChannelUpsertPayload {
    platform: string
    botId: string
    displayName: string
    transportType: string
    endpointUrl?: string
    credentialId?: number | null
    credentialSecret?: string
    enabled: boolean
}

export interface GroupListItem {
    platform: string
    botId: string
    groupId: string
    groupName?: string | null
    lifecycle: string
    inService: boolean
    inFlightTaskCount: number
    personaId?: number | null
    personaName?: string | null
    updatedAt?: string | null
}

export interface GroupLifecyclePrecheck {
    inFlightTaskCount: number
    activeWorkerCount: number
    hasSession: boolean
    transcriptRetainPolicy: string
}

export interface GroupPersonaHistoryItem {
    id: number
    fromPersonaId?: number | null
    fromPersonaName?: string | null
    toPersonaId: number
    toPersonaName: string
    operator: string
    reason?: string | null
    switchedAt: string
}

export interface ProactiveParams {
    silenceBreakMinutes?: number | null
    minIntervalHours?: number | null
    dailyBudget?: number | null
    activeHoursOverride?: string | null
}

export interface GroupConfig {
    platform: string
    botId: string
    groupId: string
    groupName?: string | null
    personaId?: number | null
    personaName?: string | null
    allowedMemberTaskCategories: string[]
    allowedAutonomousCategories: string[]
    disabledTools: string[]
    toolParamOverrides: Record<string, Record<string, unknown>>
    disabledSkills: string[]
    proactiveParams: ProactiveParams
    userBlacklist: string[]
    groupRules?: string | null
    transcriptRetainPolicy: string
    recentPersonaHistory: GroupPersonaHistoryItem[]
}

export interface AffectedTaskSummary {
    taskId: number
    taskKind: string
    category: string
    mandateSource: string
    status: string
}

export interface MandatePrecheckResult {
    affectedTaskCount: number
    affectedTasks: AffectedTaskSummary[]
}

export function listChannels(): Promise<AccessChannel[]> {
    return http.get<AccessChannel[]>('/api/management/access/channels')
}

export function createChannel(payload: ChannelUpsertPayload): Promise<AccessChannel> {
    return http.post<AccessChannel>('/api/management/access/channels', payload)
}

export function updateChannel(id: number, payload: Partial<ChannelUpsertPayload>): Promise<AccessChannel> {
    return http.put<AccessChannel>(`/api/management/access/channels/${id}`, payload)
}

export function deleteChannel(id: number): Promise<void> {
    return http.delete<void>(`/api/management/access/channels/${id}`)
}

export function listGroups(params: {
    platform?: string
    botId?: string
    lifecycle?: string
    inService?: boolean | ''
    keyword?: string
    page?: number
    pageSize?: number
}): Promise<PageResult<GroupListItem>> {
    return http.get<PageResult<GroupListItem>>(`/api/management/groups${buildQuery(params)}`)
}

export function setGroupInService(
    platform: string,
    botId: string,
    groupId: string,
    inService: boolean,
): Promise<{ groupId: string; inService: boolean }> {
    return http.put(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/service`, {
        inService,
    })
}

export function precheckGroupLifecycle(
    platform: string,
    botId: string,
    groupId: string,
): Promise<GroupLifecyclePrecheck> {
    return http.get(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/lifecycle/precheck`)
}

export function pauseGroup(platform: string, botId: string, groupId: string): Promise<void> {
    return http.post(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/pause`)
}

export function resumeGroup(platform: string, botId: string, groupId: string): Promise<void> {
    return http.post(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/resume`)
}

export function destroyGroup(
    platform: string,
    botId: string,
    groupId: string,
    retainTranscriptToMemory: boolean,
    confirmGroupId: string,
): Promise<{ destroyed: boolean; groupId: string; retainTranscriptToMemory: boolean; notice: string }> {
    return http.post(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/destroy`, {
        retainTranscriptToMemory,
        confirmGroupId,
    })
}

export function getGroupConfig(platform: string, botId: string, groupId: string): Promise<GroupConfig> {
    return http.get(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/config`)
}

export function precheckGroupMandate(
    platform: string,
    botId: string,
    groupId: string,
    payload: Record<string, unknown>,
): Promise<MandatePrecheckResult> {
    return http.post(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/config/precheck`, payload)
}

export function saveGroupConfig(
    platform: string,
    botId: string,
    groupId: string,
    payload: Record<string, unknown>,
): Promise<{ saved: boolean; cancelledTaskCount: number; config: GroupConfig }> {
    return http.put(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/config`, payload)
}

export function listGroupPersonaHistory(
    platform: string,
    botId: string,
    groupId: string,
    page = 1,
    pageSize = 20,
): Promise<PageResult<GroupPersonaHistoryItem>> {
    return http.get(`/api/management/groups/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/persona-history${buildQuery({
        page,
        pageSize
    })}`)
}
