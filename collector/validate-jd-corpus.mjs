import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { mainlandCity } from './build-jd-corpus.mjs'

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = resolve(SCRIPT_DIRECTORY, '..')
const DEFAULT_ROOT = resolve(PROJECT_ROOT, 'test-data/jd-corpus')
const ALLOWED_HOSTS = new Set(['www.iguopin.com', 'zhaopin.meituan.com', 'careers.tencent.com', 'talent.baidu.com', 'www.ncss.cn'])
const FORBIDDEN_HOST_PARTS = ['greenhouse.io', 'lever.co', 'ashbyhq.com', 'linkedin.com', 'indeed.com']

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function readJson(path) {
  return JSON.parse(await readFile(path, 'utf8'))
}

async function readJsonLines(path) {
  const text = await readFile(path, 'utf8')
  return text.trim() ? text.trim().split(/\r?\n/).map((line, index) => {
    try {
      return JSON.parse(line)
    } catch (error) {
      throw new Error(`${path}:${index + 1} JSON 无效：${error.message}`)
    }
  }) : []
}

function chineseCharacterCount(value) {
  return (String(value ?? '').match(/[\u3400-\u9fff]/g) ?? []).length
}

function assertChinaUrl(record) {
  const url = new URL(record.sourceUrl)
  const hostname = url.hostname.toLowerCase()
  assert(url.protocol === 'https:', `${record.recordId} 来源 URL 不是 HTTPS`)
  assert(ALLOWED_HOSTS.has(hostname), `${record.recordId} 来源域名不在中国白名单：${hostname}`)
  assert(!FORBIDDEN_HOST_PARTS.some(part => hostname.includes(part)), `${record.recordId} 命中国外来源域名`)
  if (record.sourcePlatform === '国聘') assert(hostname === 'www.iguopin.com', `${record.recordId} 国聘域名错误`)
  if (record.sourcePlatform === '美团招聘官网') assert(hostname === 'zhaopin.meituan.com', `${record.recordId} 美团域名错误`)
  if (record.sourcePlatform === '腾讯招聘官网') assert(hostname === 'careers.tencent.com', `${record.recordId} 腾讯域名错误`)
  if (record.sourcePlatform === '百度招聘官网') assert(hostname === 'talent.baidu.com', `${record.recordId} 百度域名错误`)
  if (record.sourcePlatform === '国家大学生就业服务平台') assert(hostname === 'www.ncss.cn', `${record.recordId} 国家平台域名错误`)
}

function validateRecord(record, { qualified = false } = {}) {
  assert(record.schemaVersion === '2.0.0', `${record.recordId} schemaVersion 错误`)
  assert(record.sourceCountry === 'CN', `${record.recordId} sourceCountry 不是 CN`)
  assert(record.companyCountry === 'CN', `${record.recordId} companyCountry 不是 CN`)
  assert(record.workCountry === 'CN', `${record.recordId} workCountry 不是 CN`)
  assert(record.workCountryBasis, `${record.recordId} 缺少中国大陆工作地点判定依据`)
  assert(mainlandCity(record.city), `${record.recordId} 工作地点不是中国大陆：${record.city}`)
  assert(record.language === 'zh-CN', `${record.recordId} 不是中文原文`)
  assert(record.timeScope === 'CURRENT', `${record.recordId} 不是 CURRENT`)
  assert(record.liveAtCollection === true, `${record.recordId} 缺少采集时仍在线证据`)
  assert(record.company && record.jobTitle && record.city, `${record.recordId} 缺少企业、岗位或城市`)
  if (qualified) {
    assert(record.rawText.length >= 200, `${record.recordId} 正文少于 200 字符`)
    assert(chineseCharacterCount(record.rawText) >= 80, `${record.recordId} 中文正文不足 80 个汉字`)
  } else {
    assert(record.rawText.length > 0, `${record.recordId} 缺少正文`)
    assert(chineseCharacterCount(record.rawText) >= 20, `${record.recordId} 候选正文中文不足 20 个汉字`)
  }
  assert(Number.isFinite(Date.parse(record.publishedAt)), `${record.recordId} 发布时间无效`)
  assert(sha256(record.rawText) === record.contentHash, `${record.recordId} 正文 SHA-256 不一致`)
  assertChinaUrl(record)
  const accepted = record.processingStatus === 'REVIEW_ACCEPTED'
  const rejected = record.processingStatus === 'REVIEW_REJECTED'
  const pending = ['PENDING_REVIEW', 'PENDING_HUMAN_REVIEW'].includes(record.processingStatus)
  assert(accepted || rejected || pending, `${record.recordId} 审核状态无效：${record.processingStatus}`)
  if (accepted) assert(record.review?.decision === 'ACCEPT', `${record.recordId} 缺少通过结论`)
  if (rejected) assert(record.review?.decision === 'REJECT', `${record.recordId} 缺少拒绝结论`)
  if (record.relevanceStatus != null) {
    assert(record.relevanceStatus === 'TARGET_ROLE_MATCH', `${record.recordId} 不属于网站目标计算机岗位`)
    assert(Array.isArray(record.matchedRoleCodes) && record.matchedRoleCodes.length > 0, `${record.recordId} 缺少网站岗位代码`)
    assert(record.relevanceScore >= 80, `${record.recordId} 岗位相关性分数不足`)
    assert(Array.isArray(record.relevanceReasons) && record.relevanceReasons.length > 0, `${record.recordId} 缺少岗位相关性依据`)
  }
  if (qualified) {
    assert(accepted || pending, `${record.recordId} 合格集记录不是通过或待审核状态`)
    assert(record.qualityGate?.passed === true, `${record.recordId} 未通过结构门禁`)
    assert(record.sourceFamily && record.sourcePlatform, `${record.recordId} 缺少独立来源标识`)
  }
}

async function verifyChecksums(root) {
  const lines = (await readFile(resolve(root, 'checksums.sha256'), 'utf8')).trim().split(/\r?\n/)
  for (const [index, line] of lines.entries()) {
    const match = line.match(/^([0-9a-f]{64})  (.+)$/)
    assert(match, `checksums.sha256 第 ${index + 1} 行格式错误`)
    const actual = sha256(await readFile(resolve(root, match[2])))
    assert(actual === match[1], `${match[2]} 的 SHA-256 校验失败`)
  }
  return lines.length
}

async function validateCorpus(root = DEFAULT_ROOT) {
  const manifest = await readJson(resolve(root, 'manifest.json'))
  const ledger = await readJson(resolve(root, 'source-ledger.json'))
  const candidates = await readJsonLines(resolve(root, 'candidate.jsonl'))
  const qualified = await readJsonLines(resolve(root, 'qualified.jsonl'))
  const exactDuplicates = await readJsonLines(resolve(root, 'duplicates/exact.jsonl'))
  const nearDuplicates = await readJsonLines(resolve(root, 'duplicates/near.jsonl'))
  const companySet = new Set(qualified.map(record => record.company.toLowerCase()))
  const boardSet = new Set(qualified.map(record => record.companyBoardId))
  const sourcePlatformSet = new Set(qualified.map(record => record.sourcePlatform))
  const sourceFamilySet = new Set(qualified.map(record => record.sourceFamily))
  const recordIds = new Set()
  const contentHashes = new Set()

  assert(['3.0.0', '3.1.0'].includes(manifest.schemaVersion), 'manifest 必须使用 schemaVersion 3.0.0 或 3.1.0')
  const qualifiedMinimum = manifest.requirements.structurallyQualifiedMinimum ?? manifest.requirements.qualifiedMinimum
  assert(candidates.length >= manifest.requirements.candidateMinimum, `候选 JD 只有 ${candidates.length} 条，要求至少 ${manifest.requirements.candidateMinimum} 条`)
  assert(qualified.length >= qualifiedMinimum, `去重后合格 JD 只有 ${qualified.length} 条，要求至少 ${qualifiedMinimum} 条`)
  assert(companySet.size >= manifest.requirements.companyMinimum, `当前 JD 只有 ${companySet.size} 家企业，要求至少 ${manifest.requirements.companyMinimum} 家`)
  assert(sourcePlatformSet.size >= manifest.requirements.independentSourceMinimum, `当前只有 ${sourcePlatformSet.size} 个中国来源平台，要求至少 ${manifest.requirements.independentSourceMinimum} 个`)
  assert(sourceFamilySet.size >= manifest.requirements.independentSourceMinimum, `当前只有 ${sourceFamilySet.size} 类独立中国来源，要求至少 ${manifest.requirements.independentSourceMinimum} 类`)

  for (const record of candidates) {
    validateRecord(record)
    assert(record.qualityGate?.humanReviewRequired === true || record.qualityGate?.humanReviewRequired === false
      || record.qualityGate?.reviewRequired === true, `${record.recordId} 缺少审核标记`)
  }

  for (const record of qualified) {
    validateRecord(record, { qualified: true })
    assert(!recordIds.has(record.recordId), `recordId 重复：${record.recordId}`)
    assert(!contentHashes.has(record.contentHash), `qualified.jsonl 仍含精确重复：${record.contentHash}`)
    recordIds.add(record.recordId)
    contentHashes.add(record.contentHash)
  }

  const sourceLedgers = ledger.sources.filter(source => source.selectedRecordCount > 0)
  assert(['3.0.0', '3.1.0'].includes(ledger.schemaVersion), 'source-ledger.json 必须使用 schemaVersion 3.0.0 或 3.1.0')
  assert(sourceLedgers.length >= 3, `来源台账成功来源只有 ${sourceLedgers.length} 个`)
  assert(new Set(sourceLedgers.map(source => source.sourceFamily)).size >= 3, '来源台账未覆盖 3 类独立中国来源')
  assert(sourceLedgers.every(source => source.sourceCountry === 'CN'), '来源台账含非中国来源')
  assert(sourceLedgers.every(source => source.status !== 'COLLECTION_FAILED'), '成功来源中存在 COLLECTION_FAILED')
  assert(sourceLedgers.every(source => qualified.some(record => record.sourceId === source.sourceId)), '来源台账中的成功来源未在 qualified.jsonl 留下记录')

  const formalGolden = manifest.counts.splits?.goldenRegression != null
  const splitNames = ['development', 'validation', 'test', formalGolden ? 'golden-regression' : 'golden-regression-candidates']
  const membership = new Map()
  for (const splitName of splitNames) {
    const records = await readJsonLines(resolve(root, `splits/${splitName}.jsonl`))
    for (const record of records) {
      assert(!membership.has(record.recordId), `${record.recordId} 跨数据集重复`)
      membership.set(record.recordId, splitName)
    }
  }
  assert(membership.size === qualified.length, '数据划分未完整覆盖 qualified.jsonl')
  for (const relation of nearDuplicates) {
    assert(membership.get(relation.leftRecordId) === membership.get(relation.rightRecordId), `近重复跨数据集：${relation.leftRecordId}`)
  }
  if (formalGolden) {
    const golden = await readJsonLines(resolve(root, 'splits/golden-regression.jsonl'))
    assert(golden.length >= (manifest.requirements.goldenMinimum ?? 100), `正式黄金集只有 ${golden.length} 条`)
    const nearIds = new Set(nearDuplicates.flatMap(relation => [relation.leftRecordId, relation.rightRecordId]))
    assert(golden.every(record => !nearIds.has(record.recordId)), '正式黄金集包含近重复记录')
  }

  assert(manifest.counts.candidates === candidates.length, 'manifest 候选数与文件不一致')
  assert(manifest.counts.qualifiedAfterExactDedup === qualified.length, 'manifest 合格数与文件不一致')
  assert(manifest.counts.companies === companySet.size, 'manifest 企业数与文件不一致')
  assert(manifest.counts.independentSourceFamilies === sourceFamilySet.size, 'manifest 来源类数与文件不一致')
  assert(manifest.counts.foreignRecords === 0, 'manifest 标记存在国外记录')
  assert(manifest.counts.nonChineseRecords === 0, 'manifest 标记存在非中文记录')
  if (manifest.counts.nonTargetRecords != null) assert(manifest.counts.nonTargetRecords === 0, 'manifest 标记存在非网站目标岗位记录')
  assert((manifest.acceptance ?? manifest.automatedAcceptance).allRequirementsMet === true, '验收未通过')

  const annotationLines = (await readFile(resolve(root, 'review/annotation-template.csv'), 'utf8')).trim().split(/\r?\n/)
  assert(annotationLines.length === qualified.length + 1, '审核结果未完整覆盖 qualified.jsonl')
  const checksumsVerified = await verifyChecksums(root)
  return {
    ok: true,
    candidates: candidates.length,
    qualifiedCurrentJds: qualified.length,
    companies: companySet.size,
    companyBoards: boardSet.size,
    sourcePlatforms: [...sourcePlatformSet].sort(),
    sourceFamilies: [...sourceFamilySet].sort(),
    foreignRecords: 0,
    nonChineseRecords: 0,
    exactDuplicates: exactDuplicates.length,
    nearDuplicateRelations: nearDuplicates.length,
    splitCounts: Object.fromEntries(splitNames.map(name => [name, membership.size ? [...membership.values()].filter(value => value === name).length : 0])),
    checksumsVerified
  }
}

export { validateCorpus }

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (import.meta.url === invokedPath) {
  validateCorpus(process.argv[2] ? resolve(process.argv[2]) : DEFAULT_ROOT)
    .then(result => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`))
    .catch(error => {
      process.stderr.write(`${error.stack ?? error.message}\n`)
      process.exitCode = 1
    })
}
