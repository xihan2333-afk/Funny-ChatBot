import {buildQuery, http} from './http'

export interface AlertRuleView {
    id: number
    name: string
    metric: string
    windowMin: number
    threshold: string
    level: string
    enabled: boolean
    builtin?: boolean
}

export interface AlertNotificationView {
    id: number
    ruleId: number
    ruleName: string
    level: string
    summary: string
    readFlag: boolean
    createdAt?: string
}

export function listAlertRules(): Promise<AlertRuleView[]> {
    return http.get<AlertRuleView[]>('/api/management/alert/rules')
}

export function createAlertRule(payload: Record<string, unknown>): Promise<{ id: number }> {
    return http.post<{ id: number }>('/api/management/alert/rules', payload)
}

export function updateAlertRule(id: number, payload: Record<string, unknown>): Promise<void> {
    return http.put<void>(`/api/management/alert/rules/${id}`, payload)
}

export function deleteAlertRule(id: number): Promise<void> {
    return http.delete<void>(`/api/management/alert/rules/${id}`)
}

export function listNotifications(params: {
    level?: string
    unreadOnly?: boolean
    page?: number
    size?: number
}): Promise<AlertNotificationView[]> {
    return http.get<AlertNotificationView[]>(`/api/management/alert/notifications${buildQuery(params)}`)
}

export function acknowledgeNotification(id: number): Promise<void> {
    return http.post<void>(`/api/management/alert/notifications/${id}/ack`)
}
