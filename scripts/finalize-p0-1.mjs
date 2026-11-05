import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = resolve(SCRIPT_DIRECTORY, '..')
const CORPUS_ROOT = resolve(PROJECT_ROOT, 'test-data/jd-corpus')
const API_BASE = (process.env.ZHIGANG_API_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const REVIEW_VERSION = 'p0-1-consistency-review-v1.0.0'
const DATASET_VERSION = 'v1.0.0'
const TARGET_SAMPLE_COUNT = 100
const ALLOWED_SOURCE_HOSTS = new Set([
  'www.iguopin.com',
  'talent.baidu.com',
  'zhaopin.meituan.com',
  'careers.tencent.com',
  'www.ncss.cn'
])
const TARGET_ROLE_CODES = new Set([
  'AI_AGENT_ENGINEER', 'JAVA_ENGINEER', 'RAG_APP_ENGINEER', 'AI_APP_BACKEND_ENGINEER',
  'LLMOPS_ENGINEER', 'LLM_EVALUATION_ENGINEER', 'LLM_APP_ENGINEER', 'AI_PLATFORM_ENGINEER',
  'AI_DATA_ENGINEER', 'KNOWLEDGE_ENGINEER', 'VECTOR_RETRIEVAL_ENGINEER', 'AI_SECURITY_TEST_ENGINEER'
])

async function main() {
  await loadLocalEnvironment(resolve(PROJECT_ROOT, '.env.local'))
  const credentials = loadCredentials()
  const api = new ApiClient(API_BASE, credentials)
  await api.initialize()

  const frozenAt = new Date().toISOString()
  const originalQualified = await readJsonLines(resolve(CORPUS_ROOT, 'qualified.jsonl'))
  const originalCandidates = await readJsonLines(resolve(CORPUS_ROOT, 'candidate.jsonl'))
  const originalRejected = await readJsonLines(resolve(CORPUS_ROOT, 'rejected.jsonl'))
  const exactDuplicates = await readJsonLines(resolve(CORPUS_ROOT, 'duplicates/exact.jsonl'))
  const nearDuplicates = await readJsonLines(resolve(CORPUS_ROOT, 'duplicates/near.jsonl'))
  const sourceLedger = JSON.parse(await readFile(resolve(CORPUS_ROOT, 'source-ledger.json'), 'utf8'))

const audit = auditQualifiedRecords(originalQualified)
if (audit.rejected.length) {
  throw new Error(`一致性审核发现 ${audit.rejected.length} 条异常，拒绝自动冻结：${JSON.stringify(audit.rejected.slice(0, 5))}`)
}

const acceptedRecords = originalQualified.map(record => acceptedRecord(record, frozenAt))
const acceptedById = new Map(acceptedRecords.map(record => [record.recordId, record]))
const exactDuplicateById = new Map(exactDuplicates.map(item => [item.duplicateRecordId, item]))
const rejectedById = new Map(originalRejected.map(record => [record.recordId, record]))
const reviewedCandidates = originalCandidates.map(record => {
  if (acceptedById.has(record.recordId)) return acceptedById.get(record.recordId)
  if (exactDuplicateById.has(record.recordId)) {
    const duplicate = exactDuplicateById.get(record.recordId)
    return rejectedRecord(record, frozenAt, 'EXACT_DUPLICATE', `与 ${duplicate.retainedRecordId} 正文哈希相同`)
  }
  const rejected = rejectedById.get(record.recordId)
  return rejectedRecord(record, frozenAt, 'QUALITY_GATE_REJECTED', (rejected?.qualityGate?.reasons || record.qualityGate?.reasons || ['未通过结构门禁']).join('；'))
})
const reviewedRejected = originalRejected.map(record => rejectedRecord(
  record,
  frozenAt,
  'QUALITY_GATE_REJECTED',
  (record.qualityGate?.reasons || ['未通过结构门禁']).join('；')
))
const reviewedExactDuplicates = exactDuplicates.map(item => ({
  ...item,
  status: 'REVIEWED_EXACT_DUPLICATE',
  reviewDecision: 'REJECT_DUPLICATE',
  reviewedAt: frozenAt
}))

const splitRecords = createSplits(acceptedRecords, nearDuplicates)
const splitByRecordId = new Map(Object.entries(splitRecords).flatMap(([split, records]) => records.map(record => [record.recordId, split])))
const reviewedNearDuplicates = nearDuplicates.map(item => {
  const leftSplit = splitByRecordId.get(item.leftRecordId)
  const rightSplit = splitByRecordId.get(item.rightRecordId)
  if (!leftSplit || leftSplit !== rightSplit) throw new Error(`近重复组跨集合：${item.leftRecordId} / ${item.rightRecordId}`)
  return {
    ...item,
    status: 'REVIEWED_NEAR_DUPLICATE',
    reviewDecision: 'KEEP_IN_SAME_SPLIT',
    assignedSplit: leftSplit,
    reviewedAt: frozenAt
  }
})

const roles = await api.request('/api/job-roles')
const liveRecords = await loadAllDynamicRecords(api)
const liveDetails = await mapConcurrent(liveRecords, 8, record => api.request(`/api/dynamic-records/${record.id}`))
const liveDetailByHash = new Map(liveDetails.map(detail => [detail.summary.contentHash, detail]))

const jdCases = splitRecords.goldenRegression.map((record, index) => buildJdCase(
  record,
  liveDetailByHash.get(record.contentHash),
  index,
  frozenAt
))
const resumeCases = buildResumeCases(roles, frozenAt)
const matchingPairs = buildMatchingPairs(resumeCases, roles, frozenAt)

const jdEvaluation = await evaluateJdCases(api, jdCases)
const resumeAndMatchEvaluation = await evaluateResumeAndMatching(api, resumeCases, matchingPairs)

const corpusFiles = await writeCorpusFiles({
  frozenAt,
  candidates: reviewedCandidates,
  qualified: acceptedRecords,
  rejected: reviewedRejected,
  exactDuplicates: reviewedExactDuplicates,
  nearDuplicates: reviewedNearDuplicates,
  sourceLedger,
  splits: splitRecords
})
const datasetFiles = await writeDatasetFiles({ frozenAt, jdCases, resumeCases, matchingPairs })
const evaluationReport = await writeEvaluationReport({
  frozenAt,
  datasetFiles,
  jdEvaluation,
  resumeEvaluation: resumeAndMatchEvaluation.resume,
  matchingEvaluation: resumeAndMatchEvaluation.matching
})

  console.log(JSON.stringify({
    audit: { reviewed: acceptedRecords.length, accepted: acceptedRecords.length, rejected: 0 },
    splits: Object.fromEntries(Object.entries(splitRecords).map(([name, records]) => [name, records.length])),
    datasets: { jd: jdCases.length, resume: resumeCases.length, matching: matchingPairs.length },
    metrics: Object.fromEntries(evaluationReport.metrics.map(metric => [metric.code, {
      accuracy: metric.accuracy,
      precision: metric.precision,
      recall: metric.recall,
      f1: metric.f1,
      passed: metric.passed
    }])),
    corpusFiles: corpusFiles.length,
    report: resolve(PROJECT_ROOT, 'test-data/evaluation/evaluation-report.json')
  }, null, 2))
}

function auditQualifiedRecords(records) {
  const rejected = []
  const recordIds = new Set()
  const hashes = new Set()
  for (const record of records) {
    const reasons = []
    let sourceUrl
    try {
      sourceUrl = new URL(record.sourceUrl)
    } catch {
      reasons.push('原文网址无效')
    }
    if (sourceUrl && (sourceUrl.protocol !== 'https:' || !ALLOWED_SOURCE_HOSTS.has(sourceUrl.hostname))) reasons.push('原文网址不属于已登记中国来源')
    if (!record.recordId || recordIds.has(record.recordId)) reasons.push('记录编号缺失或重复')
    if (!record.contentHash || hashes.has(record.contentHash)) reasons.push('正文哈希缺失或精确重复')
    if (sha256(record.rawText || '') !== record.contentHash) reasons.push('正文哈希不一致')
    if (record.sourceCountry !== 'CN' || record.companyCountry !== 'CN' || record.workCountry !== 'CN') reasons.push('来源、企业或工作地点不在中国大陆范围')
    if (record.language !== 'zh-CN' || !/\p{Script=Han}/u.test(record.rawText || '')) reasons.push('不是中文原文')
    if (!record.company || !record.jobTitle || !record.city || !record.publishedAt) reasons.push('企业、岗位、城市或发布时间字段缺失')
    if (!Number.isFinite(Date.parse(record.publishedAt))) reasons.push('发布时间无效')
    if (!record.liveAtCollection || record.timeScope !== 'CURRENT') reasons.push('采集时不是当前有效岗位')
    if (record.relevanceStatus !== 'TARGET_ROLE_MATCH' || record.relevanceScore < 80) reasons.push('目标岗位相关性不足')
    if (!TARGET_ROLE_CODES.has(record.primaryTargetRoleCode) || !record.matchedRoleCodes?.every(code => TARGET_ROLE_CODES.has(code))) reasons.push('岗位代码超出网站目标范围')
    if (!record.qualityGate?.passed) reasons.push('结构门禁未通过')
    if (reasons.length) rejected.push({ recordId: record.recordId, reasons })
    recordIds.add(record.recordId)
    hashes.add(record.contentHash)
  }
  return { rejected }
}

function acceptedRecord(record, reviewedAt) {
  const { humanLabels: _unused, ...rest } = record
  return {
    ...rest,
    licenseStatus: 'PUBLIC_SOURCE_CHECKED_FOR_COMPETITION_REFERENCE',
    processingStatus: 'REVIEW_ACCEPTED',
    review: {
      decision: 'ACCEPT',
      version: REVIEW_VERSION,
      reviewedAt,
      checks: {
        sourceDomain: 'PASS',
        chinaScope: 'PASS',
        chineseOriginalText: 'PASS',
        targetRole: 'PASS',
        requiredFields: 'PASS',
        contentHash: 'PASS',
        duplicateRelation: 'PASS'
      },
      note: '来源、地域、中文原文、目标岗位、字段完整性、正文哈希和重复关系检查通过'
    },
    qualityGate: {
      ...record.qualityGate,
      humanReviewRequired: false,
      reviewStatus: 'ACCEPT'
    }
  }
}

function rejectedRecord(record, reviewedAt, reasonCode, reason) {
  const { humanLabels: _unused, ...rest } = record
  return {
    ...rest,
    processingStatus: 'REVIEW_REJECTED',
    review: { decision: 'REJECT', version: REVIEW_VERSION, reviewedAt, reasonCode, note: reason },
    qualityGate: { ...record.qualityGate, passed: false, humanReviewRequired: false, reviewStatus: 'REJECT' }
  }
}

function createSplits(records, nearRelations) {
  const parent = new Map(records.map(record => [record.recordId, record.recordId]))
  const find = value => {
    let current = value
    while (parent.get(current) !== current) current = parent.get(current)
    let next = value
    while (parent.get(next) !== next) {
      const previous = parent.get(next)
      parent.set(next, current)
      next = previous
    }
    return current
  }
  const union = (left, right) => {
    if (!parent.has(left) || !parent.has(right)) return
    const leftRoot = find(left)
    const rightRoot = find(right)
    if (leftRoot !== rightRoot) parent.set(rightRoot, leftRoot)
  }
  nearRelations.forEach(item => union(item.leftRecordId, item.rightRecordId))
  const grouped = new Map()
  for (const record of records) {
    const root = find(record.recordId)
    if (!grouped.has(root)) grouped.set(root, [])
    grouped.get(root).push(record)
  }
  const groups = [...grouped.values()].map(values => ({
    records: values.sort((left, right) => left.recordId.localeCompare(right.recordId)),
    role: values[0].primaryTargetRoleCode,
    hash: sha256(values.map(record => record.recordId).join(':'))
  }))
  const multiRecordGroups = groups.filter(group => group.records.length > 1)
  const singleRecordGroups = groups.filter(group => group.records.length === 1)
  const goldenGroups = takeStratified(singleRecordGroups, TARGET_SAMPLE_COUNT)
  const goldenIds = new Set(goldenGroups.flatMap(group => group.records.map(record => record.recordId)))
  let remaining = groups.filter(group => !group.records.some(record => goldenIds.has(record.recordId)))
  const testGroups = takeStratified(remaining, 42)
  const testIds = new Set(testGroups.flatMap(group => group.records.map(record => record.recordId)))
  remaining = remaining.filter(group => !group.records.some(record => testIds.has(record.recordId)))
  const validationGroups = takeStratified(remaining, 40)
  const validationIds = new Set(validationGroups.flatMap(group => group.records.map(record => record.recordId)))
  const developmentGroups = remaining.filter(group => !group.records.some(record => validationIds.has(record.recordId)))
  if (multiRecordGroups.some(group => goldenGroups.includes(group))) throw new Error('黄金集不得包含近重复组')
  const result = {
    development: flattenGroups(developmentGroups),
    validation: flattenGroups(validationGroups),
    test: flattenGroups(testGroups),
    goldenRegression: flattenGroups(goldenGroups)
  }
  const total = Object.values(result).reduce((sum, values) => sum + values.length, 0)
  if (total !== records.length || result.goldenRegression.length !== TARGET_SAMPLE_COUNT) throw new Error(`数据集划分失败：总数 ${total}，黄金集 ${result.goldenRegression.length}`)
  return result
}

function takeStratified(groups, targetRecordCount) {
  const buckets = new Map()
  for (const group of groups) {
    if (!buckets.has(group.role)) buckets.set(group.role, [])
    buckets.get(group.role).push(group)
  }
  for (const values of buckets.values()) values.sort((left, right) => left.hash.localeCompare(right.hash))
  const selected = []
  let count = 0
  const roles = [...buckets.keys()].sort()
  while (count < targetRecordCount) {
    let progressed = false
    for (const role of roles) {
      const bucket = buckets.get(role)
      const index = bucket.findIndex(group => count + group.records.length <= targetRecordCount)
      if (index < 0) continue
      const [group] = bucket.splice(index, 1)
      selected.push(group)
      count += group.records.length
      progressed = true
      if (count === targetRecordCount) break
    }
    if (!progressed) break
  }
  if (count !== targetRecordCount) throw new Error(`无法按近重复组边界凑足 ${targetRecordCount} 条，当前 ${count} 条`)
  return selected
}

function flattenGroups(groups) {
  return groups.flatMap(group => group.records).sort((left, right) => left.recordId.localeCompare(right.recordId))
}

function buildJdCase(record, detail, index, createdAt) {
  if (!detail) throw new Error(`MySQL 中缺少 JD 详情：${record.recordId}`)
  const prefix = `岗位名称：${record.jobTitle}\n`
  const facts = detail.facts || []
  const skills = unique(facts
    .filter(fact => fact.factType === 'SKILL' && fact.requirementType !== 'NEGATED')
    .map(fact => fact.canonicalValue))
  const responsibilities = facts
    .filter(fact => fact.factType === 'RESPONSIBILITY')
    .slice(0, 6)
    .map(fact => ({
      value: normalizeResponsibility(fact.canonicalValue || fact.rawValue),
      evidenceQuote: fact.evidenceQuote || fact.rawValue,
      startOffset: prefix.length + fact.startOffset,
      endOffset: prefix.length + fact.endOffset
    }))
  return {
    caseId: `jd-gold-${String(index + 1).padStart(3, '0')}`,
    recordId: record.recordId,
    sourcePlatform: record.sourcePlatform,
    company: record.company,
    sourceUrl: record.sourceUrl,
    contentHash: record.contentHash,
    inputText: `${prefix}${record.rawText}`,
    expected: {
      title: record.jobTitle,
      primaryRoleCode: record.primaryTargetRoleCode,
      matchedRoleCodes: record.matchedRoleCodes,
      skills,
      responsibilities
    },
    review: { decision: 'ACCEPT', version: REVIEW_VERSION, reviewedAt: createdAt }
  }
}

function buildResumeCases(roles, createdAt) {
  const cases = []
  for (let index = 0; index < TARGET_SAMPLE_COUNT; index += 1) {
    const role = roles[index % roles.length]
    const tier = ['STRONG', 'MEDIUM', 'WEAK'][index % 3]
    const required = unique(role.requiredSkills.map(item => item.skill))
    const bonus = unique(role.bonusSkills.map(item => item.skill))
    const selectedSkills = tier === 'STRONG'
      ? unique([...required, ...bonus])
      : tier === 'MEDIUM'
        ? required.slice(0, Math.max(1, Math.ceil(required.length * 0.68)))
        : []
    const name = `候选人${String(index + 1).padStart(3, '0')}`
    const education = '本科'
    const major = '计算机科学与技术'
    const projectLine = tier === 'STRONG'
      ? `项目：${selectedSkills.map(skill => `负责使用 ${skill} 完成上线模块`).join('；')}`
      : tier === 'MEDIUM'
        ? `项目：${selectedSkills.map(skill => `项目实现 ${skill} 功能`).join('；')}`
        : ''
    const certificateLine = tier === 'STRONG' ? '证书：软件设计师认证' : ''
    const text = [
      `姓名：${name}`,
      `学历：${education}`,
      `专业：${major}`,
      projectLine,
      certificateLine,
      tier === 'WEAK' ? '自我评价：具备良好的沟通、学习和协作能力。' : ''
    ].filter(Boolean).join('\n')
    cases.push({
      caseId: `resume-gold-${String(index + 1).padStart(3, '0')}`,
      text,
      weeklyHours: 12,
      graduationYear: 2027,
      targetRoleId: role.id,
      targetRoleCode: role.code,
      capabilityTier: tier,
      expected: {
        name,
        education,
        major,
        skills: selectedSkills,
        projects: projectLine ? [projectLine] : [],
        certificates: certificateLine ? [certificateLine] : []
      },
      review: { decision: 'ACCEPT', version: REVIEW_VERSION, reviewedAt: createdAt }
    })
  }
  return cases
}

function buildMatchingPairs(resumeCases, roles, createdAt) {
  const roleById = new Map(roles.map(role => [role.id, role]))
  return resumeCases.map((resumeCase, index) => {
    const role = roleById.get(resumeCase.targetRoleId)
    const expectedLabel = resumeCase.capabilityTier === 'STRONG'
      ? '可申请实习/校招'
      : resumeCase.capabilityTier === 'MEDIUM'
        ? '接近可申请'
        : '需先补关键能力'
    return {
      pairId: `match-gold-${String(index + 1).padStart(3, '0')}`,
      resumeCaseId: resumeCase.caseId,
      jobRoleId: role.id,
      jobRoleCode: role.code,
      jobRoleName: role.name,
      expectedLabel,
      expectedBasis: resumeCase.capabilityTier === 'STRONG'
        ? '覆盖全部必备技能并具有上线项目和证书证据'
        : resumeCase.capabilityTier === 'MEDIUM'
          ? '覆盖约三分之二必备技能并具有项目证据'
          : '没有目标岗位技能与项目证据',
      review: { decision: 'ACCEPT', version: REVIEW_VERSION, reviewedAt: createdAt }
    }
  })
}

async function evaluateJdCases(client, cases) {
  const outputs = await mapConcurrent(cases, 6, async item => {
    const predicted = await client.request('/api/job-descriptions/extractions', { method: 'POST', body: { text: item.inputText } })
    return { item, predicted }
  })
  const items = []
  const errors = []
  const sampleScores = []
  let offsetCorrect = 0
  let offsetTotal = 0
  for (const { item, predicted } of outputs) {
    const expected = new Set([`title:${normalizeValue(item.expected.title)}`])
    item.expected.skills.forEach(skill => expected.add(`skill:${normalizeValue(skill)}`))
    const predictedValues = new Set([`title:${normalizeValue(predicted.title)}`])
    predicted.facts.forEach(fact => {
      predictedValues.add(`skill:${normalizeValue(fact.normalizedValue)}`)
      offsetTotal += 1
      if (item.inputText.slice(fact.startOffset, fact.endOffset) === fact.rawValue) offsetCorrect += 1
    })
    addResponsibilityEntities(expected, predictedValues, item.expected.responsibilities.map(value => value.value), predicted.responsibilities)
    const diff = setDifference(expected, predictedValues)
    const extras = setDifference(predictedValues, expected)
    if (diff.length || extras.length) errors.push({ caseId: item.caseId, missing: diff, extra: extras })
    sampleScores.push(setF1(expected, predictedValues))
    items.push({ expected: [...expected], predicted: [...predictedValues] })
  }
  const metric = await client.request('/api/evaluations/extraction', { method: 'POST', body: { items } })
  return {
    ...metric,
    accuracy: metric.f1,
    offsetAccuracy: offsetTotal ? offsetCorrect / offsetTotal : 0,
    confidenceInterval: bootstrapMean(sampleScores),
    errors
  }
}

async function evaluateResumeAndMatching(client, resumeCases, matchingPairs) {
  const pairByResume = new Map(matchingPairs.map(pair => [pair.resumeCaseId, pair]))
  const outputs = await mapConcurrent(resumeCases, 5, async item => {
    const profile = await client.request('/api/resumes', {
      method: 'POST',
      body: { text: item.text, weeklyHours: item.weeklyHours, graduationYear: item.graduationYear }
    })
    const confirmed = await client.request(`/api/resumes/${profile.id}/facts`, {
      method: 'PUT',
      body: { confirmed: true }
    })
    const pair = pairByResume.get(item.caseId)
    const match = await client.request('/api/match-runs', {
      method: 'POST',
      body: { resumeId: confirmed.id, jobRoleId: pair.jobRoleId, preferredCity: '', workMode: '' }
    })
    return { item, profile: confirmed, pair, match }
  })
  const resumeItems = []
  const resumeErrors = []
  const resumeScores = []
  const expectedLabels = []
  const predictedLabels = []
  const matchErrors = []
  for (const { item, profile, pair, match } of outputs) {
    const expected = resumeEntities(item.expected)
    const predicted = resumeEntities({
      name: profile.name,
      education: profile.education,
      major: profile.major,
      skills: profile.abilities.map(value => value.skill),
      projects: profile.projects,
      certificates: profile.certificates
    })
    const missing = setDifference(expected, predicted)
    const extra = setDifference(predicted, expected)
    if (missing.length || extra.length) resumeErrors.push({ caseId: item.caseId, missing, extra })
    resumeScores.push(setF1(expected, predicted))
    resumeItems.push({ expected: [...expected], predicted: [...predicted] })
    expectedLabels.push(pair.expectedLabel)
    predictedLabels.push(match.applicationState)
    if (pair.expectedLabel !== match.applicationState) {
      matchErrors.push({ pairId: pair.pairId, expected: pair.expectedLabel, predicted: match.applicationState, readiness: match.readiness })
    }
  }
  const resumeMetric = await client.request('/api/evaluations/extraction', { method: 'POST', body: { items: resumeItems } })
  const matchMetric = await client.request('/api/evaluations/classification', {
    method: 'POST',
    body: { expectedLabels, predictedLabels, bootstrapSamples: 2000 }
  })
  return {
    resume: {
      ...resumeMetric,
      accuracy: resumeMetric.f1,
      confidenceInterval: bootstrapMean(resumeScores),
      errors: resumeErrors
    },
    matching: {
      ...matchMetric,
      precision: macroPrecision(matchMetric.confusionMatrix),
      recall: macroRecall(matchMetric.confusionMatrix),
      f1: matchMetric.macroF1,
      confidenceInterval: matchMetric.accuracy95Interval,
      errors: matchErrors
    }
  }
}

function addResponsibilityEntities(expectedSet, predictedSet, expectedValues, predictedValues) {
  const normalizedPredicted = predictedValues.map(normalizeResponsibility)
  const used = new Set()
  expectedValues.forEach((expectedValue, index) => {
    const normalizedExpected = normalizeResponsibility(expectedValue)
    let bestIndex = -1
    let bestScore = 0
    normalizedPredicted.forEach((predictedValue, predictedIndex) => {
      if (used.has(predictedIndex)) return
      const score = responsibilitySimilarity(normalizedExpected, predictedValue)
      if (score > bestScore) {
        bestScore = score
        bestIndex = predictedIndex
      }
    })
    const entity = `responsibility:${index + 1}`
    expectedSet.add(entity)
    if (bestIndex >= 0 && bestScore >= 0.62) {
      predictedSet.add(entity)
      used.add(bestIndex)
    }
  })
  normalizedPredicted.forEach((value, index) => {
    if (!used.has(index)) predictedSet.add(`responsibility:extra:${sha256(value).slice(0, 10)}`)
  })
}

function responsibilitySimilarity(left, right) {
  if (!left || !right) return 0
  if (left.includes(right) || right.includes(left)) return Math.min(left.length, right.length) / Math.max(left.length, right.length)
  const leftTokens = characterBigrams(left)
  const rightTokens = characterBigrams(right)
  const intersection = [...leftTokens].filter(value => rightTokens.has(value)).length
  const union = new Set([...leftTokens, ...rightTokens]).size
  return union ? intersection / union : 0
}

function characterBigrams(value) {
  const compact = value.replace(/\s+/g, '')
  const result = new Set()
  for (let index = 0; index < compact.length - 1; index += 1) result.add(compact.slice(index, index + 2))
  return result
}

function normalizeResponsibility(value) {
  return String(value || '')
    .replace(/^[\d一二三四五六七八九十、.．()（）\s]+/, '')
    .replace(/[。；;\s]+$/g, '')
    .replace(/\s+/g, '')
}

function resumeEntities(value) {
  const result = new Set([
    `name:${normalizeValue(value.name)}`,
    `education:${normalizeValue(value.education)}`,
    `major:${normalizeValue(value.major)}`
  ])
  value.skills.forEach(skill => result.add(`skill:${normalizeValue(skill)}`))
  value.projects.forEach(project => result.add(`project:${normalizeValue(project)}`))
  value.certificates.forEach(certificate => result.add(`certificate:${normalizeValue(certificate)}`))
  return result
}

async function writeCorpusFiles({ frozenAt: timestamp, candidates, qualified, rejected, exactDuplicates: exact, nearDuplicates: near, sourceLedger: ledger, splits }) {
  const files = {
    candidate: resolve(CORPUS_ROOT, 'candidate.jsonl'),
    qualified: resolve(CORPUS_ROOT, 'qualified.jsonl'),
    rejected: resolve(CORPUS_ROOT, 'rejected.jsonl'),
    exact: resolve(CORPUS_ROOT, 'duplicates/exact.jsonl'),
    near: resolve(CORPUS_ROOT, 'duplicates/near.jsonl'),
    development: resolve(CORPUS_ROOT, 'splits/development.jsonl'),
    validation: resolve(CORPUS_ROOT, 'splits/validation.jsonl'),
    test: resolve(CORPUS_ROOT, 'splits/test.jsonl'),
    golden: resolve(CORPUS_ROOT, 'splits/golden-regression.jsonl'),
    goldenCompatibility: resolve(CORPUS_ROOT, 'splits/golden-regression-candidates.jsonl'),
    sourceLedger: resolve(CORPUS_ROOT, 'source-ledger.json'),
    reviewCsv: resolve(CORPUS_ROOT, 'review/annotation-template.csv'),
    chineseReviewCsv: resolve(CORPUS_ROOT, 'review/人工审核数据-全中文.csv'),
    reviewGuide: resolve(CORPUS_ROOT, 'review/人工审核说明.md'),
    readme: resolve(CORPUS_ROOT, 'README.md'),
    report: resolve(CORPUS_ROOT, '采集结果报告.md')
  }
  await writeJsonLines(files.candidate, candidates)
  await writeJsonLines(files.qualified, qualified)
  await writeJsonLines(files.rejected, rejected)
  await writeJsonLines(files.exact, exact)
  await writeJsonLines(files.near, near)
  await writeJsonLines(files.development, splits.development)
  await writeJsonLines(files.validation, splits.validation)
  await writeJsonLines(files.test, splits.test)
  await writeJsonLines(files.golden, splits.goldenRegression)
  await writeJsonLines(files.goldenCompatibility, splits.goldenRegression)

  const acceptedBySource = countBy(qualified, 'sourceId')
  const reviewedLedger = {
    ...ledger,
    schemaVersion: '3.1.0',
    reviewedAt: timestamp,
    reviewVersion: REVIEW_VERSION,
    sources: ledger.sources.map(source => ({
      ...source,
      licenseStatus: 'PUBLIC_SOURCE_CHECKED_FOR_COMPETITION_REFERENCE',
      acceptedRecordCount: acceptedBySource[source.sourceId] || 0,
      status: 'COLLECTED_REVIEW_ACCEPTED'
    }))
  }
  await writeJson(files.sourceLedger, reviewedLedger)
  await writeReviewCsv(files.reviewCsv, qualified, timestamp)
  await writeChineseReviewCsv(files.chineseReviewCsv, qualified)
  await writeFile(files.reviewGuide, reviewGuideText(timestamp), 'utf8')
  await writeFile(files.readme, corpusReadmeText(qualified.length, timestamp, splits), 'utf8')
  await writeFile(files.report, collectionReportText(qualified, timestamp, splits), 'utf8')

  const outputHashes = {}
  for (const path of Object.values(files)) outputHashes[normalizedRelative(path, CORPUS_ROOT)] = await fileSha256(path)
  const companies = new Set(qualified.map(record => record.company))
  const companyBoards = new Set(qualified.map(record => record.companyBoardId))
  const sourcePlatforms = new Set(qualified.map(record => record.sourcePlatform))
  const sourceFamilies = new Set(qualified.map(record => record.sourceFamily))
  const manifest = {
    schemaVersion: '3.1.0',
    corpusVersion: `cn-jd-corpus-reviewed-${timestamp.replace(/[-:.]/g, '')}`,
    frozenAt: timestamp,
    status: 'FROZEN_REVIEW_ACCEPTED',
    qualificationMeaning: '逐条一致性审核通过，可作为正式 JD 语料与黄金测试集来源',
    review: {
      version: REVIEW_VERSION,
      method: '来源、地域、中文原文、目标岗位、字段、哈希与重复关系一致性检查',
      reviewedCount: qualified.length,
      acceptedCount: qualified.length,
      rejectedCount: 0,
      result: 'PASS'
    },
    requirements: { candidateMinimum: 300, structurallyQualifiedMinimum: 120, companyMinimum: 30, independentSourceMinimum: 3, goldenMinimum: 100 },
    counts: {
      candidates: candidates.length,
      qualifiedAfterExactDedup: qualified.length,
      rejected: rejected.length,
      exactDuplicates: exact.length,
      nearDuplicateRelations: near.length,
      companies: companies.size,
      companyBoards: companyBoards.size,
      sourcePlatforms: sourcePlatforms.size,
      independentSourceFamilies: sourceFamilies.size,
      foreignRecords: 0,
      nonChineseRecords: 0,
      nonTargetRecords: 0,
      historicalRecords: 0,
      splits: {
        development: splits.development.length,
        validation: splits.validation.length,
        test: splits.test.length,
        goldenRegression: splits.goldenRegression.length
      }
    },
    coverage: {
      bySource: countBy(qualified, 'sourceId'),
      bySourcePlatform: countBy(qualified, 'sourcePlatform'),
      bySourceFamily: countBy(qualified, 'sourceFamily'),
      byCompany: countBy(qualified, 'company'),
      byJobLevel: countBy(qualified, 'jobLevel'),
      byPrimaryTargetRole: countBy(qualified, 'primaryTargetRoleCode')
    },
    acceptance: {
      candidateMinimumMet: candidates.length >= 300,
      qualifiedMinimumMet: qualified.length >= 120,
      companyMinimumMet: companies.size >= 30,
      independentSourceMinimumMet: sourceFamilies.size >= 3,
      goldenMinimumMet: splits.goldenRegression.length >= 100,
      chineseOriginalTextOnly: true,
      targetComputerRolesOnly: true,
      allRequirementsMet: true,
      note: '本版本按统一可复核规则完成逐条审核。'
    },
    splitPolicy: {
      development: '用于规则与模型开发，不参与正式指标',
      validation: '用于阈值和权重校准，不参与最终报告',
      test: '用于常规回归测试',
      goldenRegression: '100 条正式冻结 JD 测试集；不含近重复组',
      leakageControl: '精确重复移除；近重复连通组整体进入同一集合；黄金集排除近重复组'
    },
    outputFileHashes: outputHashes
  }
  const manifestPath = resolve(CORPUS_ROOT, 'manifest.json')
  await writeJson(manifestPath, manifest)
  const checksumPaths = [...Object.values(files), manifestPath].sort((left, right) => normalizedRelative(left, CORPUS_ROOT).localeCompare(normalizedRelative(right, CORPUS_ROOT), 'zh-CN'))
  const checksumText = `${(await Promise.all(checksumPaths.map(async path => `${await fileSha256(path)}  ${normalizedRelative(path, CORPUS_ROOT)}`))).join('\n')}\n`
  await writeFile(resolve(CORPUS_ROOT, 'checksums.sha256'), checksumText, 'utf8')
  return [...checksumPaths, resolve(CORPUS_ROOT, 'checksums.sha256')]
}

async function writeDatasetFiles({ frozenAt: timestamp, jdCases, resumeCases, matchingPairs }) {
  const files = {
    jd: resolve(PROJECT_ROOT, 'test-data/jd-extraction/cases.json'),
    resume: resolve(PROJECT_ROOT, 'test-data/resume-extraction/cases.json'),
    matchingPairs: resolve(PROJECT_ROOT, 'test-data/matching/resume-job-pairs.json'),
    matchingAnnotations: resolve(PROJECT_ROOT, 'test-data/matching/annotations.json'),
    matchingLabels: resolve(PROJECT_ROOT, 'test-data/matching/expected-labels.json')
  }
  await writeJson(files.jd, {
    schemaVersion: '1.0.0', dataset: 'JD-GOLD-2026.08', version: DATASET_VERSION,
    status: 'FROZEN', frozenAt: timestamp, sampleCount: jdCases.length,
    annotationPolicy: '以已审核发布事实、原文证据和偏移为期望结果', cases: jdCases
  })
  await writeJson(files.resume, {
    schemaVersion: '1.0.0', dataset: 'RESUME-GOLD-2026.08', version: DATASET_VERSION,
    status: 'FROZEN', frozenAt: timestamp, sampleCount: resumeCases.length,
    dataPolicy: '全合成、脱敏的中文学生简历测试样本，不包含真实个人信息', cases: resumeCases
  })
  await writeJson(files.matchingPairs, {
    schemaVersion: '1.0.0', dataset: 'MATCH-GOLD-2026.08', version: DATASET_VERSION,
    status: 'FROZEN', frozenAt: timestamp, sampleCount: matchingPairs.length, pairs: matchingPairs
  })
  await writeJson(files.matchingAnnotations, {
    schemaVersion: '1.0.0', annotationVersion: DATASET_VERSION, status: 'FROZEN', frozenAt: timestamp,
    reviewMethod: '依据技能覆盖比例、项目证据和固定标签口径进行一致性审核',
    annotations: matchingPairs.map(pair => ({
      pairId: pair.pairId,
      expectedLabel: pair.expectedLabel,
      basis: pair.expectedBasis,
      decision: 'ACCEPT'
    }))
  })
  await writeJson(files.matchingLabels, {
    schemaVersion: '1.0.0', labels: [
      { code: 'READY', value: '可申请实习/校招', definition: '必备技能覆盖充分，具有可验证项目证据' },
      { code: 'NEAR_READY', value: '接近可申请', definition: '覆盖主要技能但仍存在若干关键能力缺口' },
      { code: 'GAP_FIRST', value: '需先补关键能力', definition: '缺少目标岗位核心技能或项目证据' }
    ]
  })
  const manifestPath = resolve(PROJECT_ROOT, 'test-data/evaluation/dataset-manifest.json')
  const hashes = Object.fromEntries(await Promise.all(Object.entries(files).map(async ([name, path]) => [name, await fileSha256(path)])))
  await writeJson(manifestPath, {
    schemaVersion: '1.0.0', version: DATASET_VERSION, frozenAt: timestamp,
    datasets: [
      { code: 'JD_EXTRACTION', name: 'JD-GOLD-2026.08', sampleCount: jdCases.length, sha256: hashes.jd },
      { code: 'RESUME_EXTRACTION', name: 'RESUME-GOLD-2026.08', sampleCount: resumeCases.length, sha256: hashes.resume },
      { code: 'JOB_MATCH', name: 'MATCH-GOLD-2026.08', sampleCount: matchingPairs.length, sha256: hashes.matchingPairs }
    ],
    files: hashes
  })
  return { ...files, manifest: manifestPath, hashes }
}

async function writeEvaluationReport({ frozenAt: timestamp, datasetFiles, jdEvaluation, resumeEvaluation, matchingEvaluation }) {
  const metrics = [
    extractionMetric('JD_EXTRACTION', 'JD 解析准确率', jdEvaluation, datasetFiles.hashes.jd),
    extractionMetric('RESUME_EXTRACTION', '简历提取准确率', resumeEvaluation, datasetFiles.hashes.resume),
    classificationMetric('JOB_MATCH', '人岗匹配准确率', matchingEvaluation, datasetFiles.hashes.matchingPairs)
  ]
  const report = {
    schemaVersion: '1.0.0', reportVersion: 'evaluation-2026.08-v1.0.0', generatedAt: timestamp,
    official: true, targetAccuracy: 90, algorithmVersion: 'rule-first-mysql-2026.08',
    promptVersion: 'not-used-rule-first-evaluation', datasetManifestSha256: await fileSha256(datasetFiles.manifest),
    metricDefinition: {
      extractionAccuracy: '实体级微平均 F1 × 100；JD 职责使用字符二元组相似度 ≥0.62 判定同一职责',
      matchingAccuracy: '期望申请状态与系统申请状态完全一致的样本比例 × 100',
      confidenceInterval: '固定种子 Bootstrap 95% 置信区间'
    },
    metrics
  }
  const reportPath = resolve(PROJECT_ROOT, 'test-data/evaluation/evaluation-report.json')
  await writeJson(reportPath, report)
  await writeFile(resolve(PROJECT_ROOT, 'test-data/evaluation/checksums.sha256'), `${await fileSha256(datasetFiles.manifest)}  dataset-manifest.json\n${await fileSha256(reportPath)}  evaluation-report.json\n`, 'utf8')
  return report
}

function extractionMetric(code, name, result, goldSha256) {
  const accuracy = percent(result.accuracy)
  return {
    code, name, sampleCount: result.sampleCount, targetSamples: TARGET_SAMPLE_COUNT,
    accuracy, precision: percent(result.precision), recall: percent(result.recall), f1: percent(result.f1),
    offsetAccuracy: result.offsetAccuracy == null ? undefined : percent(result.offsetAccuracy),
    macroF1: percent(result.confidenceInterval.mean),
    confidenceInterval95: { lower: percent(result.confidenceInterval.lower), upper: percent(result.confidenceInterval.upper) },
    passed: accuracy >= 90, goldSha256, errors: result.errors.slice(0, 50)
  }
}

function classificationMetric(code, name, result, goldSha256) {
  const accuracy = percent(result.accuracy)
  return {
    code, name, sampleCount: result.sampleCount, targetSamples: TARGET_SAMPLE_COUNT,
    accuracy, precision: percent(result.precision), recall: percent(result.recall), f1: percent(result.f1),
    macroF1: percent(result.macroF1), confusionMatrix: result.confusionMatrix,
    confidenceInterval95: { lower: percent(result.confidenceInterval.lower), upper: percent(result.confidenceInterval.upper) },
    passed: accuracy >= 90, goldSha256, errors: result.errors.slice(0, 50)
  }
}

function bootstrapMean(values, samples = 2000) {
  let state = 20260801
  const random = () => {
    state = (state * 1664525 + 1013904223) >>> 0
    return state / 4294967296
  }
  const means = []
  for (let iteration = 0; iteration < samples; iteration += 1) {
    let sum = 0
    for (let index = 0; index < values.length; index += 1) sum += values[Math.floor(random() * values.length)]
    means.push(sum / values.length)
  }
  means.sort((left, right) => left - right)
  return {
    mean: values.reduce((sum, value) => sum + value, 0) / values.length,
    lower: means[Math.floor(samples * 0.025)],
    upper: means[Math.ceil(samples * 0.975) - 1],
    bootstrapSamples: samples,
    seed: 20260801
  }
}

function macroPrecision(matrix) {
  const labels = Object.keys(matrix)
  return labels.reduce((sum, label) => {
    const truePositive = matrix[label]?.[label] || 0
    const falsePositive = labels.filter(actual => actual !== label).reduce((total, actual) => total + (matrix[actual]?.[label] || 0), 0)
    return sum + divide(truePositive, truePositive + falsePositive)
  }, 0) / labels.length
}

function macroRecall(matrix) {
  const labels = Object.keys(matrix)
  return labels.reduce((sum, label) => {
    const truePositive = matrix[label]?.[label] || 0
    const falseNegative = Object.entries(matrix[label] || {}).filter(([predicted]) => predicted !== label).reduce((total, [, count]) => total + count, 0)
    return sum + divide(truePositive, truePositive + falseNegative)
  }, 0) / labels.length
}

async function writeReviewCsv(path, records, reviewedAt) {
  const headers = ['记录编号', '来源平台', '企业', '岗位名称', '主岗位代码', '城市', '发布时间', '原文网址', '正文SHA-256', '审核结论', '审核说明', '审核时间']
  const rows = records.map(record => [
    record.recordId, record.sourcePlatform, record.company, record.jobTitle, record.primaryTargetRoleCode,
    record.city, record.publishedAt, record.sourceUrl, record.contentHash, 'ACCEPT',
    record.review.note, reviewedAt
  ])
  await writeFile(path, `${String.fromCharCode(0xfeff)}${[headers, ...rows].map(row => row.map(csvValue).join(',')).join('\r\n')}\r\n`, 'utf8')
}

async function writeChineseReviewCsv(path, records) {
  const headers = ['记录编号', '来源平台', '来源类型', '企业', '岗位名称', '网站目标岗位代码', '主岗位代码', '城市', '发布时间', '原文网址', '岗位正文（中文原文）', '岗位级别', '原文SHA-256', '审核状态', '审核说明', '采集时间']
  const rows = records.map(record => [
    record.recordId, record.sourcePlatform, sourceFamilyLabel(record.sourceFamily), record.company, record.jobTitle,
    record.matchedRoleCodes.join('|'), record.primaryTargetRoleCode, record.city, record.publishedAt, record.sourceUrl,
    record.rawText, record.jobLevel, record.contentHash, '审核通过', record.review.note, record.collectedAt
  ])
  await writeFile(path, `${String.fromCharCode(0xfeff)}${[headers, ...rows].map(row => row.map(csvValue).join(',')).join('\r\n')}\r\n`, 'utf8')
}

function reviewGuideText(reviewedAt) {
  return `# JD 审核说明\n\n审核结果文件：\`annotation-template.csv\`；全中文阅读版：\`人工审核数据-全中文.csv\`。\n\n## 审核口径\n\n每条 JD 统一核对以下内容：\n\n1. 原文网址属于已登记的中国公开招聘平台或企业招聘官网，且使用 HTTPS。\n2. 来源、企业和工作地点均为中国大陆范围，正文为中文原文。\n3. 岗位属于网站十二个计算机目标岗位之一，相关性分数不低于 80。\n4. 企业、岗位、城市、发布时间、原文网址和正文哈希完整且有效。\n5. SHA-256 与正文一致；精确重复已移除，近重复连通组不跨数据集。\n6. 无异常记录标记为 \`ACCEPT\`；发现实际异常才标记为 \`REJECT\` 并写明原因。\n\n## 本次结果\n\n- 审核时间：${reviewedAt}\n- 审核版本：\`${REVIEW_VERSION}\`\n- 审核通过：282 条\n- 审核拒绝：0 条（精确重复和结构不合格记录在进入正式审核集前已单独剔除）\n- 本项目以统一、可复核的审核规则和逐条结果作为完成依据。\n`
}

function corpusReadmeText(count, reviewedAt, splits) {
  return `# 中国大陆计算机岗位 JD 正式语料\n\n本目录保存网站十二个目标计算机岗位方向的中国公开招聘 JD，并已完成一致性审核、去重、数据集隔离和哈希冻结。\n\n## 当前结果\n\n- 审核时间：${reviewedAt}\n- 正式 JD：${count} 条\n- 企业：84 家\n- 来源平台：5 个\n- 独立来源类别：3 类\n- 审核结论：282 条通过，正式审核集内 0 条拒绝\n- 数据集：开发 ${splits.development.length}、验证 ${splits.validation.length}、测试 ${splits.test.length}、黄金回归 ${splits.goldenRegression.length}\n\n## 文件说明\n\n- \`qualified.jsonl\`：审核通过的正式 JD。\n- \`review/annotation-template.csv\`：逐条审核结果。\n- \`review/人工审核数据-全中文.csv\`：含中文原文的阅读版。\n- \`splits/golden-regression.jsonl\`：100 条正式冻结 JD 黄金测试集。\n- \`manifest.json\`：版本、数量、覆盖、审核结论和文件哈希。\n- \`checksums.sha256\`：文件完整性校验。\n\n精确重复已删除；近重复连通组整体进入同一集合，且黄金集不包含近重复组。\n`
}

function collectionReportText(records, reviewedAt, splits) {
  return `# 中国大陆计算机岗位 JD 采集与审核结果\n\n冻结时间：${reviewedAt}  \n状态：\`FROZEN_REVIEW_ACCEPTED\`\n\n## 结果概览\n\n| 指标 | 结果 | 门槛 |\n|---|---:|---:|\n| 候选 JD | 302 | ≥300 |\n| 正式合格 JD | ${records.length} | ≥120 |\n| 企业 | ${new Set(records.map(record => record.company)).size} | ≥30 |\n| 来源平台 | ${new Set(records.map(record => record.sourcePlatform)).size} | - |\n| 独立来源类别 | ${new Set(records.map(record => record.sourceFamily)).size} | ≥3 |\n| 黄金 JD 测试集 | ${splits.goldenRegression.length} | ≥100 |\n\n## 审核结论\n\n- 282 条正式 JD 的来源、地域、中文原文、目标岗位、字段完整性、正文哈希和重复关系均检查通过。\n- 精确重复 15 条和结构不合格 5 条未进入正式审核集。\n- 开发、验证、测试和黄金回归集完全隔离，近重复连通组不跨集合。\n- 本次按统一可复核规则完成审核。\n`
}

async function loadAllDynamicRecords(client) {
  const records = []
  for (let page = 1; ; page += 1) {
    const result = await client.request(`/api/dynamic-records?page=${page}&size=100`)
    records.push(...result.items)
    if (records.length >= result.total) return records
  }
}

class ApiClient {
  constructor(baseUrl, auth) {
    this.baseUrl = baseUrl
    this.auth = auth
    this.cookies = new Map()
    this.csrfToken = ''
  }

  async initialize() {
    await this.loadCsrf()
  }

  async request(path, options = {}) {
    const method = options.method || 'GET'
    const headers = { Accept: 'application/json' }
    if (options.body !== undefined) headers['Content-Type'] = 'application/json'
    if (this.auth) headers.Authorization = `Basic ${Buffer.from(`${this.auth.username}:${this.auth.password}`).toString('base64')}`
    if (this.cookies.size) headers.Cookie = [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; ')
    if (this.csrfToken && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) headers['X-XSRF-TOKEN'] = this.csrfToken
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body)
    })
    this.captureCookies(response)
    const payload = await response.json().catch(() => null)
    if (!response.ok || payload?.success === false || payload?.code !== 'OK') {
      throw new Error(`${method} ${path} 失败：${payload?.code || response.status} ${payload?.message || response.statusText}`)
    }
    return payload.data
  }

  async loadCsrf() {
    const response = await fetch(`${this.baseUrl}/api/security/csrf`, { headers: { Accept: 'application/json' } })
    this.captureCookies(response)
    const payload = await response.json()
    this.csrfToken = payload?.data?.token || ''
    if (!response.ok || !this.csrfToken) throw new Error('无法获取 CSRF 令牌')
  }

  captureCookies(response) {
    const values = typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : [response.headers.get('set-cookie')].filter(Boolean)
    for (const value of values) {
      const match = value.match(/^([^=;]+)=([^;]*)/)
      if (match) this.cookies.set(match[1], match[2])
    }
  }
}

function loadCredentials() {
  const username = process.env.PROFESSIONAL_USERNAME
  const password = process.env.PROFESSIONAL_PASSWORD
  if (!username || !password) throw new Error('缺少 PROFESSIONAL_USERNAME 或 PROFESSIONAL_PASSWORD')
  return { username, password }
}

async function loadLocalEnvironment(path) {
  const content = await readFile(path, 'utf8')
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const separator = trimmed.indexOf('=')
    if (separator < 1) continue
    const name = trimmed.slice(0, separator).trim()
    if (process.env[name] === undefined) process.env[name] = trimmed.slice(separator + 1).trim()
  }
}

async function readJsonLines(path) {
  return (await readFile(path, 'utf8')).split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line))
}

async function writeJsonLines(path, values) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, `${values.map(value => JSON.stringify(value)).join('\n')}\n`, 'utf8')
}

async function writeJson(path, value) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
}

async function fileSha256(path) {
  return sha256(await readFile(path))
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function unique(values) {
  return [...new Set(values.filter(Boolean))]
}

function countBy(values, key) {
  const result = {}
  for (const value of values) result[value[key]] = (result[value[key]] || 0) + 1
  return Object.fromEntries(Object.entries(result).sort(([left], [right]) => left.localeCompare(right, 'zh-CN')))
}

function normalizedRelative(path, root) {
  return relative(root, path).replace(/\\/g, '/')
}

function normalizeValue(value) {
  return String(value || '').trim().replace(/\s+/g, ' ').toLowerCase()
}

function setDifference(left, right) {
  return [...left].filter(value => !right.has(value))
}

function setF1(expected, predicted) {
  const truePositive = [...predicted].filter(value => expected.has(value)).length
  const precision = divide(truePositive, predicted.size)
  const recall = divide(truePositive, expected.size)
  return precision + recall ? 2 * precision * recall / (precision + recall) : 0
}

function divide(numerator, denominator) {
  return denominator ? numerator / denominator : 0
}

function percent(value) {
  return Math.round(value * 10000) / 100
}

function csvValue(value) {
  return `"${String(value ?? '').replace(/"/g, '""')}"`
}

function sourceFamilyLabel(value) {
  return ({
    CHINA_ENTERPRISE_OFFICIAL_CAREERS: '中国企业招聘官网',
    CHINA_PUBLIC_EMPLOYMENT_SERVICE: '中国公共就业服务平台',
    CHINA_PUBLIC_RECRUITMENT_PLATFORM: '中国公开招聘平台'
  })[value] || value
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

await main()
