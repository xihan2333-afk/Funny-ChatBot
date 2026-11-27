import {buildQuery, http, type PageResult} from './http'

export interface MemorySummary {
    id: number
    platform: string
    botId: string
    groupId: string
    memoryType: string
    content: string
    userId?: string | null
    strength: number
    status: string
    supersededBy?: number | null
    occurredAt?: string | null
}

export interface MemoryDetail extends MemorySummary {
    keywords?: string | null
    sourceRef?: string | null
    sedimentJob?: {
        id: number;
        sessionId: number;
        startEntryId: number;
        endEntryId: number;
        status: string;
        createdAt: string
    } | null
    transcriptEntries: Array<{ id: number; kind: string; payload: string; occurredAt: string }>
    supersededByEntry?: MemorySummary | null
}

export interface UserProfileItem {
    id: number
    userId: string
    callName?: string | null
    platformName?: string | null
    activityLevel?: string | null
    interestTags?: string | null
    familiarity?: number | null
    rawAffinity: number
    effectiveAffinity: number
    interactDigest?: string | null
    updatedAt?: string | null
}

export interface EmojiMappingItem {
    id: number
    platform: string
    botId: string
    groupId: string
    fingerprint: string
    description: string
    source: string
    updatedAt?: string | null
}

export interface LinkCardItem {
    id: number
    normalizedUrl: string
    domain: string
    title?: string | null
    siteName?: string | null
    summary?: string | null
    source: string
    domainBlocked: boolean
    expiresAt: string
    createdAt?: string | null
}

export interface ErasurePreview {
    platform: string
    botId: string
    groupId: string
    userId: string
    memoryCount: number
    userProfileExists: boolean
    relationCount: number
    transcriptEntryCount: number
}

export const cognitionApi = {
    listMemories: (params: Record<string, unknown>) =>
        http.get<PageResult<MemorySummary>>(`/api/management/cognition/memories${buildQuery(params)}`),
    getMemory: (id: number) => http.get<MemoryDetail>(`/api/management/cognition/memories/${id}`),
    reviseMemory: (id: number, content: string) =>
        http.put<void>(`/api/management/cognition/memories/${id}`, {content}),
    deleteMemory: (id: number) => http.delete<void>(`/api/management/cognition/memories/${id}`),
    restoreMemory: (id: number) => http.post<void>(`/api/management/cognition/memories/${id}/restore`),
    revokeSupersede: (id: number) => http.post<void>(`/api/management/cognition/memories/${id}/revoke-supersede`),

    listUserProfiles: (platform: string, botId: string, groupId: string, page = 1, pageSize = 20) =>
        http.get<PageResult<UserProfileItem>>(
            `/api/management/cognition/profiles/users${buildQuery({platform, botId, groupId, page, pageSize})}`,
        ),
    getGroupProfile: (platform: string, botId: string, groupId: string) =>
        http.get<any>(`/api/management/cognition/profiles/group/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}`),
    listRelations: (platform: string, botId: string, groupId: string, page = 1, pageSize = 20) =>
        http.get<PageResult<any>>(
            `/api/management/cognition/profiles/relations${buildQuery({platform, botId, groupId, page, pageSize})}`,
        ),
    recomputeProfiles: (platform: string, botId: string, groupId: string) =>
        http.post<void>(
            `/api/management/cognition/profiles/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/recompute`,
        ),

    listEmojis: (params: Record<string, unknown>) =>
        http.get<PageResult<EmojiMappingItem>>(`/api/management/cognition/emojis${buildQuery(params)}`),
    correctEmoji: (id: number, description: string) =>
        http.put<EmojiMappingItem>(`/api/management/cognition/emojis/${id}`, {description}),

    listLinkCards: (params: Record<string, unknown>) =>
        http.get<PageResult<LinkCardItem>>(`/api/management/cognition/link-cards${buildQuery(params)}`),
    deleteLinkCard: (id: number) => http.delete<void>(`/api/management/cognition/link-cards/${id}`),

    previewErasure: (platform: string, botId: string, groupId: string, userId: string) =>
        http.get<ErasurePreview>(`/api/management/privacy/preview${buildQuery({platform, botId, groupId, userId})}`),
    eraseUser: (body: { platform: string; botId: string; groupId: string; userId: string; confirm: boolean }) =>
        http.post<any>('/api/management/privacy/erase', body),
}
