import type {
  CandidateSkillSummary,
  DashboardSummary,
  DynamicRecordDetail,
  DynamicRecordPage,
  EmergingCandidate,
  EmergingQueryDiscovery,
  EvaluationSummary,
  GraphView,
  JobRole,
  MarketCard,
  PublicOverview,
  QualityReport,
  RealSnapshot,
  RepositoryOverview,
  ReviewQueueSummary,
  RoleMarketTimeline,
  RoleEvolutionRun,
  RoleSkillEvidence,
  SourceLedgerItem,
  RuntimeStatus
} from '../types/models'
import type { EvidenceExplanation, EvolutionReport, GraphFilterParams, GraphRelease } from '../types/models'
import { filterGraphByRoles, normalizeGraphFilters } from '../utils/graph-filters'
import { publicEmergingQueries } from '../utils/emerging-query-cache'

export interface FrozenSnapshot {
  schemaVersion: '1.0.0'
  snapshot: {
    code: string
    generatedAt: string
    dataMode: 'FROZEN_CACHE'
    usageScope: string
    sourceDocumentCount: number
    sourceHash: string
    disclaimer: string
  }
  runtimeStatus: RuntimeStatus
  dashboard: DashboardSummary
  publicOverview: PublicOverview
  publicGraphOverview: GraphView
  publicGraphFilters?: { techStack?: Record<string, GraphView> }
  roles: JobRole[]
  marketCards?: Record<string, MarketCard>
  marketTimelines: Record<string, RoleMarketTimeline>
  repositoryOverview: RepositoryOverview
  quality: QualityReport
  dynamicRecords?: DynamicRecordPage
  dynamicRecordDetails?: Record<string, DynamicRecordDetail>
  dataFiles?: {
    marketCards?: string
    dynamicRecords?: string
    dynamicRecordDetails?: string
    realSnapshot?: string
  }
  candidateSkills: CandidateSkillSummary[]
  reviewQueueSummary: ReviewQueueSummary
  realSnapshot?: RealSnapshot
  sourceLedger: SourceLedgerItem[]
  emergingCandidates: EmergingCandidate[]
  emergingQueries?: EmergingQueryDiscovery[]
  roleEvolutions?: Record<string, RoleEvolutionRun>
  evaluationSummary: EvaluationSummary
}

let snapshotPromise: Promise<FrozenSnapshot> | null = null

function catalogRoles(snapshot: FrozenSnapshot) {
  return snapshot.roles.filter(role => role.code !== 'LLM_EVALUATION_ENGINEER')
}

function publicGraph(snapshot: FrozenSnapshot, graph: GraphView): GraphView {
  const hiddenRoles = snapshot.roles.filter(role => role.code === 'LLM_EVALUATION_ENGINEER')
  const hiddenRoleIds = new Set(hiddenRoles.map(role => `role-${role.id}`))
  const hiddenFamilyNames = new Set(hiddenRoles.map(role => role.family))
  const visibleRoleFamilies = new Set(catalogRoles(snapshot).map(role => role.family))
  const hiddenFamilyIds = new Set(
    graph.nodes
      .filter(node => node.type === 'JOB_FAMILY' && hiddenFamilyNames.has(node.group) && !visibleRoleFamilies.has(node.group))
      .map(node => node.id)
  )
  const hiddenNodeIds = new Set([...hiddenRoleIds, ...hiddenFamilyIds])
  return {
    ...graph,
    nodes: graph.nodes.filter(node => !hiddenNodeIds.has(node.id)),
    edges: graph.edges.filter(edge => !hiddenNodeIds.has(edge.source) && !hiddenNodeIds.has(edge.target))
  }
}

function trustedPublicOverview(snapshot: FrozenSnapshot): PublicOverview {
  const visibleRoles = catalogRoles(snapshot)
  const roleDetails = new Map(visibleRoles.map(role => [role.code, role]))
  return {
    ...snapshot.publicOverview,
    roles: snapshot.publicOverview.roles.filter(role => role.code !== 'LLM_EVALUATION_ENGINEER').map(role => {
      const source = roleDetails.get(role.code)
      const dimensions = {
        jobLevel: role.jobLevel ?? source?.level ?? '未明确',
        techStack: role.techStack ?? [...(source?.requiredSkills ?? []), ...(source?.bonusSkills ?? [])]
          .flatMap(item => [item.category, item.skill]).filter((value, index, values) => values.indexOf(value) === index),
        roleVersion: role.roleVersion ?? source?.releaseVersion ?? snapshot.snapshot.code,
        evidenceLayer: role.evidenceLayer ?? (role.status === 'PUBLISHED' ? 'REAL_EVIDENCE' : role.status)
      }
      const emerging = role.emerging ?? role.code.startsWith('DISCOVERED_')
      return { ...role, ...dimensions, emerging }
    })
  }
}
const dataFilePromises = new Map<string, Promise<unknown>>()

function validateSnapshot(value: unknown): FrozenSnapshot {
  if (!value || typeof value !== 'object') throw new Error('岗位资料无效，请联系资料管理员更新。')
  const snapshot = value as Partial<FrozenSnapshot>
  if (snapshot.schemaVersion !== '1.0.0') throw new Error('当前资料版本暂不支持，请联系资料管理员。')
  if (snapshot.snapshot?.dataMode !== 'FROZEN_CACHE') throw new Error('岗位资料状态异常，请联系资料管理员。')
  if (!snapshot.snapshot.code || !snapshot.snapshot.generatedAt) throw new Error('岗位资料缺少版本信息，请联系资料管理员。')
  if (!Array.isArray(snapshot.roles) || snapshot.roles.length === 0) throw new Error('当前资料中没有可用岗位。')
  if (!snapshot.runtimeStatus || !snapshot.dashboard || !snapshot.publicOverview || !snapshot.quality) throw new Error('岗位资料不完整，请联系资料管理员。')
  if (!snapshot.dynamicRecords && !snapshot.dataFiles?.dynamicRecords) throw new Error('岗位资料列表不完整，请联系资料管理员。')
  return snapshot as FrozenSnapshot
}

export function loadFrozenSnapshot() {
  snapshotPromise ??= fetch('/data/current.json', { cache: 'no-cache', headers: { Accept: 'application/json' } })
    .then(async response => {
      if (!response.ok) throw new Error('岗位资料加载失败，请刷新后重试。')
      return validateSnapshot(await response.json())
    })
    .catch(error => {
      snapshotPromise = null
      throw error
    })
  return snapshotPromise
}

export function resetFrozenSnapshotForTest() {
  snapshotPromise = null
  dataFilePromises.clear()
}

async function loadDataFile<T>(path: string | undefined, label: string): Promise<T> {
  if (!path) throw new Error(`当前资料缺少${label}索引。`)
  if (!dataFilePromises.has(path)) dataFilePromises.set(path, fetch(path, { cache: 'force-cache', headers: { Accept: 'application/json' } })
    .then(async response => {
      if (!response.ok) throw new Error(`${label}加载失败，请刷新后重试。`)
      return response.json()
    })
    .catch(error => {
      dataFilePromises.delete(path)
      throw error
    }))
  return dataFilePromises.get(path) as Promise<T>
}

function loadMarketCards(snapshot: FrozenSnapshot) {
  return snapshot.marketCards
    ? Promise.resolve(snapshot.marketCards)
    : loadDataFile<Record<string, MarketCard>>(snapshot.dataFiles?.marketCards, '岗位证据')
}

function loadDynamicRecords(snapshot: FrozenSnapshot) {
  return snapshot.dynamicRecords
    ? Promise.resolve(snapshot.dynamicRecords)
    : loadDataFile<DynamicRecordPage>(snapshot.dataFiles?.dynamicRecords, 'JD 列表')
}

function loadDynamicRecordDetails(snapshot: FrozenSnapshot) {
  return snapshot.dynamicRecordDetails
    ? Promise.resolve(snapshot.dynamicRecordDetails)
    : loadDataFile<Record<string, DynamicRecordDetail>>(snapshot.dataFiles?.dynamicRecordDetails, 'JD 详情')
}

function loadRealSnapshot(snapshot: FrozenSnapshot) {
  return snapshot.realSnapshot
    ? Promise.resolve(snapshot.realSnapshot)
    : loadDataFile<RealSnapshot>(snapshot.dataFiles?.realSnapshot, '发布画像')
}

function role(snapshot: FrozenSnapshot, roleId: number) {
  return snapshot.roles.find(item => item.id === roleId) ?? snapshot.roles[0]
}

function evidenceFor(marketCards: Record<string, MarketCard>, roleId: number, skill: string) {
  const card = marketCards[String(roleId)]
  if (!card) return []
  const matched = (card.evidence ?? []).filter(item => item.quote.toLowerCase().includes(skill.toLowerCase()))
  return (matched.length ? matched : card.evidence ?? []).slice(0, 5)
}

function profileEvolution(snapshot: FrozenSnapshot, selected: JobRole, card: MarketCard): RoleEvolutionRun {
  const requirements = [...selected.requiredSkills, ...selected.bonusSkills]
  const changes = requirements.slice(0, 8).map((item, index): RoleEvolutionRun['changes'][number] => {
    const evidence = evidenceFor({ [String(selected.id)]: card }, selected.id, item.skill).map((source, evidenceIndex) => ({
      id: selected.id * 1000 + index * 10 + evidenceIndex,
      windowType: 'CURRENT' as const,
      documentId: source.documentId,
      title: selected.name,
      company: source.company,
      source: source.source,
      sourceUrl: '',
      publishedAt: source.publishedAt,
      quote: source.quote,
      startOffset: source.startOffset,
      endOffset: source.endOffset,
      anchorValid: true,
      direction: 'STABLE'
    }))
    return {
      id: selected.id * 100 + index + 1,
      skill: item.skill,
      changeType: 'STABLE',
      confidenceLevel: 'OBSERVATION_SIGNAL',
      absoluteChange: 0,
      differenceLowerBound: 0,
      differenceUpperBound: 0,
      sensitivityDirection: 'STABLE',
      consecutiveAbsentWindows: 0,
      publishable: false,
      includedInDraft: false,
      points: [{ period: '当前岗位资料', documentCount: card.sampleSize, companyCount: card.independentCompanies, demandRate: item.demandRate, lowerBound: Math.max(0, item.demandRate - 0.05), upperBound: Math.min(1, item.demandRate + 0.05) }],
      evidence
    }
  })
  return {
    id: 800000 + selected.id,
    runCode: `PROFILE-${selected.code}-${snapshot.snapshot.code}`,
    roleId: selected.id,
    roleCode: selected.code,
    roleName: selected.name,
    policyVersion: '岗位画像快照',
    policyHash: snapshot.snapshot.sourceHash,
    inputHash: snapshot.snapshot.sourceHash,
    status: 'INSUFFICIENT_DATA',
    conclusionLevel: 'INSUFFICIENT_TIME_DATA',
    sampleControl: '当前岗位市场卡片',
    statisticsMethod: '岗位技能与证据映射',
    version: 1,
    draftNote: '当前岗位已有技能与原文证据，但尚未形成可比较的双时间窗口趋势。',
    windows: [{ id: 1, type: 'CURRENT', label: '当前岗位资料', startDate: snapshot.snapshot.generatedAt.slice(0, 10), endDate: snapshot.snapshot.generatedAt.slice(0, 10), documentCount: card.sampleSize, companyCount: card.independentCompanies, sourceCount: card.evidence.length, sourceNames: [...new Set(card.evidence.map(item => item.source))], cityScope: '当前快照', levelScope: selected.level, sampleHash: snapshot.snapshot.sourceHash }],
    changes,
    createdAt: snapshot.snapshot.generatedAt,
    updatedAt: snapshot.snapshot.generatedAt
  }
}

function graphFor(snapshot: FrozenSnapshot, roleId: number, viewType: string): GraphView {
  const selected = role(snapshot, roleId)
  const nodes: GraphView['nodes'] = [{
    id: `role-${selected.id}`, label: selected.name, type: 'JOB_ROLE', group: selected.family,
    evidenceCount: selected.requiredSkills.length, status: 'FROZEN'
  }]
  const edges: GraphView['edges'] = []
  ;[...selected.requiredSkills, ...selected.bonusSkills].forEach((item, index) => {
    const id = `skill-${index}`
    nodes.push({ id, label: item.skill, type: 'SKILL', group: item.category, evidenceCount: item.evidenceCount, status: 'FROZEN' })
    edges.push({ id: `edge-${index}`, source: `role-${selected.id}`, target: id, relation: item.requirementType === 'REQUIRED' ? 'REQUIRES' : 'PREFERS', weight: item.demandRate, status: 'FROZEN' })
  })
  selected.responsibilities.forEach((item, index) => {
    const id = `responsibility-${index}`
    nodes.push({ id, label: item, type: 'RESPONSIBILITY', group: '职责', evidenceCount: 1, status: 'FROZEN' })
    edges.push({ id: `responsibility-edge-${index}`, source: `role-${selected.id}`, target: id, relation: 'PERFORMS', weight: 1, status: 'FROZEN' })
  })
  return {
    releaseVersion: snapshot.snapshot.code,
    viewType,
    nodes,
    edges,
    metadata: { dataMode: 'FROZEN_CACHE', graphRelease: snapshot.snapshot.code, sampleSize: snapshot.snapshot.sourceDocumentCount, evidenceAvailable: true }
  }
}

export const frozenSnapshot = {
  load: loadFrozenSnapshot,
  runtimeStatus: async () => (await loadFrozenSnapshot()).runtimeStatus,
  dashboard: async () => (await loadFrozenSnapshot()).dashboard,
  publicOverview: async () => trustedPublicOverview(await loadFrozenSnapshot()),
  publicGraphOverview: async (filters: GraphFilterParams = {}) => {
    const snapshot = await loadFrozenSnapshot()
    const normalized = normalizeGraphFilters(filters)
    const techStackKey = normalized.techStack?.toLocaleLowerCase('zh-CN')
    const filteredGraph = techStackKey ? snapshot.publicGraphFilters?.techStack?.[techStackKey] : undefined
    if (!filteredGraph) return filterGraphByRoles(publicGraph(snapshot, snapshot.publicGraphOverview), trustedPublicOverview(snapshot).roles, normalized)
    const remainingFilters = { ...normalized }
    delete remainingFilters.techStack
    return filterGraphByRoles(publicGraph(snapshot, filteredGraph), trustedPublicOverview(snapshot).roles, remainingFilters)
  },
  roles: async () => catalogRoles(await loadFrozenSnapshot()),
  marketCard: async (roleId: number) => {
    const snapshot = await loadFrozenSnapshot()
    const marketCards = await loadMarketCards(snapshot)
    return marketCards[String(roleId)] ?? Object.values(marketCards)[0]
  },
  marketTimeline: async (roleId: number) => {
    const snapshot = await loadFrozenSnapshot()
    return snapshot.marketTimelines[String(roleId)] ?? { roleId, periods: [] }
  },
  repositoryOverview: async () => (await loadFrozenSnapshot()).repositoryOverview,
  quality: async () => (await loadFrozenSnapshot()).quality,
  emerging: async () => (await loadFrozenSnapshot()).emergingCandidates,
  emergingQueries: async (limit = 12) => publicEmergingQueries((await loadFrozenSnapshot()).emergingQueries ?? [], limit),
  evolution: async (roleId: number): Promise<EvolutionReport> => {
    const snapshot = await loadFrozenSnapshot()
    const selected = role(snapshot, roleId)
    const marketCards = await loadMarketCards(snapshot)
    const card = marketCards[String(selected.id)] ?? Object.values(marketCards)[0]
    return {
      roleId: selected.id, roleName: selected.name, baselineRelease: '', currentRelease: snapshot.snapshot.code,
      sampleControl: snapshot.snapshot.disclaimer, conclusionLevel: 'INSUFFICIENT_TIME_DATA', skills: [],
      evidence: card?.evidence ?? [], metadata: { dataMode: 'FROZEN_CACHE', graphRelease: snapshot.snapshot.code, sampleSize: card?.sampleSize ?? 0, independentCompanies: card?.independentCompanies ?? 0, confidence: 0, evidenceAvailable: Boolean(card?.evidence.length) }
    }
  },
  roleEvolution: async (roleId: number): Promise<RoleEvolutionRun> => {
    const snapshot = await loadFrozenSnapshot()
    const run = snapshot.roleEvolutions?.[String(roleId)]
    if (run) return run
    const selected = role(snapshot, roleId)
    if (selected.emerging) throw new Error('新型岗位暂不进入岗位演化。')
    const marketCards = await loadMarketCards(snapshot)
    const card = marketCards[String(selected.id)]
    if (card) return profileEvolution(snapshot, selected, card)
    throw new Error('暂无公开岗位演化数据，请选择其他岗位。')
  },
  releases: async (): Promise<GraphRelease[]> => {
    const snapshot = await loadFrozenSnapshot()
    return [{ id: 1, version: snapshot.snapshot.code, status: 'FROZEN', snapshotId: snapshot.snapshot.code, policyVersion: snapshot.quality.policyVersion, publisher: '资料管理流程', changes: ['发布已核验岗位资料'], publishedAt: snapshot.snapshot.generatedAt }]
  },
  evidenceExplanation: async (jobRoleId: number, skill?: string): Promise<EvidenceExplanation> => {
    const snapshot = await loadFrozenSnapshot()
    const selected = role(snapshot, jobRoleId)
    const marketCards = await loadMarketCards(snapshot)
    const card = marketCards[String(selected.id)] ?? Object.values(marketCards)[0]
    const evidence = (skill ? evidenceFor(marketCards, selected.id, skill) : card?.evidence ?? []).slice(0, 3)
    return { questionType: 'SKILL_REQUIREMENT', explanation: skill ? `${skill} 出现在当前岗位原文证据中；结论仅适用于本期资料。` : snapshot.snapshot.disclaimer, evidence, refused: evidence.length === 0, metadata: { dataMode: 'FROZEN_CACHE', graphRelease: snapshot.snapshot.code, sampleSize: card?.sampleSize ?? 0, independentCompanies: card?.independentCompanies ?? 0, confidence: 0.5, evidenceAvailable: evidence.length > 0 } }
  },
  dynamicRecords: async (params: { status?: string; keyword?: string; riskLevel?: string; reviewRoute?: string; onlyExceptions?: boolean; page?: number; size?: number }) => {
    const snapshot = await loadFrozenSnapshot()
    const dynamicRecords = await loadDynamicRecords(snapshot)
    const keyword = params.keyword?.trim().toLowerCase()
    const items = dynamicRecords.items.filter(item => {
      if (params.status && item.status !== params.status) return false
      if (params.riskLevel && item.riskLevel !== params.riskLevel) return false
      if (params.reviewRoute && item.reviewRoute !== params.reviewRoute) return false
      if (params.onlyExceptions && item.reviewRoute !== 'EXCEPTION_REVIEW') return false
      return !keyword || `${item.title} ${item.company} ${item.city}`.toLowerCase().includes(keyword)
    })
    const page = Math.max(1, params.page ?? 1)
    const size = Math.max(1, params.size ?? 20)
    return { items: items.slice((page - 1) * size, page * size), page, size, total: items.length }
  },
  dynamicRecord: async (id: number) => {
    const snapshot = await loadFrozenSnapshot()
    const detail = (await loadDynamicRecordDetails(snapshot))[String(id)]
    if (!detail) throw new Error('未找到这条岗位资料。')
    return detail
  },
  candidateSkills: async () => (await loadFrozenSnapshot()).candidateSkills,
  reviewQueueSummary: async () => (await loadFrozenSnapshot()).reviewQueueSummary,
  realSnapshot: async () => loadRealSnapshot(await loadFrozenSnapshot()),
  sourceLedger: async () => (await loadFrozenSnapshot()).sourceLedger ?? [],
  evaluationSummary: async () => (await loadFrozenSnapshot()).evaluationSummary,
  graph: async (roleId: number, viewType: string, filters: GraphFilterParams = {}) => {
    const snapshot = await loadFrozenSnapshot()
    return filterGraphByRoles(publicGraph(snapshot, graphFor(snapshot, roleId, viewType)), trustedPublicOverview(snapshot).roles, filters)
  },
  roleSkillEvidence: async (roleId: number, skill: string): Promise<RoleSkillEvidence> => {
    const snapshot = await loadFrozenSnapshot()
    const selected = role(snapshot, roleId)
    const requirement = [...selected.requiredSkills, ...selected.bonusSkills].find(item => item.skill === skill)
    const evidence = evidenceFor(await loadMarketCards(snapshot), roleId, skill).map(item => ({
      documentId: item.documentId, title: selected.name, company: item.company, quote: item.quote,
      startOffset: item.startOffset, endOffset: item.endOffset, sourceUrl: ''
    }))
    return {
      roleId, roleName: selected.name, skill, requirementType: requirement?.requirementType ?? 'MENTIONED',
      demandRate: requirement?.demandRate ?? 0, documentCount: evidence.length,
      companyCount: new Set(evidence.map(item => item.company)).size, snapshotCode: snapshot.snapshot.code,
      dataScope: snapshot.snapshot.usageScope, evidence
    }
  }
}

export function rejectFrozenWrite(operation: string): never {
  throw new Error(`${operation}暂不可用；当前资料仅供查看，如需修改请联系资料管理员。`)
}
