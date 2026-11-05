import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { filterPublicEmergingQueries, sha256 } from './snapshot-quality.mjs'

const snapshotPath = resolve(process.argv[2] ?? 'public/data/current.json')
const outputPath = resolve(process.argv[3] ?? 'artifacts/snapshot-reconciliation.json')
const projectRoot = resolve(dirname(snapshotPath), '../../..')
await loadLocalEnvironment(resolve(projectRoot, '.env.local'))

const apiBase = (process.env.ZHIGANG_API_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const snapshotSerialized = await readFile(snapshotPath, 'utf8')
const snapshot = JSON.parse(snapshotSerialized)
const authorization = process.env.PROFESSIONAL_USERNAME && process.env.PROFESSIONAL_PASSWORD
  ? `Basic ${Buffer.from(`${process.env.PROFESSIONAL_USERNAME}:${process.env.PROFESSIONAL_PASSWORD}`).toString('base64')}`
  : ''

const javaRole = snapshot.roles.find(role => role.code === 'JAVA_ENGINEER')
if (!javaRole) throw new Error('静态快照缺少 Java 岗位，无法对账。')
const evolutionRoles = ['JAVA_ENGINEER', 'LLM_APP_ENGINEER', 'AI_DATA_ENGINEER', 'AI_SECURITY_TEST_ENGINEER']
const evolutionRoleEntries = evolutionRoles.map(code => {
  const role = snapshot.roles.find(item => item.code === code)
  if (!role) throw new Error(`静态快照缺少 ${code} 岗位，无法对账。`)
  return [code, role]
})

const [overview, graphOverview, sourceLedger, emergingQueries, ...evolutionAndGraphs] = await Promise.all([
  request('/api/public/overview'),
  request('/api/public/graph-overview'),
  request('/api/professional/source-ledger'),
  request('/api/emerging-discovery/queries?limit=100'),
  ...evolutionRoleEntries.map(([, role]) => request(`/api/job-roles/${role.id}/evolution-runs/latest`)),
  request('/api/public/graph-overview?techStack=Java'),
  request('/api/public/graph-overview?techStack=JavaScript')
])

const evolutionValues = Object.fromEntries(evolutionRoleEntries.map(([code], index) => [code, evolutionAndGraphs[index]]))
const javaEvolution = evolutionValues.JAVA_ENGINEER
const javaGraph = evolutionAndGraphs[evolutionRoleEntries.length]
const javaScriptGraph = evolutionAndGraphs[evolutionRoleEntries.length + 1]
const errors = []
const checks = []
function compare(label, dynamicValue, frozenValue) {
  const dynamicSerialized = JSON.stringify(dynamicValue)
  const frozenSerialized = JSON.stringify(frozenValue)
  const passed = dynamicSerialized === frozenSerialized
  checks.push({ label, passed, dynamicValue, frozenValue })
  if (!passed) errors.push(`${label}不一致`)
}
function sorted(values) {
  return [...values].sort((left, right) => String(left).localeCompare(String(right), 'zh-CN'))
}
function roleFacts(roles) {
  return roles.map(role => [role.id, role.code, role.status, role.roleVersion ?? '', role.evidenceLayer ?? '']).sort((left, right) => Number(left[0]) - Number(right[0]))
}
function queryFacts(items) {
  return filterPublicEmergingQueries(items).map(item => [item.id, item.status, item.publishedRoleId ?? null, item.evidenceCount, item.sourceCount, item.fieldEvidenceComplete]).sort((left, right) => Number(left[0]) - Number(right[0]))
}
function graphFacts(graph) {
  return {
    nodes: sorted(graph.nodes.map(node => node.id)),
    edges: graph.edges.map(edge => [
      edge.source,
      edge.target,
      edge.relation ?? edge.type ?? '',
      edge.label ?? '',
      edge.status ?? '',
      edge.weight ?? null
    ]).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right), 'zh-CN'))
  }
}
function exact(value) {
  return String(value ?? '').trim().toLocaleLowerCase('zh-CN')
}
function filterStaticGraph(techStack) {
  const exportedFilter = snapshot.publicGraphFilters?.techStack?.[exact(techStack)]
  if (exportedFilter) return exportedFilter
  const nodeById = new Map(snapshot.publicGraphOverview.nodes.map(node => [node.id, node]))
  const skillNodeIds = new Set(snapshot.publicGraphOverview.nodes
    .filter(node => node.type === 'SKILL' && exact(node.label) === exact(techStack))
    .map(node => node.id))
  const roleNodeIds = new Set(snapshot.publicGraphOverview.edges.flatMap(edge => {
    const source = nodeById.get(edge.source)
    const target = nodeById.get(edge.target)
    if (source?.type === 'JOB_ROLE' && skillNodeIds.has(edge.target)) return [source.id]
    if (target?.type === 'JOB_ROLE' && skillNodeIds.has(edge.source)) return [target.id]
    return []
  }))
  const edges = snapshot.publicGraphOverview.edges.filter(edge => {
    const source = nodeById.get(edge.source)
    const target = nodeById.get(edge.target)
    if (!source || !target) return false
    if (source.type === 'JOB_FAMILY' && target.type === 'JOB_ROLE') return roleNodeIds.has(target.id)
    if (source.type === 'JOB_ROLE' && target.type === 'SKILL') {
      return roleNodeIds.has(source.id) && skillNodeIds.has(target.id)
    }
    if (source.type === 'SKILL' && target.type === 'JOB_ROLE') {
      return skillNodeIds.has(source.id) && roleNodeIds.has(target.id)
    }
    return false
  })
  const connected = new Set(edges.flatMap(edge => [edge.source, edge.target]))
  roleNodeIds.forEach(id => connected.add(id))
  return {
    nodes: snapshot.publicGraphOverview.nodes.filter(node => connected.has(node.id)),
    edges
  }
}

compare('公开 JD 数量', overview.sampleCount, snapshot.publicOverview.sampleCount)
compare('公开企业数量', overview.companyCount, snapshot.publicOverview.companyCount)
compare('公开岗位实体、状态、版本与证据层', roleFacts(overview.roles), roleFacts(snapshot.publicOverview.roles))
compare('完整公开图节点与关系', graphFacts(graphOverview), graphFacts(snapshot.publicGraphOverview))
compare('公开来源台账', sourceLedger.map(item => [item.sourceId, item.sourceName, item.acceptedCount]), snapshot.sourceLedger.map(item => [item.sourceId, item.sourceName, item.acceptedCount]))
compare('公开缓存查询与证据状态', queryFacts(emergingQueries), queryFacts(snapshot.emergingQueries ?? []))
compare('Java 图谱筛选', graphFacts(javaGraph), graphFacts(filterStaticGraph('Java')))
compare('JavaScript 图谱筛选', graphFacts(javaScriptGraph), graphFacts(filterStaticGraph('JavaScript')))
compare('Java 演化窗口', javaEvolution.windows.map(item => [item.id, item.type, item.documentCount, item.companyCount, item.sourceCount]), snapshot.roleEvolutions[String(javaRole.id)].windows.map(item => [item.id, item.type, item.documentCount, item.companyCount, item.sourceCount]))
compare('Java 演化变化项', javaEvolution.changes.map(item => [item.id, item.skill, item.changeType, item.confidenceLevel, item.publishable]), snapshot.roleEvolutions[String(javaRole.id)].changes.map(item => [item.id, item.skill, item.changeType, item.confidenceLevel, item.publishable]))
for (const [code, role] of evolutionRoleEntries.slice(1)) {
  const live = evolutionValues[code]
  const frozen = snapshot.roleEvolutions?.[String(role.id)]
  if (!frozen) {
    errors.push(`${code}演化缺失`)
    continue
  }
  compare(`${code}演化窗口`, live.windows.map(item => [item.id, item.type, item.documentCount, item.companyCount, item.sourceCount]), frozen.windows.map(item => [item.id, item.type, item.documentCount, item.companyCount, item.sourceCount]))
  compare(`${code}演化变化项`, live.changes.map(item => [item.id, item.skill, item.changeType, item.confidenceLevel, item.publishable]), frozen.changes.map(item => [item.id, item.skill, item.changeType, item.confidenceLevel, item.publishable]))
}

if (javaGraph.nodes.some(node => node.type === 'SKILL' && exact(node.label) === 'javascript')) errors.push('Java 筛选错误包含 JavaScript 技能')
if (javaScriptGraph.nodes.some(node => node.type === 'SKILL' && exact(node.label) === 'java')) errors.push('JavaScript 筛选错误包含 Java 技能')

const report = {
  snapshotCode: snapshot.snapshot.code,
  snapshotSha256: sha256(snapshotSerialized),
  apiBase,
  generatedAt: new Date().toISOString(),
  passed: errors.length === 0,
  errors,
  checks
}
await mkdir(dirname(outputPath), { recursive: true })
await writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
if (errors.length) {
  errors.forEach(error => console.error(`- ${error}`))
  process.exitCode = 1
} else {
  console.log(`动态与静态快照对账通过：${snapshot.snapshot.code}`)
  console.log(`报告：${outputPath}`)
}

async function request(path) {
  const response = await fetch(`${apiBase}${path}`, { headers: { Accept: 'application/json', ...(authorization ? { Authorization: authorization } : {}) } })
  const payload = await response.json().catch(() => null)
  if (!response.ok || payload?.success === false || payload?.code !== 'OK') {
    throw new Error(`GET ${path} 失败：${payload?.code || response.status} ${payload?.message || response.statusText}`)
  }
  return payload.data
}

async function loadLocalEnvironment(file) {
  let content = ''
  try { content = await readFile(file, 'utf8') } catch { return }
  for (const line of content.split(/\r?\n/)) {
    const matched = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$/)
    if (!matched || process.env[matched[1]]) continue
    process.env[matched[1]] = matched[2].replace(/^['"]|['"]$/g, '')
  }
}
