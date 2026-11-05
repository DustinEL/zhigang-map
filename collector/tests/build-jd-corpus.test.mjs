import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  classifyTargetRole,
  cleanText,
  deduplicateRecords,
  extractEmbeddedJson,
  inferJobLevel,
  iguopinLocationIsMainland,
  mainlandCity,
  normalizeDate,
  partitionRecords,
  qualityGate,
  technicalPattern
} from '../build-jd-corpus.mjs'

const selectionPolicy = JSON.parse(await readFile(new URL('../jd-corpus-sources.json', import.meta.url), 'utf8')).selectionPolicy
const targetRolePattern = technicalPattern(selectionPolicy)

test('cleanText removes HTML while preserving readable paragraphs', () => {
  assert.equal(cleanText('<p>Python &amp; SQL</p><p>Build pipelines</p>'), 'Python & SQL\nBuild pipelines')
})

test('inferJobLevel recognizes English, Chinese, and Portuguese levels', () => {
  assert.equal(inferJobLevel('Senior Data Engineer'), '高级或管理岗')
  assert.equal(inferJobLevel('Analista de Dados Pleno'), '中级')
  assert.equal(inferJobLevel('Estagiário de Dados'), '初级或实习')
})

test('normalizeDate parses Chinese recruitment dates', () => {
  assert.equal(normalizeDate('2026年08月01日'), '2026-08-01T00:00:00+08:00')
  assert.equal(normalizeDate(1784103614), '2026-07-15T08:20:14.000Z')
})

test('mainlandCity accepts mainland locations and rejects overseas locations', () => {
  assert.equal(mainlandCity('北京-丰台区、天津-东丽区'), true)
  assert.equal(mainlandCity('安徽省淮北市'), true)
  assert.equal(mainlandCity('全国'), true)
  assert.equal(mainlandCity('圣保罗'), false)
  assert.equal(mainlandCity('利雅得、吉达'), false)
  assert.equal(mainlandCity('香港'), false)
})

test('iguopinLocationIsMainland accepts private employers when structured job locations are mainland China', () => {
  assert.equal(iguopinLocationIsMainland({
    company_info: { nature_cn: '民营企业' },
    district_list: [{ country: '000000', area_cn: '深圳' }]
  }, '深圳'), true)
  assert.equal(iguopinLocationIsMainland({
    company_info: { nature_cn: '民营企业' },
    district_list: [{ country: 'US', area_cn: '旧金山' }]
  }, '旧金山'), false)
})

test('extractEmbeddedJson reads SSR arrays without truncating escaped text', () => {
  const html = '<script>window.data={"listDetailData":[{"name":"研发[平台]","text":"包含 \\\"引号\\\""}],"total":1}</script>'
  assert.deepEqual(extractEmbeddedJson(html, '"listDetailData":', '['), [
    { name: '研发[平台]', text: '包含 "引号"' }
  ])
})

test('qualityGate never treats a short or undated record as qualified', () => {
  const result = qualityGate({
    sourceRecordKey: '1',
    jobTitle: 'Data Engineer',
    company: 'Example',
    city: 'Shanghai',
    liveAtCollection: true,
    publishedAt: null,
    sourceUrl: 'https://example.com/jobs/1',
    originalPostingUrl: null,
    sourceCountry: 'CN',
    companyCountry: 'CN',
    language: 'zh-CN',
    rawText: 'short'
  })
  assert.equal(result.passed, false)
  assert.deepEqual(result.reasons, ['缺少有效发布时间', '工作地点不是中国大陆', 'JD 正文少于 200 字符', 'JD 中文正文不足 80 个汉字'])
  assert.equal(result.humanReviewRequired, true)
})

test('target role classifier accepts website AI Agent and Java backend jobs', () => {
  const agent = classifyTargetRole({
    jobTitle: '企业微信-AI Agent开发工程师-平台框架/应用',
    category: '技术研发',
    rawText: '负责智能体任务规划、工具调用、多智能体协作和大模型应用开发。'
  }, targetRolePattern)
  const java = classifyTargetRole({
    jobTitle: '后端开发工程师',
    category: '软件开发',
    rawText: '负责 Java 后端服务，使用 Spring Boot、Spring Cloud、MyBatis 和 MySQL。'
  }, targetRolePattern)
  assert.equal(agent.relevanceStatus, 'TARGET_ROLE_MATCH')
  assert(agent.matchedRoleCodes.includes('AI_AGENT_ENGINEER'))
  assert.equal(java.relevanceStatus, 'TARGET_ROLE_MATCH')
  assert(java.matchedRoleCodes.includes('JAVA_ENGINEER'))
})

test('target role classifier rejects AI product operations and non-computer engineering jobs', () => {
  const product = classifyTargetRole({
    jobTitle: '美团闪购-AI产品经理',
    category: '产品',
    rawText: '负责大模型、RAG 和智能体产品规划。'
  }, targetRolePattern)
  const mechanical = classifyTargetRole({
    jobTitle: '机械设计工程师',
    category: '制造',
    rawText: '负责机械结构设计和生产工艺改进。'
  }, targetRolePattern)
  const frontend = classifyTargetRole({
    jobTitle: '前端开发工程师',
    category: '软件开发',
    rawText: '使用 Vue 开发页面，并对接 Java、RAG 和 AI Agent 后端接口。'
  }, targetRolePattern)
  assert.equal(product.relevanceStatus, 'REJECTED_NON_TARGET')
  assert.equal(mechanical.relevanceStatus, 'REJECTED_NON_TARGET')
  assert.equal(frontend.relevanceStatus, 'REJECTED_NON_TARGET')
})

test('exact duplicates retain the current tier-one record', () => {
  const shared = { contentHash: 'a'.repeat(64), rawText: 'x'.repeat(300), simHash: '0'.repeat(16) }
  const historical = { ...shared, recordId: 'historical', sourceId: 'archive', sourceTier: 'TIER_2', timeScope: 'HISTORICAL' }
  const current = { ...shared, recordId: 'current', sourceId: 'official', sourceTier: 'TIER_1', timeScope: 'CURRENT' }
  const result = deduplicateRecords([historical, current])
  assert.equal(result.unique[0].recordId, 'current')
  assert.equal(result.exactDuplicates[0].duplicateRecordId, 'historical')
})

test('near-duplicate relations keep connected records in one split', () => {
  const records = ['a', 'b', 'c'].map(recordId => ({ recordId }))
  const splits = partitionRecords(records, [{ leftRecordId: 'b', rightRecordId: 'a' }])
  const containingA = Object.entries(splits).find(([, values]) => values.some(value => value.recordId === 'a'))[0]
  const containingB = Object.entries(splits).find(([, values]) => values.some(value => value.recordId === 'b'))[0]
  assert.equal(containingA, containingB)
})
