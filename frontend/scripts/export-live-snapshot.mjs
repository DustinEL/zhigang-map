import { createHash } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { collectSnapshotQualityIssues, filterPublicEmergingQueries, serializedTextIssues, sha256 } from './snapshot-quality.mjs'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const frontendRoot = resolve(scriptDirectory, '..')
const projectRoot = resolve(frontendRoot, '..')
const outputDirectory = resolve(process.argv[2] ?? resolve(frontendRoot, 'public/data'))

await loadLocalEnvironment(resolve(projectRoot, '.env.local'))

const apiBase = (process.env.ZHIGANG_API_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const username = process.env.PROFESSIONAL_USERNAME
const password = process.env.PROFESSIONAL_PASSWORD
if (!username || !password) {
  throw new Error('缺少 PROFESSIONAL_USERNAME 或 PROFESSIONAL_PASSWORD，无法导出专业端数据。')
}

const authorization = `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`
const corpusPath = resolve(projectRoot, 'test-data/jd-corpus/qualified.jsonl')
const corpusBuffer = await readFile(corpusPath)
const baselineCorpusHash = createHash('sha256').update(corpusBuffer).digest('hex')
const evaluationReport = JSON.parse(await readFile(
  resolve(projectRoot, 'test-data/evaluation/evaluation-report.json'), 'utf8'))
if (evaluationReport.official !== true || evaluationReport.metrics?.length !== 3
    || evaluationReport.metrics.some(metric => metric.sampleCount < metric.targetSamples)) {
  throw new Error('正式冻结评测报告不完整，拒绝生成网站快照。')
}

const [
  liveRuntime,
  liveDashboard,
  publicOverview,
  publicGraphOverview,
  roles,
  repositoryOverview,
  quality,
  dynamicRecords,
  candidateSkills,
  reviewQueueSummary,
  realSnapshot,
  sourceLedger,
  emergingCandidates,
  emergingRuns,
  emergingQueries
] = await Promise.all([
  request('/api/runtime-status'),
  request('/api/dashboard'),
  request('/api/public/overview'),
  request('/api/public/graph-overview'),
  request('/api/job-roles'),
  request('/api/repositories/overview'),
  request('/api/dataset-snapshots/current/quality-report'),
  loadAllDynamicRecords(),
  request('/api/candidate-skills'),
  request('/api/review-queues/summary'),
  request('/api/real-job-snapshots/current'),
  request('/api/professional/source-ledger'),
  request('/api/emerging-job-candidates'),
  request('/api/emerging-discovery/runs'),
  request('/api/emerging-discovery/queries?limit=30')
])

const publishedRecords = dynamicRecords.items.filter(item => item.status === 'PROMOTED').length
const publishedCompanyCount = new Set(dynamicRecords.items.map(item => item.company?.trim()).filter(Boolean)).size
const publishedSourceCount = sourceLedger.length
if (liveRuntime.dataMode !== 'REAL' || dynamicRecords.total === 0) {
  throw new Error('当前后端没有已发布的真实 JD，拒绝覆盖网站快照。')
}
if (publishedRecords !== dynamicRecords.total) {
  throw new Error(`动态记录中仅 ${publishedRecords}/${dynamicRecords.total} 条已发布，拒绝生成网站快照。`)
}
if (publishedSourceCount < 3) {
  throw new Error(`当前仅识别到 ${publishedSourceCount} 个来源，未达到至少 3 个来源的发布门槛。`)
}
if (publishedCompanyCount < 30) {
  throw new Error(`当前仅覆盖 ${publishedCompanyCount} 家企业，未达到至少 30 家企业的发布门槛。`)
}
const sourceHash = createHash('sha256').update(dynamicRecords.items
  .toSorted((left, right) => left.id - right.id)
  .map(item => `${item.id}:${item.sourceRecordKey}:${item.contentHash}:${item.status}`)
  .join('|')).digest('hex')

const generatedAt = new Date().toISOString()
const roleIds = roles.map(role => role.id)
const evaluationSummary = formalEvaluationSummary(evaluationReport)
const javaRole = roles.find(role => role.code === 'JAVA_ENGINEER')
if (!javaRole) throw new Error('岗位目录缺少 JAVA_ENGINEER，拒绝生成 P0-4 发布快照。')
const javaRoleEvolution = await request(`/api/job-roles/${javaRole.id}/evolution-runs/latest`)
validateRoleEvolution(javaRoleEvolution, { requirePublishableChanges: true })
const actionableEvolutionCodes = ['LLM_APP_ENGINEER', 'AI_DATA_ENGINEER', 'AI_SECURITY_TEST_ENGINEER']
const actionableRoleEvolutions = {}
for (const code of actionableEvolutionCodes) {
  const role = roles.find(roleItem => roleItem.code === code)
  if (!role) throw new Error(`岗位目录缺少 ${code}，拒绝生成岗位演化快照。`)
  const evolution = await request(`/api/job-roles/${role.id}/evolution-runs/latest`)
  validateRoleEvolution(evolution, { requirePublishableChanges: false })
  actionableRoleEvolutions[String(role.id)] = evolution
}
const releaseHash = createHash('sha256').update(JSON.stringify({
  sourceHash,
  evolutions: [javaRoleEvolution, ...Object.values(actionableRoleEvolutions)].map(item => ({
    roleCode: item.roleCode,
    evolutionInputHash: item.inputHash,
    evolutionPolicyHash: item.policyHash,
    evolutionVersion: item.version
  }))
})).digest('hex')
const snapshotCode = `jd-frozen-${generatedAt.slice(0, 10)}-${releaseHash.slice(0, 8)}`

const publicEmergingQueries = filterPublicEmergingQueries(emergingQueries)
const publishedEmergingQueries = publicEmergingQueries.filter(item => item.status === 'EMERGING_ROLE'
  && item.publishedRoleId != null && item.publishedReleaseId != null)
if (publishedEmergingQueries.length === 0) {
  throw new Error('当前没有通过证据门禁并自动入图的新兴岗位，拒绝生成 P0-3 发布快照。')
}
for (const item of publishedEmergingQueries) {
  if (item.evidenceCount < 3 || item.sourceCount < 2 || item.fieldEvidenceComplete !== true) {
    throw new Error(`新兴岗位查询 ${item.id} 的证据门禁不完整，拒绝生成发布快照。`)
  }
  const role = roles.find(roleItem => roleItem.id === item.publishedRoleId)
  if (!role || role.emerging !== true) {
    throw new Error(`新兴岗位查询 ${item.id} 未关联岗位目录中的新兴岗位，拒绝生成发布快照。`)
  }
}

const graphFilterTechStacks = ['Java', 'JavaScript']
const graphFilterEntries = await Promise.all(graphFilterTechStacks.map(async techStack => [
  techStack.toLocaleLowerCase('zh-CN'),
  await request(`/api/public/graph-overview?techStack=${encodeURIComponent(techStack)}`)
]))

const [marketCardValues, marketTimelineValues, dynamicRecordDetailValues] = await Promise.all([
  mapConcurrent(roleIds, 6, roleId => request(`/api/job-roles/${roleId}/market-card`)),
  mapConcurrent(roleIds, 6, roleId => request(`/api/job-roles/${roleId}/market-timeline`)),
  mapConcurrent(dynamicRecords.items.map(item => item.id), 6, recordId => request(`/api/dynamic-records/${recordId}`))
])

const normalizedRoles = roles
const marketCards = Object.fromEntries(marketCardValues.map(card => [String(card.role.id), {
  ...card,
  role: card.role,
  metadata: normalizeMetadata(card.metadata, snapshotCode)
}]))
const marketTimelines = Object.fromEntries(marketTimelineValues.map(timeline => [String(timeline.roleId), timeline]))
const dynamicRecordDetails = Object.fromEntries(dynamicRecordDetailValues.map(detail => [String(detail.summary.id), sanitizeUrls(detail)]))

const runtimeStatus = {
  ...liveRuntime,
  dataMode: 'FROZEN_CACHE',
  operatingMode: 'STATIC_JSON_SNAPSHOT',
  dynamicRecordCount: dynamicRecords.total,
  publishedRealDocumentCount: publishedRecords,
  pendingCandidateSkillCount: candidateSkills.filter(isPendingCandidateSkill).length,
  snapshotCode
}
const dashboard = {
  ...liveDashboard,
  sourceCount: publishedSourceCount,
  rawDocumentCount: dynamicRecords.total,
  independentCompanyCount: publishedCompanyCount,
  currentRelease: snapshotCode,
  operatingMode: 'STATIC_JSON_SNAPSHOT',
  pipeline: [
    { name: '中国计算机岗位采集', status: 'FROZEN', count: dynamicRecords.total, detail: `覆盖 ${publishedSourceCount} 个公开来源、${publishedCompanyCount} 家企业` },
    { name: '质量与去重', status: quality.publishable ? 'COMPLETED' : 'LIMITED', count: quality.effectiveCount, detail: '保留来源、内容哈希和原文证据' },
    { name: '结构化抽取', status: 'COMPLETED', count: dynamicRecords.total, detail: '岗位、技能、职责和原文锚点已同步' },
    { name: '发布快照', status: 'COMPLETED', count: normalizedRoles.length, detail: snapshotCode }
  ]
}
const normalizedPublicOverview = {
  ...publicOverview,
  updatedAt: generatedAt,
  sampleCount: dynamicRecords.total,
  companyCount: publishedCompanyCount,
  dataScope: `${dynamicRecords.total} 条中国计算机相关 JD，覆盖 ${publishedCompanyCount} 家企业、${publishedSourceCount} 个公开来源`,
  disclaimer: '当前数据用于比赛岗位能力观察；来源与原文可追溯，不代表完整市场趋势。',
  roles: publicOverview.roles
}
const normalizedPublicGraph = {
  ...publicGraphOverview,
  releaseVersion: snapshotCode,
  metadata: normalizeMetadata(publicGraphOverview.metadata, snapshotCode)
}
const publicGraphFilters = {
  techStack: Object.fromEntries(graphFilterEntries.map(([key, graph]) => [key, {
    ...graph,
    metadata: normalizeMetadata(graph.metadata, snapshotCode)
  }]))
}
const normalizedRealSnapshot = {
  ...realSnapshot,
  snapshotCode,
  dataMode: 'FROZEN_CACHE',
  roles: realSnapshot.roles.map(role => ({
    ...role,
    responsibilities: role.responsibilities.map(item => ({ ...item, evidence: item.evidence.slice(0, 2) })),
    requiredSkills: role.requiredSkills.map(item => ({ ...item, evidence: item.evidence.slice(0, 2) })),
    bonusSkills: role.bonusSkills.map(item => ({ ...item, evidence: item.evidence.slice(0, 2) })),
    mentionedSkills: role.mentionedSkills.map(item => ({ ...item, evidence: item.evidence.slice(0, 2) }))
  }))
}
const normalizedRepositoryOverview = {
  ...repositoryOverview,
  baseLibrary: {
    name: '中国计算机岗位证据库',
    recordCount: dynamicRecords.total,
    status: 'FROZEN',
    detail: `${publishedCompanyCount} 家企业、${publishedSourceCount} 个来源的只读发布快照`
  },
  dynamicLibrary: {
    name: '本地更新区',
    recordCount: 0,
    status: 'READ_ONLY_ON_PUBLIC_SITE',
    detail: '公网静态站只读；新数据在本地完整环境更新后重新发布'
  },
  pipeline: dashboard.pipeline.map((step, index) => ({
    name: step.name,
    count: step.count,
    tone: ['blue', 'cyan', 'amber', 'green'][index] || 'blue'
  })),
  promotion: {
    batchCode: snapshotCode,
    candidates: dynamicRecords.total,
    promoted: publishedRecords,
    quarantined: dynamicRecords.total - publishedRecords,
    rejected: 0,
    policyVersion: quality.policyVersion
  },
  demoData: false
}

const frozenSnapshot = sanitizeUrls({
  schemaVersion: '1.0.0',
  snapshot: {
    code: snapshotCode,
    generatedAt,
    dataMode: 'FROZEN_CACHE',
    usageScope: normalizedPublicOverview.dataScope,
    sourceDocumentCount: dynamicRecords.total,
    sourceHash,
    baselineCorpusHash,
    disclaimer: normalizedPublicOverview.disclaimer
  },
  runtimeStatus,
  dashboard,
  publicOverview: normalizedPublicOverview,
  publicGraphOverview: normalizedPublicGraph,
  publicGraphFilters,
  roles: normalizedRoles,
  marketTimelines,
  repositoryOverview: normalizedRepositoryOverview,
  quality: { ...quality, snapshotId: snapshotCode, generatedAt },
  dataFiles: {
    marketCards: `/data/snapshots/${snapshotCode}-market-cards.json`,
    dynamicRecords: `/data/snapshots/${snapshotCode}-jd-records.json`,
    dynamicRecordDetails: `/data/snapshots/${snapshotCode}-jd-details.json`,
    realSnapshot: `/data/snapshots/${snapshotCode}-real-snapshot.json`
  },
  candidateSkills,
  reviewQueueSummary,
  sourceLedger,
  evaluationSummary,
  emergingCandidates: emergingCandidates.map(candidate => ({
    ...candidate,
    metadata: normalizeMetadata(candidate.metadata, snapshotCode)
  })),
  emergingQueries: publicEmergingQueries,
  emergingDiscovery: {
    latestRun: emergingRuns[0] ? {
      runCode: emergingRuns[0].runCode,
      status: emergingRuns[0].status,
      policyVersion: emergingRuns[0].policyVersion,
      embeddingModel: emergingRuns[0].embeddingModel,
      embeddingDimension: emergingRuns[0].embeddingDimension,
      documentCount: emergingRuns[0].documentCount,
      clusterCount: emergingRuns[0].clusterCount,
      candidateCount: emergingRuns[0].candidateCount,
      noiseCount: emergingRuns[0].noiseCount,
      completedAt: emergingRuns[0].completedAt
    } : null,
    writeEnabled: false
  },
  roleEvolutions: { [String(javaRole.id)]: javaRoleEvolution, ...actionableRoleEvolutions }
})

await mkdir(resolve(outputDirectory, 'snapshots'), { recursive: true })
const serialized = `${JSON.stringify(frozenSnapshot, null, 2)}\n`
const versionedPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}.json`)
const marketCardsPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}-market-cards.json`)
const dynamicRecordsPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}-jd-records.json`)
const dynamicRecordDetailsPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}-jd-details.json`)
const realSnapshotPath = resolve(outputDirectory, 'snapshots', `${snapshotCode}-real-snapshot.json`)
const temporaryPath = resolve(outputDirectory, '.current.json.tmp')
const marketCardsContent = `${JSON.stringify(marketCards)}\n`
const dynamicRecordsContent = `${JSON.stringify({ items: dynamicRecords.items, page: 1, size: dynamicRecords.total, total: dynamicRecords.total })}\n`
const dynamicRecordDetailsContent = `${JSON.stringify(dynamicRecordDetails)}\n`
const realSnapshotContent = `${JSON.stringify(normalizedRealSnapshot)}\n`
const metadataContent = `${JSON.stringify(frozenSnapshot.snapshot, null, 2)}\n`
const qualityIssues = [
  ...serializedTextIssues(serialized, 'current.json'),
  ...collectSnapshotQualityIssues(frozenSnapshot, 'current.json'),
  ...collectSnapshotQualityIssues(marketCards, 'market-cards'),
  ...collectSnapshotQualityIssues(dynamicRecordDetails, 'jd-details'),
  ...collectSnapshotQualityIssues(normalizedRealSnapshot, 'real-snapshot')
]
if (qualityIssues.length) {
  throw new Error(`快照编码、隐私或敏感信息门禁未通过：\n${qualityIssues.map(issue => `- ${issue}`).join('\n')}`)
}
const checksumEntries = [
  ['current.json', serialized],
  [`snapshots/${snapshotCode}.json`, serialized],
  [`snapshots/${snapshotCode}-market-cards.json`, marketCardsContent],
  [`snapshots/${snapshotCode}-jd-records.json`, dynamicRecordsContent],
  [`snapshots/${snapshotCode}-jd-details.json`, dynamicRecordDetailsContent],
  [`snapshots/${snapshotCode}-real-snapshot.json`, realSnapshotContent],
  ['metadata.json', metadataContent]
]
await writeFile(versionedPath, serialized, 'utf8')
await writeFile(marketCardsPath, marketCardsContent, 'utf8')
await writeFile(dynamicRecordsPath, dynamicRecordsContent, 'utf8')
await writeFile(dynamicRecordDetailsPath, dynamicRecordDetailsContent, 'utf8')
await writeFile(realSnapshotPath, realSnapshotContent, 'utf8')
await writeFile(temporaryPath, serialized, 'utf8')
await rename(temporaryPath, resolve(outputDirectory, 'current.json'))
await writeFile(resolve(outputDirectory, 'metadata.json'), metadataContent, 'utf8')
await writeFile(resolve(outputDirectory, 'checksums.sha256'), `${checksumEntries.map(([name, content]) => `${sha256(content)}  ${name}`).join('\n')}\n`, 'utf8')
await writeFile(resolve(outputDirectory, 'validation-report.json'), `${JSON.stringify({
  snapshotCode,
  generatedAt,
  passed: true,
  publicQueryCount: publicEmergingQueries.length,
  filteredQueryCount: emergingQueries.length - publicEmergingQueries.length,
  checksums: Object.fromEntries(checksumEntries.map(([name, content]) => [name, sha256(content)])),
  checks: ['编码质量', '空查询', '敏感信息', '密钥', '证据门禁', 'Java 双窗口演化']
}, null, 2)}\n`, 'utf8')

console.log(`网站快照已生成：${snapshotCode}`)
console.log(`JD：${dynamicRecords.total} 条；企业：${publishedCompanyCount} 家；来源：${publishedSourceCount} 个；岗位：${roles.length} 个`)
console.log(`输出：${resolve(outputDirectory, 'current.json')}`)

async function request(path, maxAttempts = 3) {
  let lastError
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      const response = await fetch(`${apiBase}${path}`, {
        headers: { Accept: 'application/json', Authorization: authorization }
      })
      const payload = await response.json().catch(() => null)
      if (response.ok && payload?.success !== false && payload?.code === 'OK') return payload.data
      lastError = new Error(`GET ${path} 失败：${payload?.code || response.status} ${payload?.message || response.statusText}`)
      if (response.status < 500 && payload?.code !== 'INTERNAL_ERROR') break
    } catch (error) {
      lastError = error
    }
    if (attempt < maxAttempts) await delay(attempt * 250)
  }
  throw lastError
}

function formalEvaluationSummary(report) {
  const labels = {
    JD_EXTRACTION: ['岗位抽取黄金集', '#16835f', '岗位、职责、技能与要求类型'],
    RESUME_EXTRACTION: ['简历解析黄金集', '#2d718f', '教育、项目、技能与证据片段'],
    JOB_MATCH: ['人岗匹配验证集', '#7f6aa1', '专家标注岗位对与差距项']
  }
  return {
    reportVersion: report.reportVersion,
    generatedAt: report.generatedAt,
    datasetManifestSha256: report.datasetManifestSha256,
    metrics: report.metrics.map(metric => ({
      code: metric.code,
      name: metric.name,
      value: metric.accuracy,
      target: report.targetAccuracy,
      sampleCount: metric.sampleCount,
      targetSamples: metric.targetSamples,
      precision: metric.precision,
      recall: metric.recall,
      f1: metric.f1,
      status: metric.passed ? 'VERIFIED_PASS' : 'VERIFIED_FAIL',
      color: labels[metric.code]?.[1] ?? '#2d718f',
      note: labels[metric.code]?.[2] ?? '正式冻结测试集'
    })),
    datasets: report.metrics.map(metric => ({
      name: `${metric.code}-GOLD-2026.08`,
      type: labels[metric.code]?.[0] ?? '正式冻结测试集',
      sampleCount: metric.sampleCount,
      targetSamples: metric.targetSamples,
      version: report.reportVersion,
      status: metric.goldSha256 ? 'FROZEN' : 'AWAITING_ANNOTATION'
    })),
    errors: report.metrics.filter(metric => metric.errors?.length).map(metric => ({
      type: metric.name,
      count: metric.errors.length,
      share: `${Math.round(metric.errors.length * 100 / metric.sampleCount)}%`,
      example: '查看冻结评测报告中的逐条缺失与误报'
    })),
    algorithmVersion: report.algorithmVersion,
    promptVersion: report.promptVersion,
    demoData: false
  }
}

async function mapConcurrent(values, concurrency, mapper) {
  const results = new Array(values.length)
  let nextIndex = 0
  async function worker() {
    for (;;) {
      const index = nextIndex++
      if (index >= values.length) return
      results[index] = await mapper(values[index], index)
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, values.length) }, worker))
  return results
}

async function loadAllDynamicRecords() {
  const items = []
  const size = 100
  for (let page = 1; ; page += 1) {
    const result = await request(`/api/dynamic-records?page=${page}&size=${size}`)
    items.push(...result.items)
    if (items.length >= result.total) {
      return { items, page: 1, size: items.length, total: result.total }
    }
  }
}

function normalizeMetadata(metadata, graphRelease) {
  return { ...(metadata || {}), dataMode: 'FROZEN_CACHE', graphRelease }
}

function delay(milliseconds) {
  return new Promise(resolveDelay => setTimeout(resolveDelay, milliseconds))
}

function isPendingCandidateSkill(item) {
  return !item.reviewDecision || ['PENDING_REVIEW', 'MIXED'].includes(item.reviewDecision)
}

function validateRoleEvolution(run, { requirePublishableChanges = false } = {}) {
  if (!run.roleCode || !Array.isArray(run.windows) || run.windows.length !== 2) {
    throw new Error(`${run.roleCode || '岗位'}岗位演化缺少两个真实时间窗口。`)
  }
  const baseline = run.windows.find(window => window.type === 'BASELINE')
  const current = run.windows.find(window => window.type === 'CURRENT')
  if (!baseline || !current) {
    throw new Error(`${run.roleCode}岗位演化缺少 BASELINE/CURRENT 窗口。`)
  }
  if (requirePublishableChanges && (baseline.documentCount < 8 || current.documentCount < 8
      || baseline.companyCount < 8 || current.companyCount < 8)) {
    throw new Error('Java 岗位演化双窗口未达到真实 JD 与独立企业门禁。')
  }
  if (requirePublishableChanges && (!Array.isArray(run.changes) || run.changes.length === 0
      || run.changes.some(change => !Array.isArray(change.points) || change.points.length !== 2
        || !Array.isArray(change.evidence) || change.evidence.some(item => !item.anchorValid || !item.quote)))) {
    throw new Error('Java 岗位演化缺少逐技能双窗口指标或有效原文证据。')
  }
  if (requirePublishableChanges && run.changes.some(change => change.publishable
      && !(change.differenceLowerBound > 0 || change.differenceUpperBound < 0))) {
    throw new Error('Java 岗位演化存在未通过 95% 比例差区间门禁的可发布变化。')
  }
}

function sanitizeUrls(value) {
  if (Array.isArray(value)) return value.map(sanitizeUrls)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).map(([key, child]) => {
    if (/url$/i.test(key) && typeof child === 'string' && child) {
      try {
        const url = new URL(child)
        if (url.protocol !== 'https:') return [key, '']
        url.search = ''
        url.hash = ''
        return [key, url.toString()]
      } catch {
        return [key, '']
      }
    }
    return [key, sanitizeUrls(child)]
  }))
}

async function loadLocalEnvironment(file) {
  const content = await readFile(file, 'utf8')
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const separator = trimmed.indexOf('=')
    if (separator < 1) continue
    const name = trimmed.slice(0, separator).trim()
    if (process.env[name] === undefined) process.env[name] = trimmed.slice(separator + 1).trim()
  }
}
