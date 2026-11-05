import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const EXTRA_KEYWORDS = [
  '智能体开发',
  '多智能体',
  'AI应用后端',
  '大模型后端',
  '检索增强生成',
  '向量数据库',
  'Embedding',
  'LLMOps',
  'MLOps',
  '模型部署',
  '推理服务',
  '模型评测',
  '大模型测试',
  'AI安全测试',
  '提示注入',
  '知识工程',
  '本体工程',
  '数据治理',
  'Python开发',
  '算法工程师',
  '软件开发',
  '云原生',
  '搜索算法',
  '推荐算法',
  '测试开发',
  '信息安全',
  '数据平台',
  '分布式系统'
]

function unique(values) {
  return [...new Set(values)]
}

function buildExpandedManifest(baseManifest) {
  const manifest = structuredClone(baseManifest)
  manifest.requirements = {
    ...manifest.requirements,
    candidateMinimum: 280,
    structurallyQualifiedMinimum: 240,
    companyMinimum: 60,
    independentSourceMinimum: 3
  }
  manifest.sources = manifest.sources.map(source => {
    if (source.adapter === 'IGUOPIN') {
      return {
        ...source,
        searchKeywords: unique([...(source.searchKeywords ?? []), ...EXTRA_KEYWORDS]),
        pageCountPerKeyword: Math.max(source.pageCountPerKeyword ?? 0, 40),
        maximumRecords: Math.max(source.maximumRecords ?? 0, 1000),
        maximumRecordsPerCompany: Math.min(source.maximumRecordsPerCompany ?? 6, 6)
      }
    }
    if (source.adapter === 'NCSS') {
      return {
        ...source,
        searchKeywords: unique([...(source.searchKeywords ?? []), ...EXTRA_KEYWORDS]),
        pageCountPerKeyword: Math.max(source.pageCountPerKeyword ?? 0, 30),
        maximumDetailRequests: Math.max(source.maximumDetailRequests ?? 0, 800),
        maximumRecords: Math.max(source.maximumRecords ?? 0, 500),
        maximumRecordsPerCompany: Math.min(source.maximumRecordsPerCompany ?? 6, 6)
      }
    }
    if (source.adapter === 'TENCENT') {
      return {
        ...source,
        pageCount: Math.max(source.pageCount ?? 0, 30),
        maximumDetailRequests: Math.max(source.maximumDetailRequests ?? 0, 600),
        maximumRecords: Math.max(source.maximumRecords ?? 0, 250)
      }
    }
    if (source.adapter === 'MEITUAN') {
      return {
        ...source,
        pageCount: Math.max(source.pageCount ?? 0, 20),
        maximumRecords: Math.max(source.maximumRecords ?? 0, 250)
      }
    }
    if (source.adapter === 'BAIDU') {
      return {
        ...source,
        maximumDetailRequests: Math.max(source.maximumDetailRequests ?? 0, 100),
        maximumRecords: Math.max(source.maximumRecords ?? 0, 100)
      }
    }
    return source
  })
  if (!manifest.sources.some(source => source.adapter === 'VIVO')) {
    manifest.sources.push({
      sourceId: 'vivo-official-current-cn',
      sourceName: 'vivo 招聘官网社会招聘',
      sourcePlatform: 'vivo 招聘官网',
      sourceFamily: 'CHINA_ENTERPRISE_OFFICIAL_CAREERS',
      sourceCountry: 'CN',
      adapter: 'VIVO',
      sourceTier: 'TIER_1',
      sourceType: '中国企业招聘官网 JD',
      companyName: '维沃移动通信有限公司（vivo）',
      entryUrl: 'https://career.vivo.com/api/social/webSite/portal/page',
      listingUrl: 'https://career.vivo.com/jobs',
      detailBaseUrl: 'https://career.vivo.com/job-detail',
      licenseStatus: 'PUBLIC_OFFICIAL_SITE_REVIEW_REQUIRED',
      licenseUrl: 'https://career.vivo.com/jobs',
      licenseNote: 'vivo 招聘官网公开职位接口，无需登录；只作比赛数据审核，提交前复核官网条款。',
      pageCount: 30,
      pageSize: 100,
      maximumRecords: 250
    })
  }
  if (!manifest.sources.some(source => source.adapter === 'SANGFOR')) {
    manifest.sources.push({
      sourceId: 'sangfor-official-current-cn',
      sourceName: '深信服招聘官网社会招聘',
      sourcePlatform: '深信服招聘官网',
      sourceFamily: 'CHINA_ENTERPRISE_OFFICIAL_CAREERS',
      sourceCountry: 'CN',
      adapter: 'SANGFOR',
      sourceTier: 'TIER_1',
      sourceType: '中国企业招聘官网 JD',
      companyName: '深信服科技股份有限公司',
      tokenUrl: 'https://hr.sangfor.com/api/api/connect/token',
      entryUrl: 'https://hr.sangfor.com/api/api/Jobs',
      listingUrl: 'https://hr.sangfor.com/Sociology',
      detailBaseUrl: 'https://hr.sangfor.com/Delivery',
      licenseStatus: 'PUBLIC_OFFICIAL_SITE_REVIEW_REQUIRED',
      licenseUrl: 'https://hr.sangfor.com/Sociology',
      licenseNote: '深信服招聘官网公开职位接口，通过官网公开匿名令牌访问；只作比赛数据审核，提交前复核官网条款。',
      channelId: 110,
      pageSize: 500,
      maximumRecords: 120
    })
  }
  if (!manifest.sources.some(source => source.adapter === 'JOBONLINE')) {
    manifest.sources.push({
      sourceId: 'jobonline-current-cn',
      sourceName: '中国就业网公开招聘职位',
      sourcePlatform: '中国就业网',
      sourceFamily: 'CHINA_PUBLIC_RECRUITMENT_PLATFORM',
      sourceCountry: 'CN',
      adapter: 'JOBONLINE',
      sourceTier: 'TIER_1',
      sourceType: '中国公共招聘平台 JD',
      entryUrl: 'https://api.jobonline.cn/jobtbao-es-api/elastic/api/position/common/v1/showlist?bodytarget=INDEX_PAGE',
      listingUrl: 'https://www.jobonline.cn/position',
      detailPath: '/jobtbao-platform-srv/v1/platform/unsafe/position/getPositionDetail?target=POSITION_DETAIL',
      detailBaseUrl: 'https://www.jobonline.cn/positionDetail',
      licenseStatus: 'PUBLIC_WEB_REVIEW_REQUIRED',
      licenseUrl: 'https://www.jobonline.cn/position',
      licenseNote: '人力资源和社会保障部门指导的公开就业平台，无需登录即可查看职位；只作比赛数据审核，提交前复核平台条款。',
      searchKeywords: unique(['Java', 'AI Agent', 'RAG', '大模型', 'LLMOps', '模型评测', 'AI平台', '数据治理', '知识工程', '向量检索', 'AI安全']),
      pageCountPerKeyword: 5,
      pageSize: 50,
      maximumDetailRequests: 600,
      maximumRecords: 400,
      maximumRecordsPerCompany: 6
    })
  }
  return manifest
}

function recordHash(record) {
  return String(record?.contentHash ?? record?.contentSha256 ?? '').toLowerCase()
}

function excludeExactExposedRecords(candidates, exposedHashes) {
  const normalizedHashes = new Set([...exposedHashes].map(hash => String(hash).toLowerCase()))
  const retained = []
  const excluded = []
  for (const record of candidates) {
    const target = normalizedHashes.has(recordHash(record)) ? excluded : retained
    target.push(record)
  }
  return { retained, excluded }
}

async function expandManifest(inputPath, outputPath) {
  const baseManifest = JSON.parse(await readFile(resolve(inputPath), 'utf8'))
  const expanded = buildExpandedManifest(baseManifest)
  await writeFile(resolve(outputPath), `${JSON.stringify(expanded, null, 2)}\n`, 'utf8')
}

export { buildExpandedManifest, excludeExactExposedRecords, expandManifest }

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (import.meta.url === invokedPath) {
  const [command, inputPath, outputPath] = process.argv.slice(2)
  if (command !== 'expand-manifest' || !inputPath || !outputPath) {
    throw new Error('用法：node collector/official-jd-pipeline.mjs expand-manifest <输入来源清单> <输出来源清单>')
  }
  await expandManifest(inputPath, outputPath)
}
