import { readFile, writeFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { collectSnapshotQualityIssues, isPublicEmergingQuery, serializedTextIssues, sha256 } from './snapshot-quality.mjs'

const file = resolve(process.argv[2] ?? 'public/data/current.json')
const writeReport = process.argv.includes('--write-report')
const serialized = await readFile(file, 'utf8')
const value = JSON.parse(serialized)
const externalSerialized = new Map()
const externalValues = new Map()
async function externalData(name) {
  if (value[name]) return value[name]
  if (externalValues.has(name)) return externalValues.get(name)
  const publicPath = value.dataFiles?.[name]
  if (!publicPath) return undefined
  const localPath = resolve(file, '..', publicPath.replace(/^\/data\//, ''))
  const content = await readFile(localPath, 'utf8')
  externalSerialized.set(name, content)
  const parsed = JSON.parse(content)
  externalValues.set(name, parsed)
  return parsed
}
const dynamicRecords = await externalData('dynamicRecords')
const errors = []
errors.push(...serializedTextIssues(serialized, file))
errors.push(...collectSnapshotQualityIssues(value, file))
if (value.schemaVersion !== '1.0.0') errors.push('schemaVersion 必须为 1.0.0')
if (value.snapshot?.dataMode !== 'FROZEN_CACHE') errors.push('snapshot.dataMode 必须为 FROZEN_CACHE')
if (!value.snapshot?.code || !value.snapshot?.generatedAt || !value.snapshot?.sourceHash) errors.push('快照版本元数据不完整')
if (!Array.isArray(value.roles) || value.roles.length === 0) errors.push('至少需要一个岗位')
if (!Array.isArray(value.publicOverview?.roles) || value.publicOverview.roles.length === 0) errors.push('缺少公开岗位概览')
if (value.publicOverview?.sampleCount !== dynamicRecords?.total) errors.push('公开概览与 JD 总数不一致')
if (value.publicOverview?.roles?.some(role => role.status === 'BUILDING' && (role.sampleCount != null || role.companyCount != null))) errors.push('建设中的岗位不能公开样本数字')
if (!Array.isArray(dynamicRecords?.items)) errors.push('dynamicRecords.items 必须是数组')
if (!Array.isArray(value.sourceLedger)) errors.push('sourceLedger 必须是数组')
if (value.emergingQueries !== undefined && !Array.isArray(value.emergingQueries)) errors.push('emergingQueries 必须是数组')
if (value.emergingQueries?.some(item => !isPublicEmergingQuery(item))) errors.push('emergingQueries 含空查询、乱码或无公开证据记录')
const javaRole = value.roles?.find(role => role.code === 'JAVA_ENGINEER')
const javaEvolution = javaRole ? value.roleEvolutions?.[String(javaRole.id)] : undefined
if (!javaEvolution || javaEvolution.windows?.length !== 2) errors.push('P0-4 快照必须包含 Java 岗位双窗口演化')
if (javaEvolution?.windows?.some(window => window.documentCount < 8 || window.companyCount < 8)) errors.push('P0-4 双窗口未达到真实 JD 与企业门禁')
if (!javaEvolution?.changes?.length) errors.push('P0-4 快照缺少逐技能演化指标')
if (javaEvolution?.changes?.some(change => change.points?.length !== 2 || change.evidence?.some(item => !item.anchorValid || !item.quote))) errors.push('P0-4 演化指标或证据锚点不完整')
if (javaEvolution?.changes?.some(change => change.publishable && !(change.differenceLowerBound > 0 || change.differenceUpperBound < 0))) errors.push('P0-4 可发布变化未通过比例差区间门禁')
for (const code of ['LLM_APP_ENGINEER', 'AI_DATA_ENGINEER', 'AI_SECURITY_TEST_ENGINEER']) {
  const role = value.roles?.find(item => item.code === code)
  const evolution = role ? value.roleEvolutions?.[String(role.id)] : undefined
  if (!role || !evolution || evolution.roleCode !== code || evolution.roleId !== role.id || evolution.windows?.length !== 2) {
    errors.push(`${code}快照必须包含岗位目录对应的双窗口演化`)
  }
  if (evolution?.changes?.some(change => change.evidence?.some(item => item.documentId <= 0))) {
    errors.push(`${code}演化证据文档 ID 无效`)
  }
}
if (!Array.isArray(value.evaluationSummary?.metrics) || value.evaluationSummary.metrics.length !== 3) errors.push('评测摘要必须包含三项正式指标')
if (value.evaluationSummary?.metrics?.some(item => item.sampleCount < item.targetSamples)) errors.push('正式评测样本未达到目标数量')
if (value.sourceLedger?.some(item => !item.sourceId || !item.sourceName || !item.sourceTier || !item.timeScope || !item.adapter)) errors.push('来源台账字段不完整')
if (value.sourceLedger?.reduce((sum, item) => sum + item.acceptedCount, 0) !== dynamicRecords?.total) errors.push('来源台账合格数与 JD 总数不一致')
if (dynamicRecords?.total !== dynamicRecords?.items?.length) errors.push('JD 总数与列表数量不一致')
const publishedDocumentCount = dynamicRecords?.items?.filter(item => item.status === 'PROMOTED').length ?? 0
const pendingCandidateSkillCount = value.candidateSkills?.filter(item => !item.reviewDecision || ['PENDING_REVIEW', 'MIXED'].includes(item.reviewDecision)).length ?? 0
const waitingSkillCount = dynamicRecords?.items?.filter(item => item.reviewRoute === 'WAITING_SKILL').length ?? 0
if (value.runtimeStatus?.publishedRealDocumentCount !== publishedDocumentCount) errors.push('运行摘要与已发布 JD 数量不一致')
if (value.runtimeStatus?.pendingCandidateSkillCount !== pendingCandidateSkillCount) errors.push('运行摘要与待审核技能数量不一致')
if (value.reviewQueueSummary?.pendingCandidateSkillCount !== pendingCandidateSkillCount) errors.push('审核队列与待审核技能数量不一致')
if (value.reviewQueueSummary?.waitingSkillCount !== waitingSkillCount) errors.push('审核队列与等待技能的 JD 数量不一致')
if (pendingCandidateSkillCount === 0 && waitingSkillCount !== 0) errors.push('候选技能已处理完成，但仍有 JD 等待技能审核')
if (!Array.isArray(value.quality?.metrics) || value.quality.metrics.length === 0) errors.push('缺少质量指标')
if (!value.dynamicRecordDetails && !value.dataFiles?.dynamicRecordDetails) errors.push('缺少 JD 详情数据或详情文件索引')
if (value.dynamicRecordDetails && !Object.values(value.dynamicRecordDetails).every(detail => detail.rawText && Array.isArray(detail.facts))) errors.push('JD 详情必须保留原文和事实')
if (value.dashboard?.trends?.length) errors.push('当前样本缺少发布时间，禁止生成趋势数据')
const publicUrls = []
function collectPublicUrls(item) {
  if (!item || typeof item !== 'object') return
  if (Array.isArray(item)) {
    item.forEach(collectPublicUrls)
    return
  }
  for (const [key, child] of Object.entries(item)) {
    if (/url$/i.test(key) && typeof child === 'string' && child) publicUrls.push(child)
    else collectPublicUrls(child)
  }
}
collectPublicUrls(value)
if (publicUrls.some(url => !url.startsWith('https://') || /[?#]/.test(url))) errors.push('公开来源链接必须使用 HTTPS，且不得携带查询参数或锚点')
for (const name of ['marketCards', 'dynamicRecords', 'dynamicRecordDetails', 'realSnapshot']) {
  const external = await externalData(name)
  if (external !== undefined) {
    errors.push(...serializedTextIssues(externalSerialized.get(name) ?? JSON.stringify(external), name))
    errors.push(...collectSnapshotQualityIssues(external, name))
  }
}
if (errors.length) {
  errors.forEach(error => console.error(`- ${error}`))
  process.exitCode = 1
} else {
  console.log(`Snapshot valid: ${value.snapshot.code}`)
  console.log(`Documents: ${dynamicRecords.total}; roles: ${value.roles.length}; pending skills: ${pendingCandidateSkillCount}`)
  console.log(`SHA-256: ${sha256(serialized)}`)
  if (writeReport) {
    const dataDirectory = dirname(file)
    const checksumEntries = [['current.json', serialized]]
    for (const [name, publicPath] of Object.entries(value.dataFiles ?? {})) {
      if (typeof publicPath !== 'string' || !externalSerialized.has(name)) continue
      checksumEntries.push([publicPath.replace(/^\/data\//, ''), externalSerialized.get(name)])
    }
    const versionedPath = resolve(dataDirectory, 'snapshots', `${value.snapshot.code}.json`)
    const versionedContent = await readFile(versionedPath, 'utf8')
    checksumEntries.push([relative(dataDirectory, versionedPath).replaceAll('\\', '/'), versionedContent])
    const metadataPath = resolve(dataDirectory, 'metadata.json')
    const metadataContent = await readFile(metadataPath, 'utf8')
    checksumEntries.push(['metadata.json', metadataContent])
    const checksums = Object.fromEntries(checksumEntries.map(([name, content]) => [name, sha256(content)]))
    await writeFile(resolve(dataDirectory, 'checksums.sha256'), `${Object.entries(checksums).map(([name, hash]) => `${hash}  ${name}`).join('\n')}\n`, 'utf8')
    await writeFile(resolve(dataDirectory, 'validation-report.json'), `${JSON.stringify({
      snapshotCode: value.snapshot.code,
      generatedAt: new Date().toISOString(),
      passed: true,
      documentCount: dynamicRecords.total,
      companyCount: value.publicOverview.companyCount,
      sourceCount: value.sourceLedger.length,
      roleCount: value.roles.length,
      publicQueryCount: value.emergingQueries?.length ?? 0,
      javaEvolutionChangeCount: javaEvolution.changes.length,
      checksums
    }, null, 2)}\n`, 'utf8')
    console.log(`Validation report: ${resolve(dataDirectory, 'validation-report.json')}`)
  }
}
