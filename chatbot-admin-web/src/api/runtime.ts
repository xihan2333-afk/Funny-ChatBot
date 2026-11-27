import {buildQuery, http, type PageResult} from './http'

export interface RuntimeGroupStatus {
    platform: string
    botId: string
    groupId: string
    groupName?: string | null
    lifecycle: string
    inService: boolean
    runningTurns: number
    runningTasks: number
    activeWorkers: number
    proactiveBudgetUsed: number
    proactiveBudgetLimit: number
    breakerTripped: boolean
}

export interface RuntimeOverview {
    globalBreakerTripped: boolean
    todaySpendUsd: number
    dailyLimitUsd: number
    groups: RuntimeGroupStatus[]
}

export interface TurnSummary {
    id: number
    platform: string
    botId: string
    groupId: string
    status: string
    silentReason?: string | null
    errorCode?: string | null
    acceptedAt: string
    finishedAt?: string | null
}

export interface TurnDetail extends TurnSummary {
    traceId?: string | null
    triggers: Array<{
        id: number;
        triggerKind: string;
        eventType: string;
        decision: string;
        decisionLayer: string;
        decisionReason: string
    }>
    outboxes: Array<{
        id: number;
        type: string;
        status: string;
        receiptSummary?: string | null;
        errorCode?: string | null
    }>
}

export interface TaskSummary {
    id: number
    platform: string
    botId: string
    groupId: string
    taskKind: string
    category: string
    status: string
    mandateSource: string
    scheduleAt?: string | null
    errorCode?: string | null
    createdAt?: string | null
}

export interface TaskDetail extends TaskSummary {
    mandateRef?: string | null
    creatorUserId?: string | null
    snapshot: string
    result?: string | null
    workerAlive: boolean
    progressLogs: Array<{ id: number; kind: string; note: string; createdAt: string }>
}

export interface DecisionItem {
    id: number
    platform: string
    botId: string
    groupId: string
    triggerKind: string
    eventType: string
    decision: string
    decisionLayer: string
    decisionReason?: string | null
    batchDigest?: string | null
    turnId?: number | null
    occurredAt?: string | null
    decidedAt?: string | null
}

export interface GroupEmotionItem {
    platform: string
    botId: string
    groupId: string
    groupName?: string | null
    label: string
    rawIntensity: number
    effectiveIntensity: number
    baselineLabel: string
    baselineIntensity: number
    cause?: string | null
    updatedAt?: string | null
}

export const runtimeApi = {
    overview: () => http.get<RuntimeOverview>('/api/management/runtime/overview'),
    listTurns: (params: Record<string, unknown>) =>
        http.get<PageResult<TurnSummary>>(`/api/management/runtime/turns${buildQuery(params)}`),
    getTurn: (turnId: number) => http.get<TurnDetail>(`/api/management/runtime/turns/${turnId}`),
    listTasks: (params: Record<string, unknown>) =>
        http.get<PageResult<TaskSummary>>(`/api/management/runtime/tasks${buildQuery(params)}`),
    getTask: (taskId: number) => http.get<TaskDetail>(`/api/management/runtime/tasks/${taskId}`),
    cancelTask: (taskId: number, reason: string) =>
        http.post<void>(`/api/management/runtime/tasks/${taskId}/cancel`, {reason}),
    retryTask: (taskId: number) => http.post<void>(`/api/management/runtime/tasks/${taskId}/retry`),
    listDecisions: (params: Record<string, unknown>) =>
        http.get<PageResult<DecisionItem>>(`/api/management/runtime/decisions${buildQuery(params)}`),
    listEmotions: () => http.get<GroupEmotionItem[]>('/api/management/runtime/emotions'),
    resetEmotion: (platform: string, botId: string, groupId: string) =>
        http.post<void>(
            `/api/management/runtime/emotions/${encodeURIComponent(platform)}/${encodeURIComponent(botId)}/${encodeURIComponent(groupId)}/reset`,
        ),
    breakerStatus: () => http.get<any>('/api/management/security/breaker'),
    breakerHistory: () => http.get<any[]>('/api/management/security/breaker/history'),
    releaseBreaker: (scope: string) =>
        http.post<void>(`/api/management/security/breaker/release${buildQuery({scope})}`),
}
