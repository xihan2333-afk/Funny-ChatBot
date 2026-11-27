import {http} from './http'

export interface ProviderGap {
    providerCode: string
    displayName: string
}

export interface ConfigCompleteness {
    complete: boolean
    unboundSlots: string[]
    providersMissingCredentials: ProviderGap[]
    missingPlatformChannel: boolean
}

export interface OnboardingStep {
    key: string
    title: string
    done: boolean
    route: string
}

export interface OnboardingGuide {
    completed: boolean
    steps: OnboardingStep[]
}

export interface GroupHealthItem {
    platform: string
    botId: string
    groupId: string
    groupName: string
    lifecycle: string
    inService: boolean
    turnCount24h: number
    turnSuccessRate24h: number
}

export interface TodoCounts {
    pendingBlindReviews: number
    pendingPersonaProposals: number
    pendingImprovementProposals: number
    unreadAlerts: number
}

export interface DailySnapshot {
    groupHealth: GroupHealthItem[]
    todayCostUsd: number
    globalBreakerTripped: boolean
    trippedGroupCount: number
    todo: TodoCounts
}

export interface OverviewResponse {
    configCompleteness: ConfigCompleteness
    onboarding: OnboardingGuide
    dailySnapshot: DailySnapshot
}

export function fetchOverview(): Promise<OverviewResponse> {
    return http.get<OverviewResponse>('/api/management/overview')
}
