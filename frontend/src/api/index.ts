import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { reactive } from 'vue'
import type { ApiResponse, CandidateSkillSummary, ChangeProposal, CurrentReleaseSummary, DashboardSummary, DiagnosisComparison, DynamicRecordDetail, DynamicRecordPage, EmergingCandidate, EmergingCandidateDetail, EmergingDiscoveryCandidate, EmergingDiscoveryRun, EmergingQueryDiscovery, EvaluationStatus, EvaluationSummary, EvidenceExplanation, EvolutionReport, ExtractionResult, FeedScanResult, FeedStatus, GraphFilterParams, GraphRelease, GraphView, ImportResult, JobRole, LearningPlan, MarketCard, MatchReport, ProcessingRun, ProfessionalSession, PromotionBatch, PublicOverview, QualityReport, RealSnapshot, ReleaseDiff, RepositoryOverview, ResumeProfile, ReviewQueueSummary, RoleEvolutionRun, RoleMarketTimeline, RoleSkillEvidence, RuntimeMode, RuntimeStatus, SourceLedgerItem, StructuredImportResult, VerificationEvidenceType, VerificationReviewDecision, VerificationStatus, VerificationSubmission, VerificationSubmissionPage } from '../types/models'
import * as offline from './offline'
import { frozenSnapshot, rejectFrozenWrite } from './frozen-snapshot'
import { frozenPersonal } from './frozen-personal'
import type { FrozenPersonalSession } from './frozen-personal'
import { inferRuntimeMode } from '../utils/runtime-mode'
import { filterGraphByRoles } from '../utils/graph-filters'
import { createPendingVerificationSubmission } from '../utils/static-verification'
import { normalizeGraphViewText } from '../utils/public-text'
import { createPublicDataCache, publishWithCacheInvalidation } from './public-data-cache'
import { isActionableRole } from '../utils/role-copy'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 7000,
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: {
    'Content-Type': 'application/json; charset=utf-8'
  }
})
const staticBuild = import.meta.env.MODE === 'static-demo'
const staticDemo = import.meta.env.VITE_STATIC_DEMO === 'true'
export const frozenCache = import.meta.env.VITE_FROZEN_SNAPSHOT === 'true' || staticBuild
const staticMode = staticDemo || frozenCache
let csrfReady: Promise<string> | null = null
let modeRequestSequence = 0

async function realGet<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResponse<T>>({ ...config, method: 'GET' })
  return response.data.data
}

async function realWrite<T>(config: AxiosRequestConfig): Promise<T> {
  csrfReady ??= http.get<ApiResponse<{ token: string } | null>>('/api/security/csrf').then(response => response.data.data?.token ?? '').catch(error => {
    csrfReady = null
    throw error
  })
  const csrfToken = await csrfReady
  if (!(config.data instanceof FormData)) {
    config.headers = { ...config.headers, 'Content-Type': 'application/json; charset=utf-8' }
  }
  if (csrfToken) config.headers = { ...config.headers, 'X-XSRF-TOKEN': csrfToken }
  const response = await http.request<ApiResponse<T>>(config)
  return response.data.data
}

async function publicDataVersion() {
  const response = await realGet<{ version?: string; updatedAt?: string; sampleCount?: number; roleCount?: number }>({ url: '/api/public/data-version', timeout: 5000 })
  return response.version ?? `${response.updatedAt ?? ''}:${response.sampleCount ?? 0}:${response.roleCount ?? 0}`
}

const publicDataCache = typeof indexedDB === 'undefined'
  ? null
  : createPublicDataCache({
      namespace: `${import.meta.env.MODE}:${import.meta.env.VITE_API_BASE_URL || 'same-origin'}`,
      getVersion: publicDataVersion
    })

function cachedPublicGet<T>(key: string, config: AxiosRequestConfig, fallback: () => T | Promise<T>) {
  if (staticMode || !publicDataCache) return request<T>(config, fallback)
  return publicDataCache.get(key, () => realGet<T>(config))
}

function stableCacheKey(prefix: string, values: object) {
  const entries = Object.entries(values)
    .filter(([, value]) => value !== undefined && value !== '' && value !== null)
    .sort(([left], [right]) => left.localeCompare(right))
  return `${prefix}:${JSON.stringify(Object.fromEntries(entries))}`
}

export const runtime = reactive<{ mode: RuntimeMode; lastError: string; traceId: string; professional: ProfessionalSession }>({
  mode: frozenCache ? 'FROZEN_CACHE' : staticDemo ? 'DEMO' : 'NO_DATA',
  lastError: '',
  traceId: '',
  professional: { authenticated: false }
})

export function restoreFrozenPersonalSession(state: FrozenPersonalSession) {
  if (frozenCache) frozenPersonal.restoreSession(state)
}

export function errorMessage(error: unknown) {
  if (!axios.isAxiosError(error)) {
    const message = error instanceof Error ? error.message : ''
    return /[一-龥]/.test(message) ? message : '岗位资料暂时加载不稳定，请稍后再试'
  }
  const status = error.response?.status
  const message = (error.response?.data as Partial<ApiResponse<unknown>> | undefined)?.message ?? ''
  if (/[一-龥]/.test(message)) return message
  if (status === 500) return '岗位资料暂时加载不稳定，请稍后再试'
  if (status === 404) return '接口或数据不存在'
  if (status === 401 || status === 403) return '当前账号没有权限查看'
  return '请求失败，请检查服务是否正常运行'
}

export function errorTraceId(error: unknown) {
  if (!axios.isAxiosError(error)) return ''
  return (error.response?.data as Partial<ApiResponse<unknown>> | undefined)?.traceId ?? ''
}

async function loadRoleEvolution(id: number): Promise<RoleEvolutionRun> {
  if (frozenCache) return frozenSnapshot.roleEvolution(id)
  try {
    const result = await realGet<RoleEvolutionRun>({ url: `/api/job-roles/${id}/evolution-runs/latest` })
    if (result?.roleId === id) return result
  } catch {
    // 在线数据暂不可用时，使用当前选择岗位的离线岗位资料。
  }
  return offline.roleEvolution(id)
}

async function request<T>(config: AxiosRequestConfig, fallback: () => T | Promise<T>, trackMode = false): Promise<T> {
  if (staticMode) {
    try {
      const result = await fallback()
      runtime.mode = frozenCache ? 'FROZEN_CACHE' : 'DEMO'
      runtime.lastError = ''
      runtime.traceId = ''
      return result
    } catch (error) {
      runtime.mode = 'ERROR'
      runtime.lastError = error instanceof Error ? error.message : '岗位资料加载失败'
      runtime.traceId = ''
      throw error
    }
  }
  const modeRequestId = trackMode ? ++modeRequestSequence : 0
  try {
    const method = (config.method || 'GET').toUpperCase()
    if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
      csrfReady ??= http.get<ApiResponse<{ token: string } | null>>('/api/security/csrf').then(response => response.data.data?.token ?? '').catch(error => {
        csrfReady = null
        throw error
      })
      const csrfToken = await csrfReady
      if (csrfToken) config.headers = { ...config.headers, 'X-XSRF-TOKEN': csrfToken }
      // 确保 POST 请求的 Content-Type 包含 charset=utf-8
      if (config.data && typeof config.data === 'object' && !(config.data instanceof FormData)) {
        config.headers = { ...config.headers, 'Content-Type': 'application/json; charset=utf-8' }
      }
    }
    const response = await http.request<ApiResponse<T>>(config)
    if (trackMode && modeRequestId === modeRequestSequence) {
      runtime.mode = inferRuntimeMode(response.data.data)
      runtime.lastError = ''
      runtime.traceId = response.data.traceId
    }
    return response.data.data
  } catch (error) {
    if (trackMode && modeRequestId === modeRequestSequence) {
      runtime.mode = 'ERROR'
      runtime.lastError = errorMessage(error)
      runtime.traceId = errorTraceId(error)
    }
    const method = (config.method || 'GET').toUpperCase()
    if (method === 'GET') {
      const result = await fallback()
      runtime.mode = 'DEMO'
      runtime.lastError = '岗位资料暂时加载不稳定，已展示本地示例内容。'
      runtime.traceId = ''
      return result
    }
    throw error
  }
}

export const api = {
  publicOverview: () => cachedPublicGet<PublicOverview>('public-overview', { url: '/api/public/overview', timeout: 15000 }, () => frozenCache ? frozenSnapshot.publicOverview() : offline.publicOverview),
  publicGraphOverview: (filters: GraphFilterParams = {}) => cachedPublicGet<GraphView>(stableCacheKey('public-graph-overview', filters), { url: '/api/public/graph-overview', params: filters }, () => frozenCache ? frozenSnapshot.publicGraphOverview(filters) : filterGraphByRoles(offline.publicGraphOverview, offline.publicOverview.roles, filters)),
  currentRelease: () => request<CurrentReleaseSummary>({ url: '/api/public/current-release' }, async () => {
    const releases = frozenCache ? await frozenSnapshot.releases() : offline.releases
    const release = releases[0]
    return { version: release.version, dataScope: '当前生效岗位能力图谱', documentCount: 0, policyVersion: release.policyVersion, updatedAt: release.publishedAt }
  }),
  runtimeStatus: () => request<RuntimeStatus>({ url: '/api/runtime-status' }, () => frozenCache ? frozenSnapshot.runtimeStatus() : ({ dataMode: 'DEMO', operatingMode: 'STATIC_DEMO', dynamicRecordCount: 0, publishedRealDocumentCount: offline.dashboard.rawDocumentCount, pendingCandidateSkillCount: 0, snapshotCode: 'demo-snapshot-2026-07' }), true),
  dashboard: () => request<DashboardSummary>({ url: '/api/dashboard' }, () => frozenCache ? frozenSnapshot.dashboard() : offline.dashboard),
  roles: () => cachedPublicGet<JobRole[]>('job-roles', { url: '/api/job-roles' }, () => frozenCache ? frozenSnapshot.roles() : offline.roles),
  marketCard: (id: number) => request<MarketCard>({ url: `/api/job-roles/${id}/market-card` }, () => frozenCache ? frozenSnapshot.marketCard(id) : offline.marketCard(id)),
  marketTimeline: (id: number) => request<RoleMarketTimeline>({ url: `/api/job-roles/${id}/market-timeline` }, () => frozenCache ? frozenSnapshot.marketTimeline(id) : offline.marketTimeline(id)),
  repositoryOverview: () => request<RepositoryOverview>({ url: '/api/repositories/overview' }, () => frozenCache ? frozenSnapshot.repositoryOverview() : offline.repositoryOverview),
  evaluationSummary: () => request<EvaluationSummary>({ url: '/api/evaluation-runs/latest' }, () => frozenCache ? frozenSnapshot.evaluationSummary() : offline.evaluationSummary),
  importStructuredJd: (data: unknown) => request<StructuredImportResult>({ url: '/api/data-imports/structured-jd', method: 'POST', data, timeout: 120000 }, () => frozenCache ? rejectFrozenWrite('网页导入') : offline.structuredImportResult),
  dynamicRecords: (params: { importId?: number; status?: string; keyword?: string; riskLevel?: string; reviewRoute?: string; onlyExceptions?: boolean; page?: number; size?: number } = {}) => request<DynamicRecordPage>({ url: '/api/dynamic-records', params }, () => frozenCache ? frozenSnapshot.dynamicRecords(params) : offline.dynamicRecordPage),
  dynamicRecord: (id: number) => request<DynamicRecordDetail>({ url: `/api/dynamic-records/${id}` }, () => frozenCache ? frozenSnapshot.dynamicRecord(id) : offline.dynamicRecordDetail(id)),
  reviewDynamicRecord: (id: number, data: { decision: string; reviewer: string; reason: string; factReviews: Array<{ factId: number; decision: string; correctedValue?: string; note?: string }> }) => request<DynamicRecordDetail>({ url: `/api/dynamic-records/${id}/reviews`, method: 'POST', data }, () => frozenCache ? rejectFrozenWrite('JD 审核') : offline.dynamicRecordDetail(id)),
  bulkReviewDynamicRecords: (data: { recordIds: number[]; decision: string; reviewer: string; reason: string }) => request<DynamicRecordDetail[]>({ url: '/api/dynamic-records/bulk-reviews', method: 'POST', data }, () => frozenCache ? rejectFrozenWrite('批量 JD 审核') : data.recordIds.map(id => offline.dynamicRecordDetail(id))),
  retriageDynamicRecords: (importId?: number) => request<ReviewQueueSummary>({ url: '/api/dynamic-records/retriage', method: 'POST', params: { importId } }, () => frozenCache ? rejectFrozenWrite('重新分流') : ({ totalRecords: offline.dynamicRecordPage.total, autoApprovedCount: 0, sampledCount: 0, exceptionCount: 0, waitingSkillCount: offline.dynamicRecordPage.total, humanApprovedCount: 0, blockedCount: 0, pendingCandidateSkillCount: offline.candidateSkills.length, estimatedRemainingActions: offline.dynamicRecordPage.total + offline.candidateSkills.length })),
  reviewQueueSummary: (importId?: number) => request<ReviewQueueSummary>({ url: '/api/review-queues/summary', params: { importId } }, () => frozenCache ? frozenSnapshot.reviewQueueSummary() : ({ totalRecords: offline.dynamicRecordPage.total, autoApprovedCount: 0, sampledCount: 0, exceptionCount: 0, waitingSkillCount: offline.dynamicRecordPage.total, humanApprovedCount: 0, blockedCount: 0, pendingCandidateSkillCount: offline.candidateSkills.length, estimatedRemainingActions: offline.dynamicRecordPage.total + offline.candidateSkills.length })),
  candidateSkills: () => request<CandidateSkillSummary[]>({ url: '/api/candidate-skills' }, () => frozenCache ? frozenSnapshot.candidateSkills() : offline.candidateSkills),
  reviewCandidateSkill: (skill: string, data: { decision: string; correctedValue?: string; reviewer: string; note?: string }) => request<CandidateSkillSummary[]>({ url: `/api/candidate-skills/${encodeURIComponent(skill)}/reviews`, method: 'POST', data }, () => frozenCache ? rejectFrozenWrite('候选技能审核') : offline.candidateSkills.map(item => item.skill === skill ? { ...item, reviewDecision: data.decision, correctedValue: data.correctedValue } : item)),
  bulkReviewCandidateSkills: (data: { skills: string[]; decision: string; correctedValue?: string; reviewer: string; note?: string }) => request<CandidateSkillSummary[]>({ url: '/api/candidate-skills/bulk-reviews', method: 'POST', data }, () => frozenCache ? rejectFrozenWrite('批量候选技能审核') : offline.candidateSkills),
  createPromotionBatch: (recordIds: number[], requestedBy: string, importId?: number, selectionMode = 'IDS') => request<PromotionBatch>({ url: '/api/promotion-batches', method: 'POST', data: { recordIds, importId, selectionMode, requestedBy } }, () => frozenCache ? rejectFrozenWrite('创建发布批次') : offline.promotionBatch(recordIds, requestedBy)),
  promotionBatch: (id: number) => request<PromotionBatch>({ url: `/api/promotion-batches/${id}` }, () => frozenCache ? rejectFrozenWrite('加载在线发布批次') : offline.promotionBatch([], '岗位研究员')),
  reviewPromotionBatch: (id: number, decision: string, reviewer: string, note: string) => request<PromotionBatch>({ url: `/api/promotion-batches/${id}/reviews`, method: 'POST', data: { decision, reviewer, note } }, () => frozenCache ? rejectFrozenWrite('审核发布批次') : ({ ...offline.promotionBatch([], '岗位研究员'), id, status: decision === 'APPROVE' ? 'APPROVED' : 'REJECTED', approvedBy: reviewer, reviewNote: note })),
  publishPromotionBatch: (id: number, publisher: string) => request<PromotionBatch>({ url: `/api/promotion-batches/${id}/publish`, method: 'POST', data: { publisher } }, () => frozenCache ? rejectFrozenWrite('发布快照') : ({ ...offline.promotionBatch([], '岗位研究员'), id, status: 'COMPLETED', completedAt: new Date().toISOString() })),
  realSnapshot: () => request<RealSnapshot>({ url: '/api/real-job-snapshots/current' }, () => frozenCache ? frozenSnapshot.realSnapshot() : offline.realSnapshot),
  sourceLedger: () => request<SourceLedgerItem[]>({ url: '/api/professional/source-ledger' }, () => frozenCache ? frozenSnapshot.sourceLedger() : offline.sourceLedger),
  professionalSession: () => request<ProfessionalSession>({ url: '/api/security/professional/session' }, () => ({ authenticated: false, loginRequired: frozenCache, capabilities: [] })),
  professionalLogin: (username: string, password: string) => request<ProfessionalSession>({ url: '/api/security/professional/login', method: 'POST', data: { username, password } }, () => frozenCache ? rejectFrozenWrite('专业端登录') : ({ authenticated: false })),
  professionalLogout: () => request<ProfessionalSession>({ url: '/api/security/professional/logout', method: 'POST' }, () => ({ authenticated: false })),
  emerging: () => request<EmergingCandidate[]>({ url: '/api/emerging-job-candidates' }, () => frozenCache ? frozenSnapshot.emerging() : offline.candidates),
  emergingQueries: (limit = 12) => request<EmergingQueryDiscovery[]>({ url: '/api/emerging-discovery/queries', params: { limit } }, () => frozenCache ? frozenSnapshot.emergingQueries(limit) : []),
  discoverEmergingQuery: async (query: string) => {
    return await realWrite<EmergingQueryDiscovery>({
      url: '/api/emerging-discovery/queries',
      method: 'POST',
      data: { query },
      timeout: 120000
    })
  },
  publishEmergingQuery: (id: number) => publishWithCacheInvalidation(
    () => realWrite<EmergingQueryDiscovery>({ url: `/api/emerging-discovery/queries/${id}/publications`, method: 'POST', timeout: 30000 }),
    () => publicDataCache?.invalidate(['public-overview', 'public-graph-overview:', 'job-roles', 'role-graph:']) ?? Promise.resolve()
  ),
  removeEmergingQueryFromGraph: (id: number) => publishWithCacheInvalidation(
    () => realWrite<EmergingQueryDiscovery>({ url: `/api/emerging-discovery/queries/${id}/publications`, method: 'DELETE', timeout: 30000 }),
    () => publicDataCache?.invalidate(['public-overview', 'public-graph-overview:', 'job-roles', 'role-graph:']) ?? Promise.resolve()
  ),
  emergingRuns: () => request<EmergingDiscoveryRun[]>({ url: '/api/emerging-discovery/runs' }, () => []),
  startEmergingRun: (requestedBy: string) => request<EmergingDiscoveryRun>({ url: '/api/emerging-discovery/runs', method: 'POST', params: { requestedBy }, timeout: 30000 }, () => frozenCache ? rejectFrozenWrite('启动新岗位发现') : Promise.reject(new Error('完整发现运行需要 MySQL 环境'))),
  emergingDiscoveryCandidates: () => request<EmergingDiscoveryCandidate[]>({ url: '/api/emerging-discovery/candidates' }, () => []),
  emergingCandidateDetail: (id: number) => request<EmergingCandidateDetail>({ url: `/api/emerging-discovery/candidates/${id}/detail` }, () => Promise.reject(new Error('候选审计详情仅在 MySQL 环境提供'))),
  updateEmergingCandidate: (id: number, data: { expectedVersion: number; name: string; jobLevel: string; technologyStack: string[]; responsibilities: string[]; requiredSkills: string[]; bonusSkills: string[]; scenarios: string[]; reason: string; evidenceIds: number[]; editor: string }) => request<EmergingDiscoveryCandidate>({ url: `/api/emerging-discovery/candidates/${id}`, method: 'PUT', data }, () => frozenCache ? rejectFrozenWrite('编辑新岗位候选') : Promise.reject(new Error('完整候选编辑需要 MySQL 环境'))),
  submitEmergingCandidate: (id: number, expectedVersion: number, creator: string) => request<EmergingDiscoveryCandidate>({ url: `/api/emerging-discovery/candidates/${id}/submissions`, method: 'POST', params: { expectedVersion, creator } }, () => frozenCache ? rejectFrozenWrite('提交新岗位候选') : Promise.reject(new Error('完整候选提交需要 MySQL 环境'))),
  reviewEmergingCandidate: (id: number, expectedVersion: number, decision: string, reviewer: string, comment: string) => request<EmergingDiscoveryCandidate>({ url: `/api/emerging-discovery/candidates/${id}/reviews`, method: 'POST', params: { expectedVersion }, data: { decision, reviewer, comment } }, () => frozenCache ? rejectFrozenWrite('审核新岗位候选') : Promise.reject(new Error('完整候选审核需要 MySQL 环境'))),
  publishEmergingCandidate: (id: number, expectedVersion: number, version: string, publisher: string) => request<EmergingDiscoveryCandidate>({ url: `/api/emerging-discovery/candidates/${id}/publications`, method: 'POST', params: { expectedVersion }, data: { version, publisher } }, () => frozenCache ? rejectFrozenWrite('发布新岗位候选') : Promise.reject(new Error('完整候选发布需要 MySQL 环境'))),
  evolution: (id: number) => request<EvolutionReport>({ url: `/api/job-roles/${id}/evolution` }, () => frozenCache ? frozenSnapshot.evolution(id) : offline.evolution),
  roleEvolution: (id: number) => loadRoleEvolution(id),
  startRoleEvolution: (id: number) => request<RoleEvolutionRun>({ url: `/api/job-roles/${id}/evolution-runs`, method: 'POST' }, () => frozenCache ? rejectFrozenWrite('重新计算岗位演化') : Promise.reject(new Error('岗位演化需要真实 MySQL 环境'))),
  updateRoleEvolutionDraft: (runId: number, data: { expectedVersion: number; includedChangeIds: number[]; note: string; reason: string; editor: string }) => request<RoleEvolutionRun>({ url: `/api/role-evolution-runs/${runId}/draft`, method: 'PUT', data }, () => frozenCache ? rejectFrozenWrite('优化岗位演化草稿') : Promise.reject(new Error('岗位演化草稿需要真实 MySQL 环境'))),
  publishRoleEvolution: (runId: number, data: { expectedVersion: number; version: string; publisher: string }) => request<RoleEvolutionRun>({ url: `/api/role-evolution-runs/${runId}/publications`, method: 'POST', data }, () => frozenCache ? rejectFrozenWrite('发布岗位演化版本') : Promise.reject(new Error('岗位演化发布需要真实 MySQL 环境'))),
  graph: (id: number, viewType = 'PANORAMA', filters: GraphFilterParams = {}) => cachedPublicGet<GraphView>(stableCacheKey(`role-graph:${id}`, { viewType, ...filters }), { url: `/api/job-roles/${id}/graph`, params: { viewType, ...filters } }, () => frozenCache ? frozenSnapshot.graph(id, viewType, filters) : filterGraphByRoles(offline.graph(id, viewType), offline.publicOverview.roles, filters)).then(normalizeGraphViewText),
  roleSkillEvidence: (id: number, skill: string) => request<RoleSkillEvidence>({ url: `/api/job-roles/${id}/skills/${encodeURIComponent(skill)}/evidence` }, () => frozenCache ? frozenSnapshot.roleSkillEvidence(id, skill) : (() => {
    const role = offline.roles.find(item => item.id === id) ?? offline.roles[0]
    const requirement = [...role.requiredSkills, ...role.bonusSkills].find(item => item.skill === skill) ?? role.requiredSkills[0]
    const evidence = offline.marketCard(id).evidence.slice(0, 4).map(item => ({ documentId: item.documentId, title: role.name, company: item.company, quote: item.quote, startOffset: item.startOffset, endOffset: item.endOffset, sourceUrl: '' }))
    return { roleId: id, roleName: role.name, skill: requirement.skill, requirementType: requirement.requirementType, demandRate: requirement.demandRate, documentCount: evidence.length, companyCount: new Set(evidence.map(item => item.company)).size, snapshotCode: role.releaseVersion, dataScope: '当前岗位资料', evidence }
  })()).then(async result => {
    if (result.evidence?.length) return result
    // Keep every runtime on the same evidence contract when the online endpoint has an empty response.
    const snapshotResult = await frozenSnapshot.roleSkillEvidence(id, skill)
    return snapshotResult.evidence?.length ? snapshotResult : result
  }),
  quality: () => request<QualityReport>({ url: '/api/dataset-snapshots/current/quality-report' }, () => frozenCache ? frozenSnapshot.quality() : offline.quality),
  extract: (text: string) => request<ExtractionResult>({ url: '/api/job-descriptions/extractions', method: 'POST', data: { text } }, () => offline.extractOffline(text)),
  proposals: () => request<ChangeProposal[]>({ url: '/api/change-proposals' }, () => frozenCache ? [] : offline.proposals),
  createProposal: (title: string, type: string, reason: string, changes: string[]) => request<ChangeProposal>({ url: '/api/change-proposals', method: 'POST', data: { title, type, reason, changes } }, () => frozenCache ? rejectFrozenWrite('创建图谱提案') : ({ id: Date.now(), title, type, reason, changes, status: 'PENDING_REVIEW', creator: '岗位研究员', createdAt: new Date().toISOString() })),
  reviewProposal: (id: number, decision: string, reviewer: string, comment: string) => request<ChangeProposal>({ url: `/api/change-proposals/${id}/reviews`, method: 'POST', data: { decision, reviewer, comment } }, () => frozenCache ? rejectFrozenWrite('审核图谱提案') : ({ ...(offline.proposals.find(item => item.id === id) ?? offline.proposals[0]), id, status: decision === 'APPROVE' ? 'APPROVED' : 'REJECTED', reviewer, reviewedAt: new Date().toISOString() })),
  releases: () => request<GraphRelease[]>({ url: '/api/admin/graph-releases' }, () => frozenCache ? frozenSnapshot.releases() : offline.releases),
  publishRelease: (version: string, snapshotId: string, publisher: string, proposalIds: number[]) => request<GraphRelease>({ url: '/api/admin/graph-releases', method: 'POST', data: { version, snapshotId, publisher, proposalIds } }, () => frozenCache ? rejectFrozenWrite('发布图谱版本') : ({ id: Date.now(), version, snapshotId, publisher, status: 'PUBLISHED', policyVersion: 'v1.0.0', changes: [], publishedAt: new Date().toISOString() })),
  releaseDiff: (id: number) => request<ReleaseDiff>({ url: `/api/admin/graph-releases/${id}/diff` }, () => frozenCache ? ({ fromVersion: '', toVersion: '', added: [], changed: [], weakened: [], removed: [] }) : ({ fromVersion: '2025.12', toVersion: '2026.07', added: ['RAG 应用开发工程师'], changed: ['Docker 与 Kubernetes'], weakened: ['JSP/Servlet'], removed: [] })),
  rollbackRelease: (id: number, publisher: string) => request<GraphRelease>({ url: `/api/admin/graph-releases/${id}/rollback`, method: 'POST', params: { publisher } }, () => frozenCache ? rejectFrozenWrite('回滚图谱版本') : ({ ...(offline.releases.find(item => item.id === id) ?? offline.releases[0]), id, publisher, status: 'PUBLISHED', publishedAt: new Date().toISOString() })),
  startProcessing: (snapshotId: string) => request<ProcessingRun>({ url: '/api/processing-runs', method: 'POST', data: { snapshotId, steps: [] } }, () => frozenCache ? rejectFrozenWrite('启动在线处理任务') : ({ id: `run-${Date.now()}`, snapshotId, status: 'COMPLETED', steps: [], progress: 100, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() })),
  importDataFile: (file: File, sourceType: string, sourceName: string, licenseStatus: string) => {
    const data = new FormData()
    data.append('file', file)
    data.append('sourceType', sourceType)
    data.append('sourceName', sourceName)
    data.append('licenseStatus', licenseStatus)
    return request<ImportResult>({ url: '/api/data-imports/file', method: 'POST', data, timeout: 60000 }, () => frozenCache ? rejectFrozenWrite('网页文件导入') : ({ id: `import-${Date.now()}`, sourceType, sourceName, acceptedCount: 0, skippedCount: 0, status: 'DEMO_ONLY', createdAt: new Date().toISOString() }))
  },
  feedStatus: () => request<FeedStatus>({ url: '/api/data-imports/rss/status' }, () => ({ enabled: false, feedUrl: '', sourceName: '', sourceType: 'RECRUITMENT_JD', licenseStatus: '', licenseNote: '', scanDelayMs: 1800000 })),
  scanFeed: () => request<FeedScanResult>({ url: '/api/data-imports/rss/scan', method: 'POST', timeout: 30000 }, () => frozenCache ? rejectFrozenWrite('网页采集') : ({ acceptedCount: 0, skippedCount: 0, httpStatus: 0, status: 'OFFLINE', startedAt: new Date().toISOString(), completedAt: new Date().toISOString() })),
  evaluationStatus: () => request<EvaluationStatus[]>({ url: '/api/evaluations/status' }, () => [
    { code: 'JD_EXTRACTION', name: 'JD 解析准确率', targetSamples: 100, frozenSamples: 0, targetAccuracy: .9, status: 'WAITING_FOR_REAL_DATA' },
    { code: 'RESUME_EXTRACTION', name: '简历提取准确率', targetSamples: 20, frozenSamples: 0, targetAccuracy: .9, status: 'WAITING_FOR_REAL_DATA' },
    { code: 'JOB_MATCHING', name: '人岗匹配准确率', targetSamples: 100, frozenSamples: 0, targetAccuracy: .9, status: 'WAITING_FOR_REAL_DATA' }
  ]),
  retryProcessing: (id: string) => request<ProcessingRun>({ url: `/api/processing-runs/${id}/retries`, method: 'POST' }, () => frozenCache ? rejectFrozenWrite('重试在线处理任务') : ({ id, snapshotId: 'demo-snapshot-2026-07', status: 'COMPLETED', steps: [], progress: 100, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() })),
  resume: (text: string, weeklyHours: number, graduationYear: number) => request<ResumeProfile>({ url: '/api/resumes/text', method: 'POST', data: { text, weeklyHours, graduationYear } }, () => frozenCache ? frozenPersonal.parseText(text, weeklyHours, graduationYear) : offline.sampleResume),
  reparseResume: (id: number, text: string, weeklyHours: number, graduationYear: number) => request<ResumeProfile>({ url: `/api/resumes/${id}/text`, method: 'PUT', data: { text, weeklyHours, graduationYear } }, () => frozenCache ? frozenPersonal.reparseText(id, text, weeklyHours, graduationYear) : ({ ...offline.sampleResume, id, status: 'NEEDS_CONFIRMATION' })),
  resumeFile: (file: File, weeklyHours: number, graduationYear: number) => {
    const data = new FormData()
    data.append('file', file)
    data.append('weeklyHours', String(weeklyHours))
    data.append('graduationYear', String(graduationYear))
    return request<ResumeProfile>({ url: '/api/resumes/file', method: 'POST', data, timeout: 60000 }, () => frozenCache ? frozenPersonal.unsupportedFile() : offline.sampleResume)
  },
  reparseResumeFile: (id: number, file: File, weeklyHours: number, graduationYear: number) => {
    const data = new FormData()
    data.append('file', file)
    data.append('weeklyHours', String(weeklyHours))
    data.append('graduationYear', String(graduationYear))
    return request<ResumeProfile>({ url: `/api/resumes/${id}/file`, method: 'PUT', data, timeout: 60000 }, () => frozenCache ? frozenPersonal.unsupportedFile() : ({ ...offline.sampleResume, id, status: 'NEEDS_CONFIRMATION' }))
  },
  abilityProfile: (data: { name: string; education: string; major: string; graduationYear: number; weeklyHours: number; skills: string[]; projects: string[]; certificates: string[] }) => request<ResumeProfile>({ url: '/api/ability-profiles', method: 'POST', data }, () => frozenCache ? frozenPersonal.createManual(data) : ({ ...offline.sampleResume, name: data.name || '匿名同学', education: data.education, major: data.major, graduationYear: data.graduationYear, weeklyHours: data.weeklyHours, abilities: data.skills.map(skill => ({ skill, level: 0, levelName: 'L0 自述', quote: '用户手动填写', sourceType: 'SELF_REPORT', confidence: 1 })), projects: data.projects, certificates: data.certificates, status: 'NEEDS_CONFIRMATION' })),
  resumeFacts: (id: number) => request<ResumeProfile>({ url: `/api/resumes/${id}/facts` }, () => frozenCache ? frozenPersonal.profile(id) : offline.sampleResume),
  confirmResume: (id: number, confirmed: boolean) => request<ResumeProfile>({ url: `/api/resumes/${id}/facts`, method: 'PUT', data: { confirmed } }, () => frozenCache ? frozenPersonal.confirmProfile(id, confirmed) : ({ ...offline.sampleResume, id, status: confirmed ? 'CONFIRMED' : 'NEEDS_CONFIRMATION' })),
  updateResumeFacts: (id: number, data: { name: string; education: string; major: string; graduationYear: number; weeklyHours: number; skills: string[]; projects: string[]; certificates: string[]; confirmed: boolean }) => request<ResumeProfile>({ url: `/api/resumes/${id}/facts`, method: 'PUT', data }, () => frozenCache ? frozenPersonal.updateProfile(data) : ({ ...offline.sampleResume, id, ...data, abilities: data.skills.map(skill => ({ skill, level: 0, levelName: 'L0 自述', quote: '用户修正', sourceType: 'USER_CORRECTION', confidence: 1 })), status: data.confirmed ? 'CONFIRMED' : 'NEEDS_CONFIRMATION' })),
  deleteResume: (id: number) => request<void>({ url: `/api/resumes/${id}`, method: 'DELETE' }, () => frozenCache ? frozenPersonal.deleteProfile(id) : undefined),
  match: (resumeId: number, jobRoleId: number) => {
    const role = offline.roles.find(item => item.id === jobRoleId)
    if (role && !isActionableRole(role)) return Promise.reject(new Error('ROLE_NOT_ACTIONABLE: 新型岗位暂不能用于能力诊断或行动计划'))
    return request<MatchReport>({ url: '/api/match-runs', method: 'POST', data: { resumeId, jobRoleId, preferredCity: '', workMode: '' } }, () => frozenCache ? frozenPersonal.match(resumeId, jobRoleId) : offline.matchForRole(resumeId, jobRoleId))
  },
  matchResult: (id: number) => request<MatchReport>({ url: `/api/match-runs/${id}` }, () => frozenCache ? frozenPersonal.matchResult(id) : offline.matchResult(id)),
  learningPlan: (matchRunId: number, weeklyHours: number) => request<LearningPlan>({ url: '/api/learning-plans', method: 'POST', data: { matchRunId, weeklyHours } }, () => frozenCache ? frozenPersonal.plan(matchRunId, weeklyHours) : ({ ...offline.planForMatch(matchRunId, weeklyHours) })),
  verification: (learningPlanId: number, itemOrder: number, evidenceType: VerificationEvidenceType, evidenceText: string, evidenceUrl = '') => request<VerificationSubmission>({
    url: '/api/verification-submissions', method: 'POST', data: { learningPlanId, itemOrder, evidenceType, evidenceUrl, evidenceText }
  }, () => frozenCache ? frozenPersonal.verification(learningPlanId, itemOrder, evidenceType, evidenceText, evidenceUrl) : createPendingVerificationSubmission({ id: 4001, learningPlanId, itemOrder, skill: offline.samplePlan.items.find(item => item.order === itemOrder)?.skill ?? '', evidenceType, evidenceUrl, evidenceText })),
  diagnosisComparison: (beforeMatchRunId: number, verificationSubmissionId: number) => request<DiagnosisComparison>({
    url: '/api/diagnosis-comparisons', method: 'POST', data: { beforeMatchRunId, verificationSubmissionId }
  }, () => Promise.reject(new Error('专业审核及再次诊断仅在完整动态环境提供'))),
  verificationSubmissions: (params: { status?: VerificationStatus; page?: number; size?: number } = {}) => request<VerificationSubmissionPage>({
    url: '/api/admin/verification-submissions', params
  }, () => ({ items: [], page: params.page ?? 0, size: params.size ?? 20, total: 0 })),
  reviewVerificationSubmission: (id: number, decision: VerificationReviewDecision, reason: string) => request<VerificationSubmission>({
    url: `/api/admin/verification-submissions/${id}/reviews`, method: 'POST', data: { decision, reason }
  }, () => frozenCache ? rejectFrozenWrite('审核能力证据') : Promise.reject(new Error('能力证据审核需要完整动态环境'))),
  evidenceExplanation: (data: { questionType: string; jobRoleId: number; skill?: string; graphRelease?: string; matchRunId?: number }) => request<EvidenceExplanation>({ url: '/api/evidence-explanations', method: 'POST', data }, () => frozenCache ? frozenSnapshot.evidenceExplanation(data.jobRoleId, data.skill) : ({ questionType: data.questionType as EvidenceExplanation['questionType'], explanation: data.skill ? `${data.skill} 在多条岗位证据中被列为完成核心职责所需能力，请在证据卡中核对来源。` : '请在岗位证据中核对当前结论。', evidence: offline.marketCard(data.jobRoleId).evidence.slice(0, 2), refused: false, metadata: { dataMode: 'DEMO', graphRelease: '2026.07', evidenceAvailable: true } }))
}
