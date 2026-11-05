import { createHash } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(scriptDirectory, '../..')
const sourcePath = resolve(process.argv[2] ?? resolve(scriptDirectory, '../../../data/JD结构化试验/jd_structured.json'))
const outputDirectory = resolve(process.argv[3] ?? resolve(scriptDirectory, '../public/data'))

const sourceBuffer = await readFile(sourcePath)
const generatorBuffer = await readFile(fileURLToPath(import.meta.url))
const source = JSON.parse(sourceBuffer.toString('utf8'))
const evaluationReport = JSON.parse(await readFile(resolve(projectRoot, 'test-data/evaluation/evaluation-report.json'), 'utf8'))
if (source.schemaVersion !== '1.0.0' || !Array.isArray(source.documents) || source.documents.length === 0) {
  throw new Error('输入文件必须是包含 documents 的 schemaVersion 1.0.0 结构化 JD')
}

const sourceHash = createHash('sha256').update(sourceBuffer).digest('hex')
const snapshotHash = createHash('sha256').update(sourceBuffer).update(generatorBuffer).digest('hex')
const generatedAt = source.generatedAt ?? new Date().toISOString()
const snapshotCode = `jd-frozen-${generatedAt.slice(0, 10)}-${snapshotHash.slice(0, 8)}`
const documents = source.documents.filter(document => document.processingStatus === 'ELIGIBLE')
const companies = new Set(documents.map(document => document.company).filter(Boolean))
const sources = new Set(documents.map(document => document.source?.name).filter(Boolean))

const roleDefinitions = {
  AI_AGENT: {
    id: 1, code: 'AI_AGENT_ENGINEER', name: 'AI Agent 开发工程师', family: 'AI 应用研发', level: '试验画像',
    definition: '负责智能体任务规划、工具调用、知识检索、流程编排和效果验证。',
    scenarios: ['企业知识库', '智能客服', '自动化办公']
  },
  JAVA: {
    id: 2, code: 'JAVA_ENGINEER', name: 'Java 开发工程师', family: '后端与云原生', level: '试验画像',
    definition: '负责 Java 后端服务、数据访问、分布式系统和工程化交付。',
    scenarios: ['企业信息化', '互联网平台', '后端服务']
  }
}

const roleKey = document => String(document.jobTitle).toLowerCase().includes('java') ? 'JAVA' : 'AI_AGENT'
const groupedDocuments = Object.fromEntries(Object.keys(roleDefinitions).map(key => [key, documents.filter(document => roleKey(document) === key)]))

function unique(values) {
  return [...new Set(values.filter(Boolean))]
}

function publicSourceUrl(value) {
  if (!value) return ''
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:') return ''
    url.search = ''
    url.hash = ''
    return url.toString()
  } catch {
    return ''
  }
}

function requirementType(fact) {
  return fact.requirementType === 'BONUS' ? 'BONUS' : 'REQUIRED'
}

function factReviewDecision(fact) {
  return fact.review?.decision ?? (fact.ontologyStatus === 'STANDARD' ? 'APPROVED_EXISTING' : 'PENDING_REVIEW')
}

function effectiveOntologyStatus(fact) {
  return factReviewDecision(fact) === 'APPROVED_STANDARD' ? 'STANDARD' : fact.ontologyStatus
}

function isPendingCandidateFact(fact) {
  return fact.ontologyStatus === 'CANDIDATE' && ['PENDING_REVIEW', 'MIXED'].includes(factReviewDecision(fact))
}

function roleMetrics(roleDocuments) {
  const skills = new Map()
  for (const document of roleDocuments) {
    for (const fact of document.facts?.skills ?? []) {
      if (effectiveOntologyStatus(fact) !== 'STANDARD' || fact.requirementType === 'NEGATED') continue
      const current = skills.get(fact.canonicalSkill) ?? { skill: fact.canonicalSkill, documents: new Set(), required: 0, bonus: 0, evidence: [] }
      current.documents.add(document.id)
      if (requirementType(fact) === 'BONUS') current.bonus += 1
      else current.required += 1
      current.evidence.push({ document, fact })
      skills.set(fact.canonicalSkill, current)
    }
  }
  return [...skills.values()].sort((left, right) => right.documents.size - left.documents.size || left.skill.localeCompare(right.skill, 'zh-CN'))
}

function skillRequirement(metric, documentCount) {
  const type = metric.required >= metric.bonus ? 'REQUIRED' : 'BONUS'
  return {
    skill: metric.skill,
    category: '岗位能力',
    requirementType: type,
    demandRate: Number((metric.documents.size / Math.max(1, documentCount)).toFixed(3)),
    evidenceCount: metric.documents.size,
    trend: '当前快照'
  }
}

function evidenceItem(document, fact) {
  return {
    documentId: document.id,
    source: document.source?.name ?? '授权来源',
    company: document.company,
    publishedAt: document.publishedAt ?? document.collectedAt,
    quote: fact.evidenceQuote,
    startOffset: fact.startOffset,
    endOffset: fact.endOffset,
    timeWeight: 1,
    gateStatus: fact.offsetVerified ? 'EVIDENCE_VALID' : 'EVIDENCE_UNVERIFIED'
  }
}

const roles = []
const marketCards = {}
const marketTimelines = {}
const realRoleSnapshots = []
for (const [key, definition] of Object.entries(roleDefinitions)) {
  const roleDocuments = groupedDocuments[key]
  const metrics = roleMetrics(roleDocuments)
  const requirements = metrics.map(metric => skillRequirement(metric, roleDocuments.length))
  const requiredSkills = requirements.filter(item => item.requirementType === 'REQUIRED').slice(0, 12)
  const bonusSkills = requirements.filter(item => item.requirementType === 'BONUS').slice(0, 8)
  const responsibilities = unique(roleDocuments.flatMap(document => (document.facts?.responsibilities ?? []).map(item => item.normalizedValue))).slice(0, 8)
  const salaryValues = roleDocuments.map(document => document.salary?.raw ?? document.salary).filter(value => typeof value === 'string' && value.trim())
  const role = {
    ...definition,
    emerging: key === 'AI_AGENT',
    salaryRange: salaryValues[0] ?? '样本未形成统一口径',
    responsibilities,
    requiredSkills,
    bonusSkills,
    scenarios: definition.scenarios,
    releaseVersion: snapshotCode,
    updatedAt: generatedAt
  }
  roles.push(role)
  const evidence = metrics.flatMap(metric => metric.evidence.slice(0, 1).map(({ document, fact }) => evidenceItem(document, fact))).slice(0, 12)
  marketCards[String(role.id)] = {
    role,
    sampleSize: roleDocuments.length,
    independentCompanies: new Set(roleDocuments.map(document => document.company)).size,
    demandHeat: 0,
    salaryQuartiles: [],
    cityDistribution: Object.fromEntries(unique(roleDocuments.map(document => document.city)).map(city => [city, Number((roleDocuments.filter(document => document.city === city).length / roleDocuments.length).toFixed(3))])),
    levelDistribution: {},
    commonSkillCombinations: [requiredSkills.slice(0, 3).map(item => item.skill).join(' + ')].filter(Boolean),
    evidence,
    confidence: roleDocuments.length >= 5 ? 0.6 : 0.4,
    sampleScope: `${roleDocuments.length} 条冻结试验样本；不代表市场趋势`,
    metadata: { dataMode: 'FROZEN_CACHE', graphRelease: snapshotCode, sampleSize: roleDocuments.length, independentCompanies: new Set(roleDocuments.map(document => document.company)).size, confidence: roleDocuments.length >= 5 ? 0.6 : 0.4, evidenceAvailable: evidence.length > 0 }
  }
  marketTimelines[String(role.id)] = { roleId: role.id, periods: [] }
  const snapshotSkills = metrics.slice(0, 15).map(metric => ({
    skill: metric.skill,
    rate: Number((metric.documents.size / Math.max(1, roleDocuments.length)).toFixed(3)),
    documentCount: metric.documents.size,
    companyCount: new Set(metric.evidence.map(item => item.document.company)).size,
    evidence: metric.evidence.slice(0, 5).map(({ document, fact }) => ({ documentId: document.id, title: document.jobTitle, company: document.company, quote: fact.evidenceQuote, startOffset: fact.startOffset, endOffset: fact.endOffset, sourceUrl: publicSourceUrl(document.source?.originalUrl) }))
  }))
  realRoleSnapshots.push({
    roleCode: role.code, roleName: role.name, sampleSize: roleDocuments.length,
    independentCompanies: new Set(roleDocuments.map(document => document.company)).size,
    confidenceLabel: '试验快照',
    responsibilities: responsibilities.map(responsibility => ({ responsibility, documentCount: 1, companyCount: 1, evidence: [] })),
    requiredSkills: snapshotSkills.filter(metric => requiredSkills.some(item => item.skill === metric.skill)),
    bonusSkills: snapshotSkills.filter(metric => bonusSkills.some(item => item.skill === metric.skill)),
    mentionedSkills: [], opposingEvidence: []
  })
}

const baselineCatalog = [
  [3, 'RAG_APP_ENGINEER', 'RAG 应用开发工程师', 'AI 应用研发', '负责检索增强生成链路、证据引用和效果评测。', ['Python', 'RAG', 'Embedding', '向量数据库', '大语言模型'], ['构建企业知识库问答', '设计检索与重排链路', '建立引用评测']],
  [4, 'AI_APP_BACKEND_ENGINEER', 'AI 应用后端工程师', 'AI 应用研发', '负责把大模型与智能体能力接入稳定的企业后端服务。', ['Java', 'Spring Boot', '大语言模型', 'RAG', 'Docker'], ['设计 AI 应用接口', '接入模型与检索服务', '保障服务稳定性']],
  [5, 'LLMOPS_ENGINEER', 'LLMOps / 模型服务工程师', 'AI 平台工程', '负责模型服务部署、监控和稳定性治理。', ['Python', 'Linux', 'Docker', 'Kubernetes', '大语言模型'], ['部署模型服务', '建设监控告警', '管理模型版本']],
  [6, 'LLM_EVALUATION_ENGINEER', '大模型评测工程师', 'AI 质量与安全', '负责评测集、质量指标和自动化评测。', ['Python', '大语言模型', '模型评测', 'RAG'], ['建设评测数据集', '定义质量指标', '分析错误案例']]
]
const baselineRoles = baselineCatalog.map(([id, code, name, family, definition, coreSkills, responsibilities]) => ({
  id, code, name, family, definition, suitableBackground: '有计算机基础，可使用能力基线进行自测',
  status: 'BASELINE', coreSkills, responsibilities
}))
const baselineJobRoles = baselineCatalog.map(([id, code, name, family, definition, coreSkills, responsibilities]) => ({
  id, code, name, family, level: '能力基线', emerging: false, definition, salaryRange: '无真实样本统计', responsibilities,
  requiredSkills: coreSkills.map(skill => ({ skill, category: '岗位能力基线', requirementType: 'REQUIRED', demandRate: 0, evidenceCount: 0, trend: '能力基线' })),
  bonusSkills: [], scenarios: [], releaseVersion: snapshotCode, updatedAt: generatedAt
}))
roles.push(...baselineJobRoles)

const allStandardSkills = unique(documents.flatMap(document => (document.facts?.skills ?? []).filter(fact => effectiveOntologyStatus(fact) === 'STANDARD').map(fact => fact.canonicalSkill)))
const candidateSkills = (source.candidateSkills ?? []).map(candidate => {
  const facts = documents.flatMap(document => (document.facts?.skills ?? [])
    .filter(fact => fact.canonicalSkill === candidate.canonicalSkill)
    .map(fact => ({ document, fact })))
  return {
    skill: candidate.canonicalSkill, suggestedCategory: candidate.suggestedCategory,
    factCount: facts.length, companyCount: new Set(facts.map(item => item.document.company)).size,
    reviewDecision: candidate.review?.decision ?? 'PENDING_REVIEW', correctedValue: candidate.review?.correctedValue ?? undefined,
    decisionSource: 'FROZEN_SOURCE', requirementTypes: unique(facts.map(item => item.fact.requirementType)),
    recommendedDecision: candidate.companyCount >= 3 ? 'APPROVED_STANDARD' : 'OBSERVING',
    recommendationReasons: [candidate.companyCount >= 3 ? '至少三家独立企业出现' : '样本证据不足，建议继续观察'],
    evidenceSamples: facts.slice(0, 5).map(({ document, fact }) => ({ recordId: document.id, title: document.jobTitle, company: document.company, quote: fact.evidenceQuote, requirementType: fact.requirementType, confidence: fact.confidence, offsetVerified: fact.offsetVerified }))
  }
})

const dynamicRecordItems = documents.map(document => {
  const candidateCount = (document.facts?.skills ?? []).filter(isPendingCandidateFact).length
  const promoted = candidateCount === 0
  return {
    id: document.id, importId: 1, sourceRecordKey: document.sourceRecordKey, title: document.jobTitle, company: document.company, city: document.city ?? '',
    contentHash: document.contentHash, dataMode: 'FROZEN_CACHE', status: promoted ? 'PROMOTED' : document.processingStatus, qualityScore: 1,
    skillCount: document.facts?.skills?.length ?? 0, candidateSkillCount: candidateCount, issueCount: 0, reviewDecision: promoted ? 'FROZEN_PUBLISHED' : 'FROZEN',
    riskLevel: candidateCount ? 'MEDIUM' : 'LOW', reviewRoute: candidateCount ? 'WAITING_SKILL' : 'FROZEN_APPROVED',
    reviewReasons: candidateCount ? ['冻结来源中仍有候选技能，网页仅供查看'] : [], reviewSource: 'FROZEN_FILE',
    collectedAt: document.collectedAt
  }
})

const dynamicRecordDetails = Object.fromEntries(documents.map(document => {
  let factId = document.id * 1000
  const facts = [
    ...(document.facts?.skills ?? []).map(fact => ({ id: ++factId, factType: 'SKILL', ontologyStatus: effectiveOntologyStatus(fact), canonicalValue: fact.canonicalSkill, suggestedCategory: fact.suggestedCategory ?? undefined, rawValue: fact.rawValue, requirementType: fact.requirementType, startOffset: fact.startOffset, endOffset: fact.endOffset, evidenceQuote: fact.evidenceQuote, confidence: fact.confidence, offsetVerified: fact.offsetVerified, reviewDecision: factReviewDecision(fact), correctedValue: fact.review?.correctedValue ?? undefined, reviewNote: fact.review?.note ?? undefined })),
    ...(document.facts?.responsibilities ?? []).map(fact => ({ id: ++factId, factType: 'RESPONSIBILITY', ontologyStatus: 'STANDARD', canonicalValue: fact.normalizedValue, rawValue: fact.rawValue, startOffset: fact.startOffset, endOffset: fact.endOffset, evidenceQuote: fact.evidenceQuote, confidence: fact.confidence, offsetVerified: fact.offsetVerified, reviewDecision: 'APPROVED_EXISTING' }))
  ]
  return [String(document.id), {
    summary: dynamicRecordItems.find(item => item.id === document.id),
    sourceName: document.source?.name ?? '', licenseStatus: document.source?.licenseStatus ?? '',
    sourceUrl: publicSourceUrl(document.source?.originalUrl), canonicalUrl: publicSourceUrl(document.source?.canonicalUrl),
    salary: document.salary?.raw ?? document.salary ?? '', experienceRequirement: document.experienceRequirement ?? '',
    educationRequirement: document.educationRequirement ?? '', publishedAt: document.publishedAt ?? undefined,
    rawText: document.rawText, facts,
    issues: document.publishedAt ? [] : [{ id: document.id, type: 'PUBLISHED_AT_MISSING', severity: 'LOW', detail: '未披露发布时间，仅可用于当前冻结快照。', blocking: false }],
    reviews: []
  }]
}))

const missingPublishedAt = documents.filter(document => !document.publishedAt).length
const quality = {
  snapshotId: snapshotCode, snapshotName: '10 条 JD 本地冻结试验快照', rawCount: source.documents.length, effectiveCount: documents.length, duplicateClusterCount: 0, publishable: documents.length > 0, policyVersion: source.processingPolicy?.version ?? 'jd-pilot-v1.1',
  metrics: [
    { code: 'SOURCE_COVERAGE', name: '来源许可字段完整率', value: documents.filter(document => document.source?.licenseStatus).length / documents.length, threshold: 1, unit: '比例', status: documents.every(document => document.source?.licenseStatus) ? 'PASSED' : 'FAILED', explanation: '冻结文件必须保留来源及许可状态' },
    { code: 'EVIDENCE_COVERAGE', name: '原文锚点有效率', value: source.qualitySummary?.allOffsetsVerified ? 1 : 0, threshold: 1, unit: '比例', status: source.qualitySummary?.allOffsetsVerified ? 'PASSED' : 'FAILED', explanation: '技能和职责必须保留原文位置' },
    { code: 'PUBLISH_TIME_COMPLETENESS', name: '发布时间完整率', value: (documents.length - missingPublishedAt) / documents.length, threshold: 0.8, unit: '比例', status: missingPublishedAt === 0 ? 'PASSED' : 'LIMITED', explanation: '发布时间不完整时禁止输出趋势结论' }
  ],
  issues: (source.qualityIssues ?? []).slice(0, 20).map(issue => ({ type: issue.issueCode, severity: issue.severity, documentName: `JD ${issue.affectedDocumentIds?.join(', ')}`, detail: issue.detail, action: issue.recommendedAction })),
  generatedAt
}

const reviewQueueSummary = {
  importId: 1, totalRecords: documents.length, autoApprovedCount: 0, sampledCount: 0, exceptionCount: 0,
  waitingSkillCount: dynamicRecordItems.filter(item => item.reviewRoute === 'WAITING_SKILL').length, humanApprovedCount: 0, blockedCount: 0,
  pendingCandidateSkillCount: candidateSkills.filter(item => item.reviewDecision === 'PENDING_REVIEW').length,
  estimatedRemainingActions: candidateSkills.filter(item => item.reviewDecision === 'PENDING_REVIEW').length
}

const dashboard = {
  sourceCount: sources.size, rawDocumentCount: documents.length, independentCompanyCount: companies.size, skillCount: allStandardSkills.length,
  publishedRoleCount: Object.keys(roleDefinitions).length, pendingReviewCount: reviewQueueSummary.pendingCandidateSkillCount, currentRelease: snapshotCode,
  operatingMode: 'STATIC_JSON_SNAPSHOT',
  pipeline: [
    { name: '授权数据采集', status: 'FROZEN', count: documents.length, detail: '本地脚本生成，不在网站服务器写入' },
    { name: '质量与去重', status: 'FROZEN', count: documents.length, detail: '保留来源、哈希和原文证据' },
    { name: '候选审核', status: reviewQueueSummary.pendingCandidateSkillCount ? 'WAITING_LOCAL_REVIEW' : 'COMPLETED', count: reviewQueueSummary.pendingCandidateSkillCount, detail: '审核结果需在本地重新生成快照' },
    { name: '静态发布', status: 'COMPLETED', count: 1, detail: snapshotCode }
  ],
  trends: []
}

const publicRoles = roles.filter(role => role.id <= 2).map(role => {
  const card = marketCards[String(role.id)]
  return {
    id: role.id, code: role.code, name: role.name, family: role.family, definition: role.definition,
    suitableBackground: role.code === 'JAVA_ENGINEER' ? '有 Java、数据库或后端项目基础的学生' : '有编程基础，希望进入大模型应用研发的学生',
    status: 'PUBLISHED',
    coreSkills: [...role.requiredSkills, ...role.bonusSkills].slice(0, 5).map(item => item.skill),
    responsibilities: role.responsibilities.slice(0, 3), sampleCount: card.sampleSize,
    companyCount: card.independentCompanies, updatedAt: role.updatedAt
  }
})
const buildingRoles = [
  [7, 'LLM_APP_ENGINEER', '大模型应用开发工程师', 'AI 应用研发', '面向业务场景开发大模型应用。'],
  [8, 'AI_PLATFORM_ENGINEER', 'AI 平台开发工程师', 'AI 平台工程', '建设模型接入、应用编排和运营治理平台。'],
  [9, 'AI_DATA_ENGINEER', 'AI 数据工程师', '数据与知识工程', '建设 AI 应用所需的数据处理和质量管道。'],
  [10, 'KNOWLEDGE_ENGINEER', '知识工程师', '数据与知识工程', '负责知识建模、治理和知识应用。'],
  [11, 'VECTOR_RETRIEVAL_ENGINEER', '向量检索工程师', '数据与知识工程', '负责向量索引、召回和检索性能优化。'],
  [12, 'AI_SECURITY_TEST_ENGINEER', 'AI 安全测试工程师', 'AI 质量与安全', '负责提示注入、数据泄露和模型应用安全测试。']
].map(([id, code, name, family, definition]) => ({
  id, code, name, family, definition, suitableBackground: '有计算机基础，希望了解该方向的学生',
  status: 'BUILDING', coreSkills: [], responsibilities: []
}))
const publicOverview = {
  updatedAt: generatedAt, dataScope: '12 岗分层目录；仅真实证据画像计入样本', sampleCount: documents.length, companyCount: companies.size,
  roles: [...publicRoles, ...baselineRoles, ...buildingRoles],
  topSkills: unique([...publicRoles, ...baselineRoles].flatMap(role => role.coreSkills)).slice(0, 8),
  disclaimer: '当前样本用于岗位能力观察，不表示整体市场趋势'
}

const graphNodeId = (prefix, value) => `${prefix}-${String(value).toLowerCase().replace(/[^a-z0-9\p{Script=Han}]+/gu, '-')}`
const publicGraphOverview = (() => {
  const nodes = new Map()
  const edges = []
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
  return { releaseVersion: snapshotCode, viewType: 'PUBLIC_PANORAMA', nodes: [...nodes.values()], edges, metadata: { dataMode: 'FROZEN_CACHE', graphRelease: snapshotCode, sampleSize: documents.length, independentCompanies: companies.size, evidenceAvailable: true } }
})()

const sourceLedger = [...sources].sort((left, right) => left.localeCompare(right, 'zh-CN')).map((sourceName, index) => {
  const sourceDocuments = documents.filter(document => document.source?.name === sourceName)
  const timeScope = sourceDocuments.every(document => (document.timeScope ?? (document.publishedAt ? 'CURRENT' : 'UNKNOWN_TIME')) === 'CURRENT')
    ? 'CURRENT'
    : sourceDocuments.every(document => (document.timeScope ?? (document.publishedAt ? 'CURRENT' : 'UNKNOWN_TIME')) === 'HISTORICAL')
      ? 'HISTORICAL'
      : 'UNKNOWN_TIME'
  return {
    sourceId: sourceDocuments[0]?.source?.sourceId ?? `legacy-source-${index + 1}`,
    sourceName,
    sourceTier: sourceDocuments[0]?.source?.sourceTier ?? 'TIER_3',
    timeScope,
    adapter: sourceDocuments[0]?.source?.adapter ?? 'MANUAL_BATCH',
    batchCount: 1,
    collectedCount: sourceDocuments.length,
    duplicateCount: 0,
    acceptedCount: sourceDocuments.length,
    exceptionCount: sourceDocuments.filter(document => !document.publishedAt).length,
    updatedAt: generatedAt
  }
})

const evaluationSummary = {
  metrics: evaluationReport.metrics.map(metric => ({
    code: metric.code,
    name: metric.name,
    value: metric.accuracy,
    target: evaluationReport.targetAccuracy,
    sampleCount: metric.sampleCount,
    targetSamples: metric.targetSamples,
    precision: metric.precision,
    recall: metric.recall,
    f1: metric.f1,
    status: metric.passed ? 'VERIFIED_PASS' : 'VERIFIED_FAIL',
    color: ({ JD_EXTRACTION: '#16835f', RESUME_EXTRACTION: '#2d718f', JOB_MATCH: '#7f6aa1' })[metric.code] ?? '#2d718f',
    note: ({ JD_EXTRACTION: '岗位、职责、技能与要求类型', RESUME_EXTRACTION: '教育、项目、技能与证据片段', JOB_MATCH: '岗位对、申请状态与差距项' })[metric.code] ?? '正式冻结测试集'
  })),
  datasets: evaluationReport.metrics.map(metric => ({
    name: ({ JD_EXTRACTION: 'JD-GOLD-2026.08', RESUME_EXTRACTION: 'RESUME-GOLD-2026.08', JOB_MATCH: 'MATCH-GOLD-2026.08' })[metric.code],
    type: ({ JD_EXTRACTION: '岗位抽取黄金集', RESUME_EXTRACTION: '简历解析黄金集', JOB_MATCH: '人岗匹配验证集' })[metric.code],
    sampleCount: metric.sampleCount,
    targetSamples: metric.targetSamples,
    version: 'v1.0',
    status: metric.goldSha256 ? 'FROZEN' : 'AWAITING_ANNOTATION'
  })),
  errors: evaluationReport.metrics.filter(metric => metric.errors?.length).map(metric => ({
    type: metric.name,
    count: metric.errors.length,
    share: `${Math.round(metric.errors.length * 100 / metric.sampleCount)}%`,
    example: '查看冻结评测报告中的逐条缺失与误报'
  })),
  algorithmVersion: evaluationReport.algorithmVersion,
  promptVersion: evaluationReport.promptVersion,
  demoData: false
}

const frozenSnapshot = {
  schemaVersion: '1.0.0',
  snapshot: { code: snapshotCode, generatedAt, dataMode: 'FROZEN_CACHE', usageScope: '10 条 JD 当前岗位试验快照', sourceDocumentCount: documents.length, sourceHash, disclaimer: '本快照只用于比赛演示和当前技能观察，不代表市场趋势。' },
  runtimeStatus: { dataMode: 'FROZEN_CACHE', operatingMode: 'STATIC_JSON_SNAPSHOT', dynamicRecordCount: documents.length, publishedRealDocumentCount: dynamicRecordItems.filter(item => item.status === 'PROMOTED').length, pendingCandidateSkillCount: reviewQueueSummary.pendingCandidateSkillCount, snapshotCode },
  dashboard, publicOverview, publicGraphOverview, roles, marketTimelines,
  repositoryOverview: {
    baseLibrary: { name: '冻结基础资料', recordCount: documents.length, status: 'FROZEN', detail: 'JSON 文件只读发布' },
    dynamicLibrary: { name: '待更新采集区', recordCount: 0, status: 'LOCAL_PIPELINE', detail: '定期采集脚本在服务器外运行' },
    pipeline: dashboard.pipeline.map((step, index) => ({ name: step.name, count: step.count, tone: ['blue', 'cyan', 'amber', 'green'][index] })),
    crawlerTasks: [{ name: '授权数据源采集', schedule: '手动或定时任务', parserVersion: 'static-collector-v1.0', recordCount: documents.length, status: 'CODE_READY' }],
    promotion: { batchCode: snapshotCode, candidates: documents.length, promoted: dynamicRecordItems.filter(item => item.status === 'PROMOTED').length, quarantined: 0, rejected: 0, policyVersion: quality.policyVersion }, demoData: false
  },
  quality,
  dataFiles: {
    marketCards: `/data/snapshots/${snapshotCode}-market-cards.json`,
    dynamicRecords: `/data/snapshots/${snapshotCode}-jd-records.json`,
    dynamicRecordDetails: `/data/snapshots/${snapshotCode}-jd-details.json`,
    realSnapshot: `/data/snapshots/${snapshotCode}-real-snapshot.json`
  }, candidateSkills, reviewQueueSummary, sourceLedger,
  emergingCandidates: roles.filter(role => role.emerging).map(role => ({ id: role.id, name: role.name, nearestRole: 'Java 开发工程师', emergenceScore: 0, uncertainty: '试验样本不足', documentCount: marketCards[String(role.id)].sampleSize, companyCount: marketCards[String(role.id)].independentCompanies, sourceCount: sources.size, clusterStability: 0, status: 'FROZEN_OBSERVATION', responsibilities: role.responsibilities, requiredSkills: role.requiredSkills.map(item => item.skill), bonusSkills: role.bonusSkills.map(item => item.skill), scenarios: role.scenarios, supportingEvidence: marketCards[String(role.id)].evidence, opposingEvidence: ['样本量仅 5 条', '所有样本均缺少明确发布时间'], metadata: { dataMode: 'FROZEN_CACHE', graphRelease: snapshotCode, sampleSize: marketCards[String(role.id)].sampleSize, independentCompanies: marketCards[String(role.id)].independentCompanies, confidence: 0.4, evidenceAvailable: true } })),
  evaluationSummary
}

await mkdir(resolve(outputDirectory, 'snapshots'), { recursive: true })
const serialized = `${JSON.stringify(frozenSnapshot, null, 2)}\n`
const versionedPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}.json`)
const marketCardsPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}-market-cards.json`)
const dynamicRecordsPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}-jd-records.json`)
const dynamicRecordDetailsPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}-jd-details.json`)
const realSnapshotPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}-real-snapshot.json`)
const temporaryPath = resolve(outputDirectory, '.current.json.tmp')
await writeFile(versionedPath, serialized, 'utf8')
await writeFile(marketCardsPath, `${JSON.stringify(marketCards)}\n`, 'utf8')
await writeFile(dynamicRecordsPath, `${JSON.stringify({ items: dynamicRecordItems, page: 1, size: 20, total: dynamicRecordItems.length })}\n`, 'utf8')
await writeFile(dynamicRecordDetailsPath, `${JSON.stringify(dynamicRecordDetails)}\n`, 'utf8')
await writeFile(realSnapshotPath, `${JSON.stringify({ snapshotCode, dataMode: 'FROZEN_CACHE', usageScope: 'CURRENT_SNAPSHOT_ONLY', documentCount: documents.length, independentCompanyCount: companies.size, publishedTimeCoverage: missingPublishedAt === 0, roles: compactRealRoleSnapshots(realRoleSnapshots) })}\n`, 'utf8')
await writeFile(temporaryPath, serialized, 'utf8')
await rename(temporaryPath, resolve(outputDirectory, 'current.json'))
await writeFile(resolve(outputDirectory, 'metadata.json'), `${JSON.stringify(frozenSnapshot.snapshot, null, 2)}\n`, 'utf8')
console.log(`Frozen snapshot created: ${snapshotCode}`)
console.log(`Current file: ${resolve(outputDirectory, 'current.json')}`)

function compactRealRoleSnapshots(items) {
  return items.map(role => ({
    ...role,
    responsibilities: role.responsibilities.map(item => ({ ...item, evidence: item.evidence.slice(0, 2) })),
    requiredSkills: role.requiredSkills.map(item => ({ ...item, evidence: item.evidence.slice(0, 2) })),
    bonusSkills: role.bonusSkills.map(item => ({ ...item, evidence: item.evidence.slice(0, 2) })),
    mentionedSkills: role.mentionedSkills.map(item => ({ ...item, evidence: item.evidence.slice(0, 2) }))
  }))
}
