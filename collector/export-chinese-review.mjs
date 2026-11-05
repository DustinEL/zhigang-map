import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = resolve(SCRIPT_DIRECTORY, '..')
const corpusDirectory = process.argv[2]
  ? resolve(process.argv[2])
  : resolve(PROJECT_ROOT, 'test-data/jd-corpus')
const input = resolve(corpusDirectory, 'qualified.jsonl')
const output = resolve(corpusDirectory, 'review/人工审核数据-全中文.csv')

const sourceFamilyLabels = {
  CHINA_ENTERPRISE_OFFICIAL_CAREERS: '中国企业招聘官网',
  CHINA_PUBLIC_EMPLOYMENT_SERVICE: '中国公共就业服务平台',
  CHINA_PUBLIC_RECRUITMENT_PLATFORM: '中国公开招聘平台'
}

function csvValue(value) {
  return `"${String(value ?? '').replace(/"/g, '""')}"`
}

function chineseStatus(status) {
  return ({
    PENDING_HUMAN_REVIEW: '待审核',
    PENDING_REVIEW: '待审核',
    REVIEW_ACCEPTED: '审核通过',
    REVIEW_REJECTED: '审核不通过'
  })[status] ?? status
}

function chineseCountry(country) {
  return country === 'CN' ? '中国大陆' : country
}

const records = (await readFile(input, 'utf8'))
  .split(/\r?\n/)
  .filter(Boolean)
  .map(line => JSON.parse(line))

const headers = [
  '记录编号',
  '来源平台',
  '来源类型',
  '来源国家/地区',
  '工作地点国家/地区',
  '工作地点判定依据',
  '企业',
  '岗位名称（来源原文）',
  '岗位名称（中文展示）',
  '网站目标岗位代码',
  '主岗位代码',
  '自动相关性分数',
  '自动相关性依据',
  '城市',
  '发布时间',
  '原文网址',
  '岗位正文（中文原文）',
  '岗位级别（中文）',
  '原文SHA-256',
  '审核状态（中文）',
  '许可复核状态',
  '采集时间'
]

const rows = records.map(record => [
  record.recordId,
  record.sourcePlatform,
  sourceFamilyLabels[record.sourceFamily] ?? record.sourceType,
  chineseCountry(record.sourceCountry),
  chineseCountry(record.workCountry),
  record.workCountryBasis,
  record.company,
  record.jobTitle,
  record.jobTitle,
  record.matchedRoleCodes?.join('|') ?? '',
  record.primaryTargetRoleCode ?? '',
  record.relevanceScore ?? 0,
  record.relevanceReasons?.join('；') ?? '',
  record.city,
  record.publishedAt,
  record.sourceUrl,
  record.rawText,
  record.jobLevel === 'SENIOR' ? '高级或管理岗' : record.jobLevel,
  record.contentHash,
  chineseStatus(record.processingStatus),
  record.licenseStatus === 'PUBLIC_SOURCE_CHECKED_FOR_COMPETITION_REFERENCE'
    ? '公开来源比赛引用检查通过'
    : record.licenseStatus === 'PUBLIC_WEB_REVIEW_REQUIRED'
    ? '待人工复核公开网页使用许可'
    : record.licenseStatus === 'PUBLIC_OFFICIAL_SITE_REVIEW_REQUIRED'
      ? '待人工复核企业官网使用许可'
      : record.licenseStatus === 'PUBLIC_GOVERNMENT_PLATFORM_REVIEW_REQUIRED'
        ? '待人工复核公共平台使用许可'
        : record.licenseStatus,
  record.collectedAt
])

await mkdir(dirname(output), { recursive: true })
await writeFile(
  output,
  `\ufeff${[headers, ...rows].map(row => row.map(csvValue).join(',')).join('\r\n')}\r\n`,
  'utf8'
)

console.log(`已生成全中文人工审核数据表：${output}`)
console.log(`记录数：${records.length}`)
