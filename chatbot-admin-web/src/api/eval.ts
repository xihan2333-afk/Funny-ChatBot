import {buildQuery, http} from './http'

export interface EvalCaseView {
    id: number
    platform?: string
    botId?: string
    groupId?: string
    caseType: string
    scenario: string
    rubric: string
    rubricVersion: number
    origin: string
    traceRef?: string
    status: string
    updatedAt?: string
}

export interface EvalBatchView {
    id: number
    batchKind: string
    scope: string
    status: string
    report?: string
    costStat?: string
    createdAt?: string
    finishedAt?: string
}

export interface EvalBatchSummaryView {
    batchId: number
    batchKind: string
    status: string
    totalCount: number
    passedCount: number
    failedCount: number
    passRate: number
    avgTotalScore: number
    costStat?: string
    finishedAt?: string
}

export interface EvalResultView {
    id: number
    batchId: number
    caseId?: number
    sourceKind: string
    platform?: string
    botId?: string
    groupId?: string
    turnId?: number
    traceId?: string
    score: string
    decision: string
    explanation?: string
    judgeModel?: string
    attribution?: string
    createdAt?: string
}

export interface ProposalView {
    id: number
    sourceKind: string
    problemCard?: string
    target?: string
    status: string
    attribution?: string
    reviewNote?: string
    createdAt?: string
    updatedAt?: string
}

export interface AttackCaseView {
    id: number
    channel: string
    title: string
    payload: string
    scenario: string
    expectation: string
    assertion: string
    enabled: boolean
    lastVerdict?: string
    updatedAt?: string
}

export interface AttackBatchReport {
    batchId: number
    batchPassed: boolean
    total: number
    passed: number
    hardBreaches: number
    softDefenseBypassed: number
    softDefenseBypassRate: number
}

export function listEvalCases(params: {
    caseType?: string;
    status?: string;
    page?: number;
    size?: number
}): Promise<EvalCaseView[]> {
    return http.get<EvalCaseView[]>(`/api/management/eval/cases${buildQuery(params)}`)
}

export function createEvalCase(payload: {
    caseType?: string
    scenarioJson: string
    rubricJson: string
    platform?: string
    botId?: string
    groupId?: string
}): Promise<EvalCaseView> {
    return http.post<EvalCaseView>('/api/management/eval/cases', payload)
}

export function generateDraftCase(params: {
    interactionNature: string
    capabilityDomain: string
    note?: string
}): Promise<EvalCaseView> {
    return http.post<EvalCaseView>(`/api/management/eval/cases/draft${buildQuery(params)}`)
}

export function updateEvalCase(caseId: number, payload: {
    caseType?: string
    scenarioJson?: string
    rubricJson?: string
    status?: string
}): Promise<EvalCaseView> {
    return http.put<EvalCaseView>(`/api/management/eval/cases/${caseId}`, payload)
}

export function discardEvalCase(caseId: number): Promise<void> {
    return http.post<void>(`/api/management/eval/cases/${caseId}/discard`)
}

export function startDailyEval(): Promise<EvalBatchView> {
    return http.post<EvalBatchView>('/api/management/eval/daily')
}

export function startManualEval(params: {
    from: string
    to: string
    platform?: string
    botId?: string
    groupId?: string
    samplePerGroup?: number
}): Promise<EvalBatchView> {
    return http.post<EvalBatchView>(`/api/management/eval/manual${buildQuery(params)}`)
}

export function listEvalBatches(params: {
    batchKind?: string;
    page?: number;
    size?: number
}): Promise<EvalBatchView[]> {
    return http.get<EvalBatchView[]>(`/api/management/eval/batches${buildQuery(params)}`)
}

export function getEvalBatchSummary(batchId: number): Promise<EvalBatchSummaryView> {
    return http.get<EvalBatchSummaryView>(`/api/management/eval/batches/${batchId}/summary`)
}

export function listEvalResults(batchId: number): Promise<EvalResultView[]> {
    return http.get<EvalResultView[]>(`/api/management/eval/results${buildQuery({batchId})}`)
}

export function listBlindQueue(params: { page?: number; size?: number } = {}): Promise<EvalResultView[]> {
    return http.get<EvalResultView[]>(`/api/management/eval/blind-queue${buildQuery(params)}`)
}

export function submitBlindScore(resultId: number, params: {
    total: number
    respond?: number
    process?: number
    form?: number
    cost?: number
    safety?: number
    decision: string
    explanation?: string
}): Promise<void> {
    return http.post<void>(`/api/management/eval/results/${resultId}/blind-score${buildQuery(params)}`)
}

export function attributeResult(resultId: number, attribution: string): Promise<void> {
    return http.post<void>(`/api/management/eval/results/${resultId}/attribution${buildQuery({attribution})}`)
}

export function promoteFaultCase(resultId: number): Promise<EvalCaseView> {
    return http.post<EvalCaseView>(`/api/management/eval/results/${resultId}/promote`)
}

export function promoteGoldenCase(resultId: number): Promise<EvalCaseView> {
    return http.post<EvalCaseView>(`/api/management/eval/results/${resultId}/promote-golden`)
}

export function getAgreement(from: string, to: string) {
    return http.get<Record<string, unknown>>(`/api/management/eval/agreement${buildQuery({from, to})}`)
}

export function listProposals(params: { status?: string; page?: number; size?: number }): Promise<ProposalView[]> {
    return http.get<ProposalView[]>(`/api/management/proposals${buildQuery(params)}`)
}

export function attributeProposal(id: number, attribution: string): Promise<void> {
    return http.post<void>(`/api/management/proposals/${id}/attribute${buildQuery({attribution})}`)
}

export function acceptProposal(id: number): Promise<void> {
    return http.post<void>(`/api/management/proposals/${id}/accept`)
}

export function rejectProposal(id: number, reason: string): Promise<void> {
    return http.post<void>(`/api/management/proposals/${id}/reject${buildQuery({reason})}`)
}

export function runReplayBatch(payload: {
    caseIds?: number[]
    kOverride?: number
    versionRef?: string
    experimentId?: string
}) {
    return http.post<Record<string, unknown>>('/api/management/replay/run', payload)
}

export function listReplayBatches(params: {
    page?: number;
    size?: number;
    experimentId?: string
} = {}): Promise<EvalBatchView[]> {
    return http.get<EvalBatchView[]>(`/api/management/replay/batches${buildQuery(params)}`)
}

export function getReplayExperiment(experimentId: string) {
    return http.get<Record<string, unknown>>(`/api/management/replay/${encodeURIComponent(experimentId)}`)
}

export function listAttackCases(channel?: string): Promise<AttackCaseView[]> {
    return http.get<AttackCaseView[]>(`/api/management/security/attack-cases${buildQuery({channel})}`)
}

export function createAttackCase(payload: {
    channel: string
    title: string
    payload: string
    scenario: string
    expectation: string
    assertion: string
}): Promise<{ id: number }> {
    return http.post<{ id: number }>('/api/management/security/attack-cases', payload)
}

export function updateAttackCase(id: number, payload: {
    channel: string
    title: string
    payload: string
    scenario: string
    expectation: string
    assertion: string
}): Promise<void> {
    return http.put<void>(`/api/management/security/attack-cases/${id}`, payload)
}

export function toggleAttackCase(id: number, enabled: boolean): Promise<void> {
    return http.put<void>(`/api/management/security/attack-cases/${id}/enabled${buildQuery({enabled})}`)
}

export function deleteAttackCase(id: number): Promise<void> {
    return http.delete<void>(`/api/management/security/attack-cases/${id}`)
}

export function runAttackCases(caseIds?: number[]): Promise<AttackBatchReport> {
    return http.post<AttackBatchReport>('/api/management/security/attack-cases/run', {caseIds})
}
