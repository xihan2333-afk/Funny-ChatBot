import {buildQuery, http, type PageResult} from './http'
import type {TraceSpanItem} from '@/components/TraceWaterfall.vue'

export interface LogItem {
    timestamp?: string
    level?: string
    category?: string
    traceId?: string
    groupId?: string
    message?: string

    [key: string]: unknown
}

export interface LogPage {
    items: LogItem[]
    total: number
}

export interface CostRow {
    dimensionKey: string
    calls: number
    inputTokens: number
    outputTokens: number
    cachedTokens: number
    costAmount: string | number
}

export interface CostReport {
    dim: 'GROUP' | 'SLOT' | 'MODEL' | 'DAY'
    rows: CostRow[]
}

export interface AuditEventView {
    id: number
    eventKind: string
    platform?: string
    botId?: string
    groupId?: string
    userId?: string
    traceId?: string
    detail?: string
    occurredAt?: string
}

export function searchTraces(params: {
    platform?: string
    botId?: string
    groupId?: string
    fromMillis?: number
    toMillis?: number
    slotCode?: string
}): Promise<string[]> {
    return http.get<string[]>(`/api/management/observe/traces${buildQuery(params)}`)
}

export function getTrace(traceId: string): Promise<TraceSpanItem[]> {
    return http.get<TraceSpanItem[]>(`/api/management/observe/trace/${encodeURIComponent(traceId)}`)
}

export function queryLogs(params: {
    fromMillis?: number
    toMillis?: number
    groupId?: string
    traceId?: string
    keyword?: string
    category?: string
    page?: number
    size?: number
}): Promise<LogPage> {
    return http.get<LogPage>(`/api/management/observe/logs${buildQuery(params)}`)
}

export function queryCost(params: {
    dim?: 'GROUP' | 'SLOT' | 'MODEL' | 'DAY'
    fromMillis?: number
    toMillis?: number
    groupId?: string
    platform?: string
    botId?: string
    slotCode?: string
    modelId?: string
}): Promise<CostReport> {
    return http.get<CostReport>(`/api/management/observe/metrics/cost${buildQuery(params)}`)
}

export function queryLatency(params: { fromMillis?: number; toMillis?: number; groupId?: string } = {}) {
    return http.get<Record<string, unknown>>(`/api/management/observe/metrics/latency${buildQuery(params)}`)
}

export function queryQuality(params: { fromMillis?: number; toMillis?: number; groupId?: string } = {}) {
    return http.get<Record<string, unknown>>(`/api/management/observe/metrics/quality${buildQuery(params)}`)
}

export function queryContextMetrics(params: { fromMillis?: number; toMillis?: number; groupId?: string } = {}) {
    return http.get<Record<string, unknown>>(`/api/management/observe/metrics/context${buildQuery(params)}`)
}

export function querySocialMetrics(params: { fromMillis?: number; toMillis?: number; groupId?: string } = {}) {
    return http.get<Record<string, unknown>>(`/api/management/observe/metrics/social${buildQuery(params)}`)
}

export function queryFeedbackMetrics(params: { fromMillis?: number; toMillis?: number; groupId?: string } = {}) {
    return http.get<Record<string, unknown>>(`/api/management/observe/metrics/feedback${buildQuery(params)}`)
}

export function queryAuditEvents(params: {
    eventKind?: string
    platform?: string
    botId?: string
    groupId?: string
    traceId?: string
    keyword?: string
    from?: string
    to?: string
    page?: number
    pageSize?: number
}): Promise<PageResult<AuditEventView>> {
    return http.get<PageResult<AuditEventView>>(`/api/management/security/audit-events${buildQuery(params)}`)
}
