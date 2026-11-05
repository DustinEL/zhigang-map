import type {
  CandidateSkillSummary,
  ChangeProposal,
  DynamicRecordDetail,
  DynamicRecordPage,
  DashboardSummary,
  EmergingCandidate,
  EvaluationSummary,
  EvolutionReport,
  ExtractionResult,
  GraphRelease,
  GraphView,
  JobRole,
  LearningPlan,
  MarketCard,
  MatchReport,
  QualityReport,
  PromotionBatch,
  PublicOverview,
  RealSnapshot,
  RepositoryOverview,
  RoleMarketTimeline,
  RoleEvolutionRun,
  SourceLedgerItem,
  StructuredImportResult,
  ResumeProfile
} from '../types/models'

const updatedAt = '2026-07-10T16:00:00+08:00'

export const sourceLedger: SourceLedgerItem[] = []

export const structuredImportResult: StructuredImportResult = {
  id: 0,
  runCode: 'offline-preview',
  sourceFileName: 'jd_structured.json',
  sourceFileHash: '8fb62d428de36512e72e3afea00e7a46ee66070cb266e3723fa58be75e8cbb63',
  fetchedCount: 10,
  acceptedCount: 10,
  duplicateCount: 0,
  quarantinedCount: 0,
  autoApprovedCount: 0,
  sampledCount: 0,
  exceptionCount: 0,
  waitingSkillCount: 2,
  status: 'OFFLINE_PREVIEW',
  policyVersion: 'jd-pilot-v1.1',
  createdAt: updatedAt
}

const offlineRecordItems: DynamicRecordPage['items'] = [
  {
    id: 1,
    importId: 0,
    sourceRecordKey: 'preview-ai-agent-01',
    title: 'AI Agent 应用开发工程师',
    company: '示例企业 A',
    city: '北京',
    contentHash: 'offline-preview-ai-agent',
    dataMode: 'DEMO',
    status: 'ELIGIBLE',
    qualityScore: 0.96,
    skillCount: 12,
    candidateSkillCount: 4,
    issueCount: 0,
    reviewDecision: 'PENDING_REVIEW',
    riskLevel: 'MEDIUM', reviewRoute: 'WAITING_SKILL', reviewReasons: ['演示候选技能待审核'], reviewSource: 'NONE',
    collectedAt: updatedAt
  },
  {
    id: 2,
    importId: 0,
    sourceRecordKey: 'preview-java-01',
    title: 'Java 开发工程师',
    company: '示例企业 B',
    city: '上海',
    contentHash: 'offline-preview-java',
    dataMode: 'DEMO',
    status: 'ELIGIBLE',
    qualityScore: 0.94,
    skillCount: 10,
    candidateSkillCount: 3,
    issueCount: 0,
    reviewDecision: 'PENDING_REVIEW',
    riskLevel: 'MEDIUM', reviewRoute: 'WAITING_SKILL', reviewReasons: ['演示候选技能待审核'], reviewSource: 'NONE',
    collectedAt: updatedAt
  }
]

export const dynamicRecordPage: DynamicRecordPage = {
  items: offlineRecordItems,
  page: 1,
  size: 20,
  total: offlineRecordItems.length
}

export const dynamicRecordDetail = (id: number): DynamicRecordDetail => {
  const summary = offlineRecordItems.find(item => item.id === id) ?? offlineRecordItems[0]
  const javaRole = summary.title.includes('Java')
  const rawText = javaRole
    ? '负责 Java 后端服务开发与维护，要求熟悉 Spring Boot、MySQL 和 Redis；具备 Docker 项目经验者优先。'
    : '负责 AI Agent 应用开发，要求熟悉 Python、RAG、工具调用和工作流编排；具备模型评测经验者优先。'
  const knownSkill = javaRole ? 'Spring Boot' : 'Python'
  const candidateSkill = javaRole ? '容器化部署' : '工作流编排'
  return {
    summary,
    sourceName: '离线预览数据',
    licenseStatus: 'DEMO_ONLY',
    sourceUrl: '',
    canonicalUrl: '',
    salary: javaRole ? '15k-25k' : '20k-35k',
    experienceRequirement: '1-3 年',
    educationRequirement: '本科',
    rawText,
    facts: [
      { id: id * 10 + 1, factType: 'SKILL', ontologyStatus: 'KNOWN', canonicalValue: knownSkill, rawValue: knownSkill, requirementType: 'REQUIRED', startOffset: 0, endOffset: knownSkill.length, evidenceQuote: rawText, confidence: 0.98, offsetVerified: true, reviewDecision: 'NOT_REQUIRED' },
      { id: id * 10 + 2, factType: 'SKILL', ontologyStatus: 'CANDIDATE', canonicalValue: candidateSkill, suggestedCategory: '工程能力', rawValue: candidateSkill, requirementType: 'MENTIONED', startOffset: 0, endOffset: candidateSkill.length, evidenceQuote: rawText, confidence: 0.86, offsetVerified: true, reviewDecision: 'PENDING_REVIEW' }
    ],
    issues: [],
    reviews: []
  }
}

export const candidateSkills: CandidateSkillSummary[] = [
  { skill: '工作流编排', suggestedCategory: 'AI 应用工程', factCount: 3, companyCount: 3, reviewDecision: 'PENDING_REVIEW', decisionSource: 'NEW', requirementTypes: ['REQUIRED', 'MENTIONED'], recommendedDecision: 'APPROVED_STANDARD', recommendationReasons: ['至少两家不同企业出现', '原文偏移有效且置信度不低于 0.85'], evidenceSamples: [] },
  { skill: '工具调用', suggestedCategory: 'AI 应用工程', factCount: 3, companyCount: 3, reviewDecision: 'PENDING_REVIEW', decisionSource: 'NEW', requirementTypes: ['REQUIRED'], recommendedDecision: 'APPROVED_STANDARD', recommendationReasons: ['至少两家不同企业出现'], evidenceSamples: [] },
  { skill: '容器化部署', suggestedCategory: '工程能力', factCount: 2, companyCount: 2, reviewDecision: 'PENDING_REVIEW', decisionSource: 'NEW', requirementTypes: ['BONUS'], recommendedDecision: 'OBSERVING', recommendationReasons: ['现有证据稳定性不足'], evidenceSamples: [] }
]

export const promotionBatch = (recordIds: number[], requestedBy: string): PromotionBatch => ({
  id: 0,
  batchCode: 'offline-preview',
  snapshotCode: 'offline-preview',
  policyVersion: 'jd-pilot-v1.1',
  status: 'DRAFT',
  candidateCount: recordIds.length,
  promotedCount: 0,
  rejectedCount: 0,
  requestedBy,
  createdAt: updatedAt,
  items: offlineRecordItems.filter(item => recordIds.includes(item.id)).map(item => ({
    recordId: item.id,
    title: item.title,
    company: item.company,
    decision: 'PENDING'
  }))
})

export const realSnapshot: RealSnapshot = {
  snapshotCode: '',
  dataMode: 'DEMO',
  usageScope: 'WAITING_FOR_PUBLISHED_REAL_DATA',
  documentCount: 0,
  independentCompanyCount: 0,
  publishedTimeCoverage: false,
  roles: []
}

export const roles: JobRole[] = [
  {
    id: 1,
    code: 'RAG_APP_ENGINEER',
    name: 'RAG 应用开发工程师',
    family: 'AI 应用研发',
    level: '初中级',
    emerging: false,
    definition: '面向企业知识库、智能客服和研发助手，负责检索增强生成链路、证据引用、评测与工程治理。',
    salaryRange: '18k-35k',
    responsibilities: ['构建企业知识库问答系统', '设计文档切分、检索、重排和生成链路', '建立引用与幻觉评测', '保障数据权限与服务稳定性'],
    requiredSkills: [
      ['Python', '编程语言', 0.82, 24, '稳定'],
      ['RAG', 'AI应用', 0.88, 28, '增强'],
      ['Embedding', 'AI应用', 0.76, 21, '增强'],
      ['向量数据库', 'AI数据基础设施', 0.79, 22, '增强'],
      ['大语言模型', 'AI应用', 0.91, 30, '稳定'],
      ['Prompt Engineering', 'AI应用', 0.72, 19, '稳定']
    ].map(([skill, category, demandRate, evidenceCount, trend]) => ({ skill: String(skill), category: String(category), requirementType: 'REQUIRED', demandRate: Number(demandRate), evidenceCount: Number(evidenceCount), trend: String(trend) })),
    bonusSkills: [
      ['LangChain', 'AI应用', 0.63, 16, '稳定'],
      ['模型评测', 'AI工程化', 0.52, 13, '新增'],
      ['知识图谱', 'AI数据基础设施', 0.31, 8, '稳定']
    ].map(([skill, category, demandRate, evidenceCount, trend]) => ({ skill: String(skill), category: String(category), requirementType: 'BONUS', demandRate: Number(demandRate), evidenceCount: Number(evidenceCount), trend: String(trend) })),
    scenarios: ['企业知识库', '智能客服', '政务问答', '研发助手'],
    releaseVersion: '2026.07',
    updatedAt
  },
  {
    id: 2,
    code: 'JAVA_ENGINEER',
    name: 'Java 开发工程师',
    family: '后端与云原生',
    level: '初中级',
    emerging: false,
    definition: '负责企业级后端服务、数据访问和分布式系统开发，并逐步承担云原生部署与 AI 能力集成。',
    salaryRange: '12k-26k',
    responsibilities: ['开发后端接口与业务服务', '设计数据库和缓存方案', '治理微服务稳定性', '接入云原生和 AI 应用能力'],
    requiredSkills: [
      ['Java', '编程语言', 0.96, 36, '稳定'],
      ['Spring Boot', '后端框架', 0.91, 34, '稳定'],
      ['MySQL', '数据库', 0.86, 31, '稳定'],
      ['Redis', '数据库', 0.73, 26, '稳定'],
      ['Linux', '工程化', 0.64, 22, '稳定']
    ].map(([skill, category, demandRate, evidenceCount, trend]) => ({ skill: String(skill), category: String(category), requirementType: 'REQUIRED', demandRate: Number(demandRate), evidenceCount: Number(evidenceCount), trend: String(trend) })),
    bonusSkills: [
      ['Docker', '云原生', 0.68, 24, '增强'],
      ['Kubernetes', '云原生', 0.48, 17, '增强'],
      ['RAG', 'AI应用', 0.21, 8, '新增'],
      ['大语言模型', 'AI应用', 0.26, 9, '新增'],
      ['JSP/Servlet', '传统技术', 0.09, 4, '弱化']
    ].map(([skill, category, demandRate, evidenceCount, trend]) => ({ skill: String(skill), category: String(category), requirementType: 'BONUS', demandRate: Number(demandRate), evidenceCount: Number(evidenceCount), trend: String(trend) })),
    scenarios: ['企业信息化', '互联网平台', '金融科技'],
    releaseVersion: '2026.07',
    updatedAt
  },
  {
    id: 3,
    code: 'AI_AGENT_ENGINEER',
    name: 'AI Agent 开发工程师',
    family: 'AI 应用研发',
    level: '中级',
    emerging: false,
    definition: '负责智能体任务规划、工具调用、记忆检索、流程编排和效果评测。',
    salaryRange: '22k-40k',
    responsibilities: ['设计智能体任务规划', '接入工具调用与工作流', '构建记忆和检索系统', '建立安全与效果评测'],
    requiredSkills: ['Python', '大语言模型', 'AI Agent', 'Prompt Engineering'].map((skill, index) => ({ skill, category: 'AI应用', requirementType: 'REQUIRED', demandRate: 0.92 - index * 0.06, evidenceCount: 17 - index, trend: index === 2 ? '增强' : '稳定' })),
    bonusSkills: ['RAG', 'LangChain', '模型评测'].map((skill, index) => ({ skill, category: 'AI应用', requirementType: 'BONUS', demandRate: 0.66 - index * 0.08, evidenceCount: 11 - index, trend: index === 2 ? '新增' : '稳定' })),
    scenarios: ['办公助手', '自动化运营', '研发协作'],
    releaseVersion: '2026.07',
    updatedAt
  }
]

const fullRole = (id: number, code: string, name: string, family: string, definition: string, required: string[], bonus: string[], scenarios: string[]): JobRole => ({
  id, code, name, family, level: '初中级', emerging: id === 6, definition, salaryRange: '样本待补充',
  responsibilities: [definition],
  requiredSkills: required.map((skill, index) => ({ skill, category: '岗位能力', requirementType: 'REQUIRED', demandRate: 0.82 - index * 0.05, evidenceCount: Math.max(6, 14 - index), trend: '稳定' })),
  bonusSkills: bonus.map((skill, index) => ({ skill, category: '岗位能力', requirementType: 'BONUS', demandRate: 0.52 - index * 0.05, evidenceCount: Math.max(4, 8 - index), trend: '稳定' })),
  scenarios, releaseVersion: '2026.07', updatedAt
})

roles.push(
  fullRole(4, 'AI_APP_BACKEND_ENGINEER', 'AI 应用后端工程师', 'AI 应用研发', '负责将大模型、RAG 和智能体能力接入稳定的后端服务。', ['Java', 'Spring Boot', '大语言模型', 'RAG'], ['Python', 'Docker', '权限控制'], ['企业知识库', '智能客服']),
  fullRole(5, 'LLMOPS_ENGINEER', 'LLMOps / 模型服务工程师', 'AI 平台工程', '负责大模型服务部署、版本管理、监控告警和稳定性治理。', ['Python', 'Linux', 'Docker', 'Kubernetes', '大语言模型'], ['模型评测', '权限控制'], ['模型 API 平台', '私有化部署']),
)

;[
  [8, 'AI_PLATFORM_ENGINEER', 'AI 平台开发工程师', 'AI 平台工程', '建设模型接入、应用编排和运营治理平台。'],
  [10, 'KNOWLEDGE_ENGINEER', '知识工程师', '数据与知识工程', '负责知识建模、治理和知识应用。'],
  [11, 'VECTOR_RETRIEVAL_ENGINEER', '向量检索工程师', '数据与知识工程', '负责向量索引、召回和检索性能优化。']
].forEach(([id, code, name, family, definition]) => roles.push({ id: Number(id), code: String(code), name: String(name), family: String(family), level: '画像建设中', emerging: false, definition: `${definition}目录画像待真实数据补充。`, salaryRange: '样本不足', responsibilities: [], requiredSkills: [], bonusSkills: [], scenarios: [], releaseVersion: '2026.07', updatedAt }))

roles.push(
  fullRole(7, 'LLM_APP_ENGINEER', '大模型应用开发工程师', 'AI 应用研发', '面向业务场景开发大模型应用。', ['Python', '大语言模型', 'RAG', 'Prompt Engineering'], ['AI Agent', 'Docker', 'Git'], ['企业知识库', '智能问答']),
  fullRole(9, 'AI_DATA_ENGINEER', 'AI 数据工程师', '数据与知识工程', '建设 AI 应用所需的数据处理、质量和治理管道。', ['Python', 'SQL', '数据仓库', '数据治理'], ['Flink', 'Spark', 'Kafka'], ['数据分析', '数据平台']),
  fullRole(12, 'AI_SECURITY_TEST_ENGINEER', 'AI 安全测试工程师', 'AI 质量与安全', '负责提示注入、数据泄露和模型应用安全测试。', ['安全测试', '提示注入', '数据安全', 'Python'], ['模型安全', '对抗攻击', '内容安全'], ['模型风控', '安全评测']),
  {
    ...fullRole(13, 'DISCOVERED_085584C95CDFBAD8', '大模型推理加速工程师', '新兴岗位',
      '负责大模型推理效率优化、模型压缩、解码优化及软硬件协同部署。',
      ['模型推理', '模型量化', 'KV Cache', 'FlashAttention', 'vLLM'],
      ['TensorRT-LLM', 'C++', 'CUDA'],
      ['云端大模型服务', '边缘设备部署', '低延迟智能系统']),
    level: '通用',
    emerging: true,
    releaseVersion: 'emerging-q-20260804-8'
  }
)

const publicRole = (role: JobRole, status: 'PUBLISHED' | 'BASELINE' | 'BUILDING', sampleCount?: number, companyCount?: number) => ({
  id: role.id,
  code: role.code,
  name: role.name,
  family: role.family,
  emerging: role.emerging,
  definition: role.definition.replace('目录画像待真实数据补充。', ''),
  suitableBackground: role.code === 'JAVA_ENGINEER' ? '有 Java、数据库或后端项目基础的学生' : role.family === 'AI 应用研发' ? '有编程基础，希望进入大模型应用研发的学生' : '有计算机基础，希望了解该方向的学生',
  status,
  coreSkills: [...role.requiredSkills, ...role.bonusSkills].slice(0, 5).map(item => item.skill),
  responsibilities: role.responsibilities.slice(0, 3),
  jobLevel: role.level,
  techStack: [...role.requiredSkills, ...role.bonusSkills].flatMap(item => [item.category, item.skill]).filter((value, index, values) => values.indexOf(value) === index),
  roleVersion: role.releaseVersion,
  evidenceLayer: status === 'PUBLISHED' ? 'REAL_EVIDENCE' as const : status,
  ...(status === 'PUBLISHED' ? { sampleCount, companyCount, updatedAt: role.updatedAt } : {})
})

export const publicOverview: PublicOverview = {
  updatedAt,
  dataScope: '12 岗分层目录；仅真实证据画像计入样本',
  sampleCount: 10,
  companyCount: 10,
  roles: roles.slice(0, 12).map(role => {
    if (['RAG_APP_ENGINEER', 'AI_AGENT_ENGINEER', 'JAVA_ENGINEER'].includes(role.code)) {
      return publicRole(role, 'PUBLISHED', 5, 5)
    }
    return publicRole(role, role.requiredSkills.length ? 'BASELINE' : 'BUILDING')
  }),
  topSkills: ['Java', 'Spring Boot', '大语言模型', 'AI Agent', 'RAG', 'Prompt Engineering'],
  disclaimer: '当前样本用于岗位能力观察，不表示整体市场趋势'
}

function graphNodeId(prefix: string, value: string | number) {
  return `${prefix}-${String(value).toLowerCase().replace(/[^a-z0-9\p{Script=Han}]+/gu, '-')}`
}

export const publicGraphOverview: GraphView = (() => {
  const nodes = new Map<string, GraphView['nodes'][number]>()
  const edges: GraphView['edges'] = []
  publicOverview.roles.forEach(role => {
    const familyId = graphNodeId('family', role.family)
    nodes.set(familyId, { id: familyId, label: role.family, type: 'JOB_FAMILY', group: role.family, evidenceCount: 0, status: 'DIRECTORY' })
    const roleId = `role-${role.id}`
    const sourceRole = roles.find(item => item.id === role.id)
    const evidenceCount = role.status === 'PUBLISHED' ? sourceRole?.requiredSkills.reduce((total, item) => total + item.evidenceCount, 0) ?? 0 : 0
    nodes.set(roleId, { id: roleId, label: role.name, type: 'JOB_ROLE', group: role.family, evidenceCount, status: role.status })
    edges.push({ id: `public-edge-${edges.length + 1}`, source: familyId, target: roleId, relation: 'CONTAINS', weight: 1, status: role.status })
    if (role.status === 'BUILDING') return
    role.coreSkills.forEach(skill => {
      const skillId = graphNodeId('skill', skill)
      const requirement = [...(sourceRole?.requiredSkills ?? []), ...(sourceRole?.bonusSkills ?? [])].find(item => item.skill === skill)
      const status = role.status === 'PUBLISHED' ? 'REAL_EVIDENCE' : 'BASELINE'
      const existing = nodes.get(skillId)
      if (!existing || status === 'REAL_EVIDENCE') nodes.set(skillId, { id: skillId, label: skill, type: 'SKILL', group: requirement?.category ?? '岗位能力', evidenceCount: status === 'REAL_EVIDENCE' ? requirement?.evidenceCount ?? 0 : 0, status })
      edges.push({ id: `public-edge-${edges.length + 1}`, source: roleId, target: skillId, relation: requirement?.requirementType === 'BONUS' ? 'PREFERS' : 'REQUIRES', weight: requirement?.demandRate || 1, status: role.status })
    })
  })
  return { releaseVersion: '2026.07', viewType: 'PUBLIC_PANORAMA', nodes: [...nodes.values()], edges, metadata: { dataMode: 'DEMO', graphRelease: '2026.07', sampleSize: 10, independentCompanies: 10, evidenceAvailable: true } }
})()

const evidence = (id: number, company: string, quote: string) => ({ documentId: id, source: '授权招聘快照', company, publishedAt: '2026-06-10T09:00:00+08:00', quote, startOffset: 0, endOffset: quote.length, timeWeight: 0.89, gateStatus: 'EVIDENCE_VALID' })

export const dashboard: DashboardSummary = {
  sourceCount: 3,
  rawDocumentCount: 314,
  independentCompanyCount: 26,
  skillCount: 22,
  publishedRoleCount: 12,
  pendingReviewCount: 4,
  currentRelease: '2026.07',
  operatingMode: 'DEMO_OFFLINE',
  pipeline: [
    { name: '多源快照', status: 'COMPLETED', count: 314, detail: '招聘、课程、政策原文只读留存' },
    { name: '质量管道', status: 'COMPLETED', count: 286, detail: '近重复、时滞、通胀和注入检查' },
    { name: '证据化抽取', status: 'COMPLETED', count: 862, detail: '规则通道 + 原文锚点' },
    { name: '候选审核', status: 'RUNNING', count: 5, detail: '新岗位和 Java 演化变更' },
    { name: '图谱发布', status: 'COMPLETED', count: 1, detail: '图谱版本 2026.07' }
  ],
  trends: [
    { label: 'RAG 需求率', values: [0.28, 0.42, 0.63, 0.81], periods: ['2025Q3', '2025Q4', '2026Q1', '2026Q2'] },
    { label: 'Docker 需求率', values: [0.41, 0.48, 0.59, 0.68], periods: ['2025Q3', '2025Q4', '2026Q1', '2026Q2'] },
    { label: 'JSP/Servlet 需求率', values: [0.31, 0.23, 0.15, 0.09], periods: ['2025Q3', '2025Q4', '2026Q1', '2026Q2'] }
  ]
}

export const marketCard = (roleId: number): MarketCard => {
  const role = roles.find(item => item.id === roleId) ?? roles[0]
  return {
    role,
    sampleSize: role.id === 1 ? 122 : 120,
    independentCompanies: role.id === 1 ? 10 : 10,
    demandHeat: role.id === 1 ? 86 : 78,
    salaryQuartiles: role.id === 1 ? [18, 25, 35] : [12, 18, 26],
    cityDistribution: { 北京: 0.24, 上海: 0.21, 深圳: 0.19, 杭州: 0.16, 南京: 0.11, 成都: 0.09 },
    levelDistribution: { 初级: 0.34, 中级: 0.48, 高级: 0.18 },
    commonSkillCombinations: role.requiredSkills.slice(0, 6).reduce<string[]>((result, item, index, all) => index % 2 === 0 ? [...result, all.slice(index, index + 3).map(skill => skill.skill).join(' + ')] : result, []).slice(0, 3),
    evidence: [
      evidence(1, '北辰智算', `必须掌握 ${role.requiredSkills.slice(0, 4).map(item => item.skill).join('、')}`),
      evidence(2, '启明科技', `负责${role.responsibilities[0]}，建立可追溯工程结果`),
      evidence(3, '远景智能', `${role.bonusSkills.slice(0, 3).map(item => item.skill).join('、')}经验优先`)
    ],
    confidence: 0.9,
    sampleScope: '演示快照 · 2025-08 至 2026-07 · 六城市 · 初中级',
    metadata: { dataMode: 'DEMO', graphRelease: role.releaseVersion, timeWindow: '2025-08 至 2026-07', sampleSize: role.requiredSkills.length ? (role.id === 1 ? 122 : 120) : 0, independentCompanies: role.requiredSkills.length ? 10 : 0, confidence: role.requiredSkills.length ? .9 : 0, evidenceAvailable: role.requiredSkills.length > 0 }
  }
}

export const marketTimeline = (roleId: number): RoleMarketTimeline => {
  const configs: Record<number, { posts: number[]; median: number[]; heat: number[] }> = {
    1: { posts: [72, 79, 83, 91, 108, 122], median: [21, 22, 22, 23, 24, 25], heat: [61, 65, 68, 73, 80, 86] },
    2: { posts: [104, 112, 118, 126, 124, 120], median: [17, 17, 18, 18, 18, 18], heat: [70, 73, 75, 79, 79, 78] },
    3: { posts: [34, 41, 48, 57, 66, 72], median: [25, 26, 27, 28, 29, 30], heat: [52, 58, 63, 69, 76, 82] }
  }
  const config = configs[roleId] ?? configs[1]
  const labels = ['2026-01', '2026-02', '2026-03', '2026-04', '2026-05', '2026-06']
  return {
    roleId,
    periods: labels.map((period, index) => ({
      period,
      postingCount: config.posts[index],
      newPostingCount: Math.max(8, Math.round(config.posts[index] * (0.22 + index * 0.01))),
      salaryP25: config.median[index] - 6,
      salaryMedian: config.median[index],
      salaryP75: config.median[index] + 10,
      demandIndex: config.heat[index],
      evidenceCount: config.posts[index],
      confidence: 0.86 + index * 0.01
    }))
  }
}

export const repositoryOverview: RepositoryOverview = {
  baseLibrary: { name: '基础资料库', recordCount: 286, status: 'FROZEN', detail: '已审核、可追溯、可用于正式分析' },
  dynamicLibrary: { name: '动态情报库', recordCount: 128, status: 'DEMO_PIPELINE', detail: '定期采集、待质检、不能直接覆盖基础知识' },
  pipeline: [
    ['定期采集', 128, 'blue'], ['格式与合规', 121, 'cyan'], ['去重与时滞', 104, 'violet'],
    ['证据化抽取', 96, 'amber'], ['专家审核', 32, 'green'], ['晋级基础库', 28, 'teal']
  ].map(([name, count, tone]) => ({ name: String(name), count: Number(count), tone: String(tone) })),
  crawlerTasks: [
    { name: '授权招聘快照', schedule: '每日 02:00', parserVersion: 'parser-v1.3', recordCount: 72, status: 'DEMO' },
    { name: '行业趋势报告', schedule: '每周一 03:00', parserVersion: 'pdf-v1.1', recordCount: 31, status: 'DEMO' },
    { name: '学习资源目录', schedule: '每周五 04:00', parserVersion: 'resource-v1.0', recordCount: 25, status: 'DEMO' }
  ],
  promotion: { batchCode: 'PROMOTION-2026-07', candidates: 32, promoted: 28, quarantined: 3, rejected: 1, policyVersion: 'v1.0.0' },
  demoData: true
}

export const evaluationSummary: EvaluationSummary = {
  metrics: [
    { code: 'JD_EXTRACTION', name: 'JD 解析准确率', value: null, target: 90, sampleCount: 0, targetSamples: 100, precision: null, recall: null, f1: null, status: 'AWAITING_DATA', color: '#16835f', note: '岗位、职责、技能与要求类型' },
    { code: 'RESUME_EXTRACTION', name: '简历提取准确率', value: null, target: 90, sampleCount: 0, targetSamples: 100, precision: null, recall: null, f1: null, status: 'AWAITING_DATA', color: '#2d718f', note: '教育、项目、技能与证据片段' },
    { code: 'JOB_MATCH', name: '人岗匹配准确率', value: null, target: 90, sampleCount: 0, targetSamples: 100, precision: null, recall: null, f1: null, status: 'AWAITING_DATA', color: '#7f6aa1', note: '专家标注岗位对与差距项' }
  ],
  datasets: [
    { name: 'JD-GOLD-2026.07', type: '岗位抽取黄金集', sampleCount: 0, targetSamples: 100, version: 'v1.0', status: 'AWAITING_ANNOTATION' },
    { name: 'RESUME-GOLD-2026.07', type: '简历解析黄金集', sampleCount: 0, targetSamples: 100, version: 'v1.0', status: 'AWAITING_ANNOTATION' },
    { name: 'MATCH-GOLD-2026.07', type: '人岗匹配专家集', sampleCount: 0, targetSamples: 100, version: 'v1.0', status: 'AWAITING_ANNOTATION' }
  ],
  errors: [],
  algorithmVersion: 'algorithm-v1.2.0',
  promptVersion: 'prompt-v1.0.3',
  demoData: true
}

export const candidates: EmergingCandidate[] = roles.filter(role => role.emerging).map((role, index) => ({
  id: role.id,
  name: role.name,
  nearestRole: index ? 'RAG 应用开发工程师' : 'Java 开发工程师',
  emergenceScore: index ? 82.4 : 88.6,
  uncertainty: '低',
  documentCount: index ? 72 : 122,
  companyCount: index ? 6 : 10,
  sourceCount: 3,
  clusterStability: index ? 0.81 : 0.87,
  status: 'PENDING_REVIEW',
  responsibilities: role.responsibilities,
  requiredSkills: role.requiredSkills.map(item => item.skill),
  bonusSkills: role.bonusSkills.map(item => item.skill),
  scenarios: role.scenarios,
  supportingEvidence: marketCard(role.id).evidence,
  opposingEvidence: ['课程与证书仅作为解释证据', '岗位标题存在同义词与换名风险'],
  metadata: { dataMode: 'DEMO', graphRelease: role.releaseVersion, sampleSize: index ? 72 : 122, independentCompanies: index ? 6 : 10, confidence: index ? .81 : .87, evidenceAvailable: true }
}))

export const evolution: EvolutionReport = {
  roleId: 2,
  roleName: 'Java 开发工程师',
  baselineRelease: '2025.12',
  currentRelease: '2026.07',
  sampleControl: '同岗位、初中级、六城市、企业软件与 AI 软件',
  conclusionLevel: 'FORMAL',
  skills: [
    ['Docker', '增强', 0.27, 0.41, 0.68],
    ['Kubernetes', '增强', 0.25, 0.23, 0.48],
    ['RAG', '新增', 0.21, 0, 0.21],
    ['大语言模型', '新增', 0.26, 0, 0.26],
    ['JSP/Servlet', '弱化', -0.22, 0.31, 0.09]
  ].map(([skill, changeType, absoluteChange, previous, current]) => ({ skill: String(skill), changeType: String(changeType), absoluteChange: Number(absoluteChange), confidence: '正式趋势', points: [
    { period: '2025H2', demandRate: Number(previous), companies: 20, lowerBound: Math.max(0, Number(previous) - 0.08), upperBound: Math.min(1, Number(previous) + 0.08) },
    { period: '2026H1', demandRate: Number(current), companies: 20, lowerBound: Math.max(0, Number(current) - 0.08), upperBound: Math.min(1, Number(current) + 0.08) }
  ] })),
  evidence: marketCard(2).evidence,
  metadata: { dataMode: 'DEMO', graphRelease: '2026.07', timeWindow: '2025H2 至 2026H1', sampleSize: 120, independentCompanies: 20, confidence: .9, evidenceAvailable: true }
}

export const roleEvolution = (roleId: number): RoleEvolutionRun => {
  const role = roles.find(item => item.id === roleId) ?? roles[0]
  const now = updatedAt
  const changes: RoleEvolutionRun['changes'] = [...role.requiredSkills.slice(0, 3), ...role.bonusSkills.slice(0, 1)].map((item, index) => {
    const changeType: RoleEvolutionRun['changes'][number]['changeType'] = index === 0 ? 'INCREASED' : index === 1 ? 'NEW' : 'STABLE'
    const confidenceLevel: RoleEvolutionRun['changes'][number]['confidenceLevel'] = index < 2 ? 'FORMAL_CHANGE' : 'STABLE'
    const sensitivityDirection: RoleEvolutionRun['changes'][number]['sensitivityDirection'] = index < 2 ? 'UP' : 'STABLE'
    return {
      id: index + 1,
      skill: item.skill,
      changeType,
      confidenceLevel,
      absoluteChange: index === 0 ? 0.18 : index === 1 ? 0.12 : 0.03,
      differenceLowerBound: index === 0 ? 0.1 : 0.02,
      differenceUpperBound: index === 0 ? 0.26 : 0.18,
      sensitivityDirection,
      consecutiveAbsentWindows: 0,
      publishable: true,
      includedInDraft: true,
      points: [
        { period: '2025 下半年', documentCount: 48, companyCount: 8, demandRate: Math.max(0.1, item.demandRate - 0.12), lowerBound: Math.max(0.05, item.demandRate - 0.2), upperBound: item.demandRate - 0.04 },
        { period: '2026 上半年', documentCount: 62, companyCount: 10, demandRate: item.demandRate, lowerBound: Math.max(0.05, item.demandRate - 0.08), upperBound: Math.min(1, item.demandRate + 0.08) }
      ],
      evidence: marketCard(role.id).evidence.slice(0, 2).map((evidence, evidenceIndex) => ({
        id: (index + 1) * 10 + evidenceIndex,
        windowType: evidenceIndex ? 'CURRENT' as const : 'BASELINE' as const,
        documentId: evidence.documentId,
        title: role.name,
        company: evidence.company,
        source: evidence.source,
        sourceUrl: '',
        publishedAt: evidence.publishedAt,
        quote: evidence.quote,
        startOffset: evidence.startOffset,
        endOffset: evidence.endOffset,
        anchorValid: true,
        direction: sensitivityDirection
      }))
    }
  })
  return {
    id: 9000 + role.id,
    runCode: `LOCAL-EVOLUTION-${role.code}`,
    roleId: role.id,
    roleCode: role.code,
    roleName: role.name,
    policyVersion: '本地示例',
    policyHash: 'local',
    inputHash: 'local',
    status: 'PUBLISHED',
    conclusionLevel: 'FORMAL_CHANGES',
    sampleControl: '本地岗位示例资料',
    statisticsMethod: '双时间窗口对比',
    version: 1,
    draftNote: '',
    windows: [
      { id: 1, type: 'BASELINE', label: '2025 下半年', startDate: '2025-07-01', endDate: '2025-12-31', documentCount: 48, companyCount: 8, sourceCount: 2, sourceNames: ['公开岗位资料'], cityScope: '多城市', levelScope: role.level, sampleHash: 'local-2025' },
      { id: 2, type: 'CURRENT', label: '2026 上半年', startDate: '2026-01-01', endDate: '2026-06-30', documentCount: 62, companyCount: 10, sourceCount: 2, sourceNames: ['公开岗位资料'], cityScope: '多城市', levelScope: role.level, sampleHash: 'local-2026' }
    ],
    changes,
    createdAt: now,
    updatedAt: now,
    publishedAt: now
  }
}

export const quality: QualityReport = {
  snapshotId: 'demo-snapshot-2026-07',
  snapshotName: 'AI 应用研发岗位族演示快照',
  rawCount: 312,
  effectiveCount: 286,
  duplicateClusterCount: 9,
  publishable: true,
  policyVersion: 'v1.0.0',
  metrics: [
    ['SOURCE_COVERAGE', '来源覆盖率', 1, 1, 'PASSED'],
    ['COMPANY_INDEPENDENCE', '独立企业比例', 0.86, 0.5, 'PASSED'],
    ['NEAR_DUPLICATE_RATE', '近重复率', 0.083, 0.2, 'PASSED'],
    ['PUBLISH_TIME_COMPLETENESS', '发布时间完整率', 1, 0.8, 'PASSED'],
    ['FIELD_COMPLETENESS', '字段完整率', 0.94, 0.85, 'PASSED'],
    ['EVIDENCE_COVERAGE', '原文锚点有效率', 1, 1, 'PASSED'],
    ['INFLATION_RATE', 'JD 通胀率', 0.012, 0.1, 'PASSED']
  ].map(([code, name, value, threshold, status]) => ({ code: String(code), name: String(name), value: Number(value), threshold: Number(threshold), unit: '比例', status: String(status), explanation: '按分析策略 v1.0.0 自动计算' })),
  issues: [
    { type: 'NEAR_DUPLICATE', severity: 'MEDIUM', documentName: '北辰智算 · RAG 应用开发工程师', detail: '近重复簇包含 4 条文档，仅代表样本贡献一次市场证据', action: '保留原文并聚类降权' },
    { type: 'JD_INFLATION', severity: 'HIGH', documentName: '海川软件 · Java 开发工程师', detail: '检测到跨前端、后端、算法、运维等角色的不合理混合要求', action: '降低证据权重并复核' },
    { type: 'PROMPT_INJECTION', severity: 'HIGH', documentName: '凌云数据 · RAG 应用开发工程师', detail: '文本含有操纵模型或泄露系统提示的指令', action: '隔离指令，仅按不可信数据抽取' }
  ],
  generatedAt: updatedAt
}

const sampleAbilities = ['Java', 'Spring Boot', 'MySQL', 'Redis', 'Linux', 'Docker', 'RAG', '大语言模型']
export const sampleResume: ResumeProfile = {
  id: 1,
  name: '陈同学',
  education: '本科',
  major: '软件工程',
  graduationYear: 2027,
  weeklyHours: 12,
  abilities: sampleAbilities.map((skill, index) => ({ skill, level: index < 6 ? 2 : 1, levelName: index < 6 ? 'L2 实践' : 'L1 学习', quote: `项目中使用 ${skill} 完成相关模块`, sourceType: index < 6 ? 'PROJECT' : 'COURSE', confidence: 0.96 })),
  projects: ['校园二手交易平台，负责后端接口、数据库设计和 Redis 缓存', '企业知识库课程项目，学习 RAG 和大语言模型'],
  certificates: ['软考软件设计师'],
  status: 'CONFIRMED'
}

export const sampleMatch: MatchReport = {
  id: 2001,
  resumeId: 1,
  roleId: 1,
  roleName: 'RAG 应用开发工程师',
  graphRelease: '2026.07',
  hardGates: [
    { name: '学历', expected: '本科及以上', actual: '本科', status: 'PASSED', explanation: '硬门槛风险单独展示，不并入能力分' },
    { name: '经验', expected: '项目/实习可替代初级经验', actual: '有项目证据', status: 'PASSED', explanation: '学生模式使用项目替代工作年限证据' },
    { name: '地域', expected: '不限', actual: '岗位支持多城市', status: 'PASSED', explanation: '地域仅作为主动筛选条件' }
  ],
  readiness: 67.8,
  growthPotential: 83.4,
  applicationState: '接近可申请',
  dimensions: [
    ['必备技能覆盖与证据等级', 0.45, 61], ['职责/项目相关度', 0.25, 75], ['加分技能覆盖', 0.1, 48], ['熟练度与近期使用', 0.1, 62], ['行业场景相关度', 0.05, 72], ['证书等补充项', 0.05, 85]
  ].map(([name, weight, rawScore]) => ({ name: String(name), weight: Number(weight), rawScore: Number(rawScore), weightedScore: Number(weight) * Number(rawScore), explanation: '确定性规则计算，可追溯到简历和岗位证据' })),
  skillDetails: roles[0].requiredSkills.map(item => {
    const ability = sampleResume.abilities.find(value => value.skill === item.skill)
    return { skill: item.skill, matchType: ability ? 'EXACT_OR_ALIAS' : 'UNMATCHED', evidenceLevel: ability?.level ?? 0, requiredLevel: 2, score: ability ? 75 : 0, resumeEvidence: ability?.quote ?? '无简历证据', gap: ability?.level === 2 ? '已满足证据等级' : ability ? '技能已出现，但证据等级不足' : '缺少岗位要求技能', remedy: '在目标项目中完成任务并提交测试结果' }
  }),
  adjacentRoles: [
    { roleId: 2, roleName: 'Java 开发工程师', readiness: 72, keyGaps: 2, transferDistance: 0.48, confidence: 0.88 },
    { roleId: 3, roleName: 'AI Agent 开发工程师', readiness: 54, keyGaps: 4, transferDistance: 0.61, confidence: 0.84 }
  ],
  createdAt: updatedAt
}

const offlineMatches = new Map<number, MatchReport>([[sampleMatch.id, sampleMatch]])

export function matchForRole(resumeId: number, jobRoleId: number): MatchReport {
  const role = roles.find(item => item.id === jobRoleId) ?? roles[0]
  const skillDetails = role.requiredSkills.map(requirement => {
    const ability = sampleResume.abilities.find(item => item.skill === requirement.skill)
    const evidenceLevel = ability?.level ?? 0
    return {
      skill: requirement.skill,
      matchType: ability ? 'EXACT_OR_ALIAS' : 'UNMATCHED',
      evidenceLevel,
      requiredLevel: 2,
      score: ability ? 75 : 0,
      resumeEvidence: ability?.quote ?? '无简历证据',
      gap: ability?.level === 2 ? '已满足证据等级' : ability ? '技能已出现，但证据等级不足' : '缺少岗位要求技能',
      remedy: `完成一个使用 ${requirement.skill} 的可运行任务，并提交代码、说明和测试结果`
    }
  })
  const report = { ...sampleMatch, id: jobRoleId === 1 ? sampleMatch.id : sampleMatch.id + jobRoleId, resumeId, roleId: role.id, roleName: role.name, skillDetails }
  offlineMatches.set(report.id, report)
  return report
}

export function matchResult(matchRunId: number): MatchReport {
  return offlineMatches.get(matchRunId) ?? { ...sampleMatch, id: matchRunId }
}

export const samplePlan: LearningPlan = {
  id: 3001,
  matchRunId: 2001,
  targetRole: 'RAG 应用开发工程师',
  weeklyHours: 12,
  estimatedWeeks: 6,
  graphRelease: '2026.07',
  items: [
    { order: 1, stage: '建立基础', skill: 'Python', reason: '目标岗位的基础编程语言', prerequisites: [], resources: ['大模型应用工程'], practicalTask: '完成文档处理和模型 API 调用脚本', acceptanceCriteria: '提交代码、README 和测试结果', estimatedHours: 14, weekStart: 1, weekEnd: 2, evidenceDeliverable: '代码仓库、README 与测试记录' },
    { order: 2, stage: '形成能力', skill: 'Embedding', reason: 'RAG 检索链路前置能力', prerequisites: ['Python'], resources: ['向量检索与知识库问答'], practicalTask: '比较两种切分策略并输出 Recall@K', acceptanceCriteria: 'Recall@5 不低于 80%', estimatedHours: 20, weekStart: 3, weekEnd: 4, evidenceDeliverable: '实验报告、评测数据与代码' },
    { order: 3, stage: '形成证据', skill: '向量数据库', reason: '目标岗位必备的数据基础设施', prerequisites: ['Embedding'], resources: ['向量检索与知识库问答'], practicalTask: '实现文档入库、过滤与相似检索', acceptanceCriteria: '提交接口测试与性能记录', estimatedHours: 20, weekStart: 5, weekEnd: 6, evidenceDeliverable: '项目链接、接口文档与性能记录' }
  ]
}

export function planForMatch(matchRunId: number, weeklyHours: number): LearningPlan {
  const sourceMatch = offlineMatches.get(matchRunId) ?? sampleMatch
  const gaps = sourceMatch.skillDetails.filter(item => item.evidenceLevel < item.requiredLevel)
  const itemHours = Math.max(6, weeklyHours)
  const estimatedWeeks = Math.max(4, Math.min(6, Math.ceil((gaps.length * itemHours) / Math.max(1, weeklyHours))))
  return {
    id: samplePlan.id,
    matchRunId,
    targetRole: sourceMatch.roleName,
    weeklyHours,
    estimatedWeeks,
    graphRelease: samplePlan.graphRelease,
    items: gaps.map((gap, index) => {
      const weekStart = Math.max(1, Math.floor(index * estimatedWeeks / Math.max(1, gaps.length)) + 1)
      const weekEnd = Math.max(weekStart, Math.ceil((index + 1) * estimatedWeeks / Math.max(1, gaps.length)))
      return {
        order: index + 1,
        stage: index < 2 ? '建立基础' : '形成证据',
        skill: gap.skill,
        reason: gap.gap,
        prerequisites: index ? [gaps[index - 1].skill] : [],
        resources: ['自行选择具有公开许可的学习资料'],
        practicalTask: gap.remedy,
        acceptanceCriteria: '提交可运行代码、README 和至少一项测试结果',
        estimatedHours: itemHours,
        weekStart,
        weekEnd,
        evidenceDeliverable: '项目链接、实现说明、README 与测试结果'
      }
    })
  }
}

export const proposals: ChangeProposal[] = [
  { id: 99, title: 'Java 岗位 2026.07 演化', type: 'ROLE_EVOLUTION', status: 'APPROVED', creator: '研究员-甲', reviewer: '审核员-丙', reason: '等长窗口需求率与证据支持', changes: ['增强：Docker 与 Kubernetes', '新增：RAG 与大语言模型集成', '弱化：JSP/Servlet', '修改：Spring Boot 升级为微服务治理'], createdAt: updatedAt, reviewedAt: updatedAt },
  { id: 98, title: 'RAG 应用开发工程师岗位定义', type: 'EMERGING_ROLE', status: 'APPROVED', creator: '研究员-甲', reviewer: '审核员-乙', reason: '职责与技能组合形成稳定新结构', changes: ['新增：RAG 应用开发工程师岗位', '新增：RAG、Embedding、向量数据库能力关系', '新增：企业知识库与研发助手场景'], createdAt: updatedAt, reviewedAt: updatedAt }
]

export const releases: GraphRelease[] = [
  { id: 10, version: '2026.07', status: 'PUBLISHED', snapshotId: 'demo-snapshot-2026-07', policyVersion: 'v1.0.0', publisher: '管理员-甲', changes: ['新增：RAG 应用开发工程师岗位', '增强：Docker 与 Kubernetes', '新增：RAG 与大语言模型集成', '弱化：JSP/Servlet'], publishedAt: updatedAt },
  { id: 9, version: '2025.12', status: 'ARCHIVED', snapshotId: 'snapshot-2025-12', policyVersion: 'v0.9.0', publisher: '管理员-甲', changes: ['基线：Java 岗位能力图谱'], publishedAt: '2025-12-10T16:00:00+08:00' }
]

export const graph = (roleId: number, viewType = 'PANORAMA'): GraphView => {
  const role = roles.find(item => item.id === roleId) ?? roles[0]
  const nodes: GraphView['nodes'] = [{ id: `role-${role.id}`, label: role.name, type: 'JOB_ROLE', group: role.family, evidenceCount: role.requiredSkills.length, status: 'PUBLISHED' }]
  const edges: GraphView['edges'] = []
  ;[...role.requiredSkills, ...role.bonusSkills].forEach((item, index) => {
    const id = `skill-${index}`
    nodes.push({ id, label: item.skill, type: 'SKILL', group: item.category, evidenceCount: item.evidenceCount, status: 'PUBLISHED' })
    edges.push({ id: `edge-${index}`, source: `role-${role.id}`, target: id, relation: item.requirementType === 'REQUIRED' ? 'REQUIRES' : 'PREFERS', weight: item.demandRate, status: 'PUBLISHED' })
  })
  role.responsibilities.forEach((item, index) => {
    const id = `resp-${index}`
    nodes.push({ id, label: item, type: 'RESPONSIBILITY', group: '职责', evidenceCount: 5, status: 'PUBLISHED' })
    edges.push({ id: `resp-edge-${index}`, source: `role-${role.id}`, target: id, relation: 'PERFORMS', weight: 1, status: 'PUBLISHED' })
  })
  return { releaseVersion: role.releaseVersion, viewType, nodes, edges }
}

export const extractOffline = (text: string): ExtractionResult => {
  const aliases: Record<string, string[]> = { 'Java': ['java'], 'Spring Boot': ['spring boot', 'springboot'], 'MySQL': ['mysql'], 'Redis': ['redis'], 'RAG': ['rag', '检索增强生成'], 'Embedding': ['embedding', '向量化'], '向量数据库': ['向量数据库', 'milvus', 'chroma'], '大语言模型': ['大语言模型', '大模型', 'llm'], 'Prompt Engineering': ['prompt engineering', '提示词工程'], 'LangChain': ['langchain'], '模型评测': ['模型评测', '幻觉率', 'recall@k'] }
  const lower = text.toLowerCase()
  const documentId = Array.from(text).reduce((hash, character) => (hash * 31 + character.charCodeAt(0)) >>> 0, 17) || 1
  const facts = Object.entries(aliases).flatMap(([canonical, terms]) => {
    const term = terms.find(value => lower.includes(value))
    if (!term) return []
    const startOffset = lower.indexOf(term)
    const rawValue = text.slice(startOffset, startOffset + term.length)
    return [{ type: 'SKILL', rawValue, normalizedValue: canonical, requirementType: text.slice(Math.max(0, startOffset - 12), startOffset).match(/优先|加分/) ? 'BONUS' : 'REQUIRED', documentId, startOffset, endOffset: startOffset + term.length, evidenceQuote: rawValue, confidence: 0.96, gateStatus: 'EVIDENCE_VALID' }]
  })
  const responsibilities = text.split(/[。；\n]/).map(value => value.trim()).filter(value => /负责|建设|设计|优化|开发/.test(value)).slice(0, 6)
  const scenarioText = text.match(/(?:行业场景|应用场景|业务场景)[:：]\s*([^\n。；]+)/)?.[1] ?? ''
  const scenarios = scenarioText.split(/[、，,/]/).map(value => value.trim()).filter(Boolean)
  const evidence = [
    ...facts.map(fact => ({ type: 'SKILL', normalizedValue: fact.normalizedValue, documentId, startOffset: fact.startOffset, endOffset: fact.endOffset, quote: fact.evidenceQuote, confidence: fact.confidence, extractionMethod: 'RULE', gateStatus: fact.gateStatus })),
    ...responsibilities.map(value => { const startOffset = text.indexOf(value); return { type: 'RESPONSIBILITY', normalizedValue: value, documentId, startOffset, endOffset: startOffset + value.length, quote: value, confidence: 0.98, extractionMethod: 'RULE', gateStatus: 'EVIDENCE_VALID' } }),
    ...scenarios.map(value => { const startOffset = text.indexOf(value); return { type: 'SCENARIO', normalizedValue: value, documentId, startOffset, endOffset: startOffset + value.length, quote: value, confidence: 0.98, extractionMethod: 'RULE', gateStatus: 'EVIDENCE_VALID' } })
  ].filter(item => item.startOffset >= 0)
  const injection = /忽略前述|系统提示|ignore previous/i.test(text)
  const conclusionCount = evidence.length
  return {
    title: text.match(/岗位名称[:：]\s*([^\n]+)/)?.[1] ?? '待确认岗位',
    salary: text.match(/\d+k\s*[-~至]\s*\d+k/i)?.[0] ?? '未明确',
    level: text.match(/实习|初级|中级|高级|专家/)?.[0] ?? '未明确',
    facts, responsibilities, scenarios, evidence,
    warnings: injection ? ['检测到提示注入式指令，已拦截模型调用并仅执行确定性规则抽取。'] : conclusionCount ? [] : ['证据不足：没有可校验字段。'],
    policyVersion: 'v1.0.0', modelMode: 'OFFLINE_RULE_FALLBACK',
    conclusionStatus: conclusionCount ? '仅可发布证据化字段' : '证据不足',
    metrics: { evidenceCoverageRate: conclusionCount ? 1 : 1, citationConsistencyRate: 1, unsupportedRefusalRate: 1, falseAcceptanceRate: 0, promptInjectionBlockRate: 1, conclusionCount, evidenceBackedCount: conclusionCount, rejectedCount: conclusionCount ? 0 : 1 }
  }
}
