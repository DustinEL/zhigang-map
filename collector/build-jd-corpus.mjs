import { createHash } from 'node:crypto'
import { access, mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { simHash } from './authorized-source-collector.mjs'
import { createJobonlineClient } from './jobonline-client.mjs'

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = resolve(SCRIPT_DIRECTORY, '..')
const DEFAULT_MANIFEST = resolve(SCRIPT_DIRECTORY, 'jd-corpus-sources.json')
const DEFAULT_OUTPUT = resolve(PROJECT_ROOT, 'test-data/jd-corpus')
const USER_AGENT = 'ZhigangMap-China-JD-Corpus/2.0 (+public-pages-only; human-review-required)'

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function decodeHtml(value) {
  return String(value ?? '')
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCodePoint(Number.parseInt(code, 16)))
    .replace(/&nbsp;|&#160;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
}

function cleanText(value) {
  return decodeHtml(String(value ?? '')
    .replace(/<!\[CDATA\[([\s\S]*?)]]>/g, '$1')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|li|div|pre|section|h[1-6])>/gi, '\n')
    .replace(/<[^>]+>/g, ' '))
    .replace(/\r/g, '')
    .replace(/[\t ]+\n/g, '\n')
    .replace(/\n[\t ]+/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/[\t ]{2,}/g, ' ')
    .trim()
}

function normalizeDate(value) {
  if (value == null || value === '') return null
  const numeric = Number(value)
  if (Number.isFinite(numeric)) {
    const milliseconds = numeric >= 1_000_000_000_000
      ? numeric
      : numeric >= 1_000_000_000 && numeric < 100_000_000_000 ? numeric * 1000 : null
    if (milliseconds != null) {
      const date = new Date(milliseconds)
      if (!Number.isNaN(date.getTime())) return date.toISOString()
    }
  }
  const original = String(value).trim()
  const chineseDate = original.match(/^(\d{4})年(\d{1,2})月(\d{1,2})日$/)
  if (chineseDate) {
    const [, year, month, day] = chineseDate
    return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}T00:00:00+08:00`
  }
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(original)) {
    return `${original.replace(' ', 'T')}+08:00`
  }
  const parsed = Date.parse(original)
  return Number.isNaN(parsed) ? null : new Date(parsed).toISOString()
}

function safeHttps(value) {
  try {
    const url = new URL(String(value ?? '').trim())
    if (url.protocol !== 'https:') return ''
    url.username = ''
    url.password = ''
    for (const key of [...url.searchParams.keys()]) {
      if (/^(trk|tracking|utm_|ref)$/i.test(key)) url.searchParams.delete(key)
    }
    return url.href
  } catch {
    return ''
  }
}

function inferJobLevel(title, explicit = '') {
  const provided = cleanText(explicit)
  if (provided) return provided
  const value = cleanText(title).toLowerCase()
  if (/负责人|总监|经理|主管|专家|架构师|首席|资深|高级/.test(value)) return '高级或管理岗'
  if (/principal|staff|lead|director|manager|senior|sr\.?/.test(value)) return '高级或管理岗'
  if (/中级|3[-—至到]5年|三年以上/.test(value)) return '中级'
  if (/mid-level|pleno/.test(value)) return '中级'
  if (/初级|助理|实习|应届|校招|管培/.test(value)) return '初级或实习'
  if (/junior|entry|intern|estagi/.test(value)) return '初级或实习'
  return '未注明'
}

function chineseCharacterCount(value) {
  return (String(value ?? '').match(/[\u3400-\u9fff]/g) ?? []).length
}

function detectLanguage(value) {
  return chineseCharacterCount(value) >= 20 ? 'zh-CN' : '非中文'
}

function qualityGate(record) {
  const reasons = []
  if (record.liveAtCollection !== true) reasons.push('采集时未确认仍在线')
  if (!record.sourceRecordKey) reasons.push('缺少来源记录编号')
  if (!record.jobTitle) reasons.push('缺少岗位名称')
  if (!record.company) reasons.push('缺少企业名称')
  if (!record.city) reasons.push('缺少工作城市')
  if (!record.publishedAt) reasons.push('缺少有效发布时间')
  if (!safeHttps(record.sourceUrl)) reasons.push('缺少 HTTPS 原文地址')
  if (record.sourceCountry !== 'CN') reasons.push('来源不是中国大陆')
  if (record.companyCountry !== 'CN') reasons.push('企业不是中国大陆')
  if (!mainlandCity(record.city)) reasons.push('工作地点不是中国大陆')
  if (record.language !== 'zh-CN') reasons.push('正文不是中文原文')
  if (record.rawText.length < 200) reasons.push('JD 正文少于 200 字符')
  if (chineseCharacterCount(record.rawText) < 80) reasons.push('JD 中文正文不足 80 个汉字')
  if (record.relevanceStatus && record.relevanceStatus !== 'TARGET_ROLE_MATCH') reasons.push('不属于网站目标计算机岗位')
  if (record.relevanceStatus && !record.matchedRoleCodes?.length) reasons.push('缺少网站目标岗位匹配结果')
  return {
    stage: '结构与目标岗位相关性校验',
    passed: reasons.length === 0,
    reasons,
    originalPostingUrlPresent: Boolean(record.originalPostingUrl),
    humanReviewRequired: true
  }
}

function enrichRecord(record, source, collectedAt) {
  const contentHash = sha256(record.rawText)
  const sourceKey = `${source.sourceId}:${record.sourceRecordKey || contentHash}`
  const enriched = {
    schemaVersion: '2.0.0',
    recordId: `jd_cn_${sha256(sourceKey).slice(0, 24)}`,
    sourceRecordKey: record.sourceRecordKey || contentHash,
    sourceId: source.sourceId,
    sourceName: source.sourceName,
    sourcePlatform: source.sourcePlatform,
    sourceFamily: source.sourceFamily,
    sourceCountry: 'CN',
    companyCountry: 'CN',
    workCountry: mainlandCity(record.city) ? 'CN' : 'UNKNOWN',
    workCountryBasis: mainlandCity(record.city)
      ? '来源结构化国家/地区字段或中国大陆行政区白名单校验'
      : '无法通过中国大陆行政区白名单校验',
    language: detectLanguage(record.rawText),
    companyBoardId: `${source.sourceId}:${sha256(record.company).slice(0, 12)}`,
    sourceTier: source.sourceTier,
    sourceType: source.sourceType,
    timeScope: 'CURRENT',
    liveAtCollection: true,
    currentStatusBasis: '采集时仍列在公开职位列表或详情页',
    licenseStatus: source.licenseStatus,
    licenseUrl: source.licenseUrl,
    licenseNote: source.licenseNote,
    collectedAt,
    publishedAt: record.publishedAt,
    publishedAtOriginal: record.publishedAtOriginal,
    publishedAtBasis: record.publishedAtBasis,
    expiresAt: record.expiresAt ?? null,
    company: record.company,
    companyBasis: record.companyBasis,
    companyCountryBasis: record.companyCountryBasis,
    jobTitle: record.jobTitle,
    city: record.city,
    jobLevel: record.jobLevel,
    jobExperience: record.jobExperience ?? '',
    jobType: record.jobType,
    sourceUrl: record.sourceUrl,
    sourceUrlBasis: '公开职位详情页',
    originalPostingUrl: record.sourceUrl,
    rawText: record.rawText,
    matchedRoleCodes: record.matchedRoleCodes ?? [],
    primaryTargetRoleCode: record.primaryTargetRoleCode ?? null,
    relevanceScore: record.relevanceScore ?? 0,
    relevanceReasons: record.relevanceReasons ?? [],
    relevanceStatus: record.relevanceStatus ?? 'UNCLASSIFIED',
    relevancePolicyVersion: 'website-target-roles-v1',
    contentHash,
    simHash: simHash(record.rawText),
    processingStatus: 'PENDING_REVIEW',
    review: null
  }
  enriched.qualityGate = qualityGate(enriched)
  return enriched
}

function uniqueBy(values, keySelector) {
  const seen = new Set()
  return values.filter(value => {
    const key = keySelector(value)
    if (!key || seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function diversifiedLimit(records, maximum, maximumPerCompany = Infinity) {
  const groups = new Map()
  for (const record of records) {
    const key = cleanText(record.company).toLowerCase()
    if (!groups.has(key)) groups.set(key, [])
    if (groups.get(key).length < maximumPerCompany) groups.get(key).push(record)
  }
  const queues = [...groups.entries()]
    .sort(([left], [right]) => left.localeCompare(right, 'zh-CN'))
    .map(([, values]) => values)
  const selected = []
  for (let round = 0; selected.length < maximum; round += 1) {
    let added = false
    for (const values of queues) {
      if (values[round] && selected.length < maximum) {
        selected.push(values[round])
        added = true
      }
    }
    if (!added) break
  }
  return selected
}

function sourcePriority(record) {
  return `${record.sourceTier === 'TIER_1' ? '0' : '1'}:${record.recordId}`
}

function deduplicateRecords(records) {
  const ordered = [...records].sort((left, right) => sourcePriority(left).localeCompare(sourcePriority(right)))
  const retainedByHash = new Map()
  const unique = []
  const exactDuplicates = []
  for (const record of ordered) {
    const retained = retainedByHash.get(record.contentHash)
    if (retained) {
      exactDuplicates.push({
        contentHash: record.contentHash,
        retainedRecordId: retained.recordId,
        duplicateRecordId: record.recordId,
        retainedSourceId: retained.sourceId,
        duplicateSourceId: record.sourceId
      })
    } else {
      retainedByHash.set(record.contentHash, record)
      unique.push(record)
    }
  }
  return { unique, exactDuplicates }
}

function hamming(left, right) {
  let value = BigInt(`0x${left}`) ^ BigInt(`0x${right}`)
  let count = 0
  while (value) {
    count += Number(value & 1n)
    value >>= 1n
  }
  return count
}

function characterShingles(text) {
  const normalized = cleanText(text).toLowerCase().replace(/[^\p{L}\p{N}+#.]+/gu, '')
  if (normalized.length < 3) return new Set([normalized])
  const shingles = new Set()
  for (let index = 0; index + 2 < normalized.length; index += 1) shingles.add(normalized.slice(index, index + 3))
  return shingles
}

function jaccard(left, right) {
  if (!left.size && !right.size) return 1
  let intersection = 0
  const smaller = left.size <= right.size ? left : right
  const larger = left.size <= right.size ? right : left
  for (const value of smaller) if (larger.has(value)) intersection += 1
  return intersection / Math.max(1, left.size + right.size - intersection)
}

function findNearDuplicates(records) {
  const relations = []
  const shingles = records.map(record => characterShingles(record.rawText))
  for (let left = 0; left < records.length; left += 1) {
    for (let right = left + 1; right < records.length; right += 1) {
      const leftLength = records[left].rawText.length
      const rightLength = records[right].rawText.length
      const lengthRatio = Math.min(leftLength, rightLength) / Math.max(leftLength, rightLength, 1)
      if (lengthRatio < 0.85) continue
      const distance = hamming(records[left].simHash, records[right].simHash)
      if (distance > 6) continue
      const similarity = jaccard(shingles[left], shingles[right])
      if (similarity < 0.9) continue
      relations.push({
        leftRecordId: records[left].recordId,
        rightRecordId: records[right].recordId,
        hammingDistance: distance,
        shingleJaccard: Number(similarity.toFixed(4)),
        lengthRatio: Number(lengthRatio.toFixed(4)),
        status: 'PENDING_HUMAN_CONFIRMATION'
      })
    }
  }
  return relations
}

class DisjointSet {
  constructor(values) {
    this.parent = new Map(values.map(value => [value, value]))
  }

  find(value) {
    const parent = this.parent.get(value)
    if (parent !== value) this.parent.set(value, this.find(parent))
    return this.parent.get(value)
  }

  union(left, right) {
    const leftRoot = this.find(left)
    const rightRoot = this.find(right)
    if (leftRoot !== rightRoot) this.parent.set(rightRoot, leftRoot)
  }
}

function partitionRecords(records, nearDuplicates) {
  const set = new DisjointSet(records.map(record => record.recordId))
  for (const relation of nearDuplicates) set.union(relation.leftRecordId, relation.rightRecordId)
  const groups = new Map()
  for (const record of records) {
    const root = set.find(record.recordId)
    if (!groups.has(root)) groups.set(root, [])
    groups.get(root).push(record)
  }
  const splits = { development: [], validation: [], test: [], goldenRegressionCandidates: [] }
  for (const group of groups.values()) {
    const bucket = Number.parseInt(sha256(group.map(record => record.recordId).sort().join(':')).slice(0, 8), 16) % 100
    const destination = bucket < 60 ? 'development' : bucket < 75 ? 'validation' : bucket < 95 ? 'test' : 'goldenRegressionCandidates'
    splits[destination].push(...group)
  }
  for (const values of Object.values(splits)) values.sort((left, right) => left.recordId.localeCompare(right.recordId))
  return splits
}

async function exists(path) {
  try {
    await access(path)
    return true
  } catch {
    return false
  }
}

async function fetchText(url, options = {}, maximumBytes = 20 * 1024 * 1024) {
  let lastError
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(url, {
        ...options,
        redirect: 'error',
        signal: AbortSignal.timeout(120_000),
        headers: { 'User-Agent': USER_AGENT, ...(options.headers ?? {}) }
      })
      if (!response.ok) throw new Error(`${url} 返回 HTTP ${response.status}`)
      const declaredLength = Number(response.headers.get('content-length') ?? 0)
      if (declaredLength > maximumBytes) throw new Error(`${url} 响应超过大小限制`)
      const buffer = Buffer.from(await response.arrayBuffer())
      if (buffer.byteLength > maximumBytes) throw new Error(`${url} 响应超过大小限制`)
      return { text: buffer.toString('utf8'), byteLength: buffer.byteLength, headers: response.headers }
    } catch (error) {
      lastError = error
      if (attempt < 3) await new Promise(resolveDelay => setTimeout(resolveDelay, attempt * 800))
    }
  }
  throw lastError
}

async function fetchJson(url, options = {}, maximumBytes) {
  const loaded = await fetchText(url, options, maximumBytes)
  return { ...loaded, data: JSON.parse(loaded.text) }
}

function extractEmbeddedJson(text, marker, openingCharacter) {
  const markerIndex = String(text).indexOf(marker)
  if (markerIndex < 0) throw new Error(`页面缺少嵌入数据标记：${marker}`)
  const startIndex = String(text).indexOf(openingCharacter, markerIndex + marker.length)
  if (startIndex < 0) throw new Error(`页面缺少嵌入 JSON：${marker}`)
  const closingCharacter = openingCharacter === '[' ? ']' : '}'
  let depth = 0
  let inString = false
  let escaped = false
  for (let index = startIndex; index < text.length; index += 1) {
    const character = text[index]
    if (inString) {
      if (escaped) escaped = false
      else if (character === '\\') escaped = true
      else if (character === '"') inString = false
      continue
    }
    if (character === '"') inString = true
    else if (character === openingCharacter) depth += 1
    else if (character === closingCharacter) {
      depth -= 1
      if (depth === 0) return JSON.parse(text.slice(startIndex, index + 1))
    }
  }
  throw new Error(`页面嵌入 JSON 未闭合：${marker}`)
}

async function mapLimit(values, limit, operation) {
  const results = Array(values.length)
  let nextIndex = 0
  async function worker() {
    while (nextIndex < values.length) {
      const index = nextIndex
      nextIndex += 1
      results[index] = await operation(values[index], index)
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, values.length) }, worker))
  return results
}

function compilePattern(value) {
  return value ? new RegExp(value, 'i') : null
}

function technicalPattern(selectionPolicy) {
  const broadPattern = compilePattern(selectionPolicy.technicalPattern)
  return {
    broadPattern,
    computerTitlePattern: compilePattern(selectionPolicy.computerTitlePattern),
    hardExcludedTitlePattern: compilePattern(selectionPolicy.hardExcludedTitlePattern),
    targetRoleRules: (selectionPolicy.targetRoleRules ?? []).map(rule => ({
      ...rule,
      strongTitlePattern: compilePattern(rule.strongTitlePattern),
      titlePattern: compilePattern(rule.titlePattern),
      textPattern: compilePattern(rule.textPattern),
      excludedTitlePattern: compilePattern(rule.excludedTitlePattern)
    })),
    test(value) {
      return broadPattern?.test(String(value ?? '')) ?? false
    }
  }
}

function classifyTargetRole(record, pattern) {
  const jobTitle = cleanText(record.jobTitle)
  const category = cleanText(record.category)
  const rawText = cleanText(record.rawText).slice(0, 6000)
  const combinedText = `${jobTitle}\n${category}\n${rawText}`
  const directMatches = []
  const contextualMatches = []

  if (!jobTitle) {
    return {
      matchedRoleCodes: [],
      primaryTargetRoleCode: null,
      relevanceScore: 0,
      relevanceReasons: ['岗位名称为空'],
      relevanceStatus: 'REJECTED_NON_TARGET'
    }
  }
  if (pattern.hardExcludedTitlePattern?.test(jobTitle)) {
    return {
      matchedRoleCodes: [],
      primaryTargetRoleCode: null,
      relevanceScore: 0,
      relevanceReasons: ['岗位名称命中非计算机研发岗位排除词'],
      relevanceStatus: 'REJECTED_NON_TARGET'
    }
  }

  for (const rule of pattern.targetRoleRules) {
    if (rule.excludedTitlePattern?.test(jobTitle)) continue
    const direct = rule.strongTitlePattern?.test(jobTitle) ?? false
    const contextual = (rule.titlePattern?.test(jobTitle) ?? false) && (rule.textPattern?.test(combinedText) ?? false)
    if (direct) directMatches.push(rule)
    else if (contextual) contextualMatches.push(rule)
  }

  const matches = [...directMatches, ...contextualMatches]
  if (!(pattern.computerTitlePattern?.test(jobTitle) ?? false) && directMatches.length === 0) {
    return {
      matchedRoleCodes: [],
      primaryTargetRoleCode: null,
      relevanceScore: 0,
      relevanceReasons: ['岗位名称不是计算机研发、测试、平台或数据岗位'],
      relevanceStatus: 'REJECTED_NON_TARGET'
    }
  }
  if (!matches.length || !(pattern.broadPattern?.test(combinedText) ?? false)) {
    return {
      matchedRoleCodes: [],
      primaryTargetRoleCode: null,
      relevanceScore: 0,
      relevanceReasons: ['未匹配网站 AI、Java 后端、模型平台、数据知识、检索或 AI 质量安全岗位'],
      relevanceStatus: 'REJECTED_NON_TARGET'
    }
  }

  return {
    matchedRoleCodes: matches.map(rule => rule.code),
    primaryTargetRoleCode: matches[0].code,
    relevanceScore: directMatches.length ? 100 : 80,
    relevanceReasons: matches.map(rule => directMatches.includes(rule)
      ? `${rule.name}：岗位名称直接命中`
      : `${rule.name}：岗位名称与正文技能共同命中`),
    relevanceStatus: 'TARGET_ROLE_MATCH'
  }
}

function isTechnical(record, pattern) {
  const classification = classifyTargetRole(record, pattern)
  Object.assign(record, classification)
  return classification.relevanceStatus === 'TARGET_ROLE_MATCH'
}

const MAINLAND_LOCATION_PATTERN = new RegExp([
  '中国', '全国', '北京', '上海', '天津', '重庆',
  '河北', '山西', '辽宁', '吉林', '黑龙江', '江苏', '浙江', '安徽', '福建', '江西', '山东',
  '河南', '湖北', '湖南', '广东', '海南', '四川', '贵州', '云南', '陕西', '甘肃', '青海',
  '内蒙古', '广西', '西藏', '宁夏', '新疆',
  '石家庄', '唐山', '秦皇岛', '邯郸', '邢台', '保定', '张家口', '承德', '沧州', '廊坊', '衡水', '雄安',
  '太原', '大同', '阳泉', '长治', '晋城', '朔州', '晋中', '运城', '忻州', '临汾', '吕梁',
  '沈阳', '大连', '鞍山', '抚顺', '本溪', '丹东', '锦州', '营口', '阜新', '辽阳', '盘锦', '铁岭', '朝阳', '葫芦岛',
  '长春', '吉林市', '四平', '辽源', '通化', '白山', '松原', '白城', '延边',
  '哈尔滨', '齐齐哈尔', '鸡西', '鹤岗', '双鸭山', '大庆', '伊春', '佳木斯', '七台河', '牡丹江', '黑河', '绥化', '大兴安岭',
  '南京', '无锡', '徐州', '常州', '苏州', '南通', '连云港', '淮安', '盐城', '扬州', '镇江', '泰州', '宿迁',
  '杭州', '宁波', '温州', '嘉兴', '湖州', '绍兴', '金华', '衢州', '舟山', '台州', '丽水',
  '合肥', '芜湖', '蚌埠', '淮南', '马鞍山', '淮北', '铜陵', '安庆', '黄山', '滁州', '阜阳', '宿州', '六安', '亳州', '池州', '宣城',
  '福州', '厦门', '莆田', '三明', '泉州', '漳州', '南平', '龙岩', '宁德',
  '南昌', '景德镇', '萍乡', '九江', '新余', '鹰潭', '赣州', '吉安', '宜春', '抚州', '上饶',
  '济南', '青岛', '淄博', '枣庄', '东营', '烟台', '潍坊', '济宁', '泰安', '威海', '日照', '临沂', '德州', '聊城', '滨州', '菏泽',
  '郑州', '开封', '洛阳', '平顶山', '安阳', '鹤壁', '新乡', '焦作', '濮阳', '许昌', '漯河', '三门峡', '南阳', '商丘', '信阳', '周口', '驻马店', '济源',
  '武汉', '黄石', '十堰', '宜昌', '襄阳', '鄂州', '荆门', '孝感', '荆州', '黄冈', '咸宁', '随州', '恩施', '仙桃', '潜江', '天门', '神农架',
  '长沙', '株洲', '湘潭', '衡阳', '邵阳', '岳阳', '常德', '张家界', '益阳', '郴州', '永州', '怀化', '娄底', '湘西',
  '广州', '韶关', '深圳', '珠海', '汕头', '佛山', '江门', '湛江', '茂名', '肇庆', '惠州', '梅州', '汕尾', '河源', '阳江', '清远', '东莞', '中山', '潮州', '揭阳', '云浮',
  '海口', '三亚', '三沙', '儋州', '五指山', '琼海', '文昌', '万宁', '东方', '定安', '屯昌', '澄迈', '临高', '白沙', '昌江', '乐东', '陵水', '保亭', '琼中',
  '成都', '自贡', '攀枝花', '泸州', '德阳', '绵阳', '广元', '遂宁', '内江', '乐山', '南充', '眉山', '宜宾', '广安', '达州', '雅安', '巴中', '资阳', '阿坝', '甘孜', '凉山',
  '贵阳', '六盘水', '遵义', '安顺', '毕节', '铜仁', '黔西南', '黔东南', '黔南',
  '昆明', '曲靖', '玉溪', '保山', '昭通', '丽江', '普洱', '临沧', '楚雄', '红河', '文山', '西双版纳', '大理', '德宏', '怒江', '迪庆',
  '西安', '铜川', '宝鸡', '咸阳', '渭南', '延安', '汉中', '榆林', '安康', '商洛',
  '兰州', '嘉峪关', '金昌', '白银', '天水', '武威', '张掖', '平凉', '酒泉', '庆阳', '定西', '陇南', '临夏', '甘南',
  '西宁', '海东', '海北', '黄南', '海南州', '果洛', '玉树', '海西',
  '呼和浩特', '包头', '乌海', '赤峰', '通辽', '鄂尔多斯', '呼伦贝尔', '巴彦淖尔', '乌兰察布', '兴安盟', '锡林郭勒', '阿拉善',
  '南宁', '柳州', '桂林', '梧州', '北海', '防城港', '钦州', '贵港', '玉林', '百色', '贺州', '河池', '来宾', '崇左',
  '拉萨', '日喀则', '昌都', '林芝', '山南', '那曲', '阿里',
  '银川', '石嘴山', '吴忠', '固原', '中卫',
  '乌鲁木齐', '克拉玛依', '吐鲁番', '哈密', '昌吉', '博尔塔拉', '巴音郭楞', '阿克苏', '克孜勒苏', '喀什', '和田', '伊犁', '塔城', '阿勒泰', '石河子'
].join('|'))

function mainlandCity(value) {
  const city = cleanText(value)
  if (!city || /香港|澳门|台湾|海外|国外/.test(city)) return false
  const locations = city.split(/[、，,;/；|]+/).map(item => item.trim()).filter(Boolean)
  return locations.length > 0 && locations.every(location => MAINLAND_LOCATION_PATTERN.test(location))
}

function mainlandDistrict(items) {
  const values = Array.isArray(items) ? items : []
  return values.length > 0 && values.every(item => !item.country || item.country === '000000')
}

function iguopinLocationIsMainland(job, city) {
  return mainlandDistrict(job?.district_list) && mainlandCity(city)
}

async function collectIguopin(source, selectionPolicy) {
  const records = []
  const pattern = technicalPattern(selectionPolicy)
  const availableRecordKeys = new Set()
  const searchKeywords = source.searchKeywords?.length ? source.searchKeywords : ['']
  const pageCount = source.pageCountPerKeyword ?? source.pageCount
  for (const keyword of searchKeywords) {
    for (let page = 1; page <= pageCount; page += 1) {
      const search = { page, page_size: 20 }
      if (keyword) search.keyword = keyword
      const body = JSON.stringify({ search, recom: { update_time: true, company_nature: true, hot_job: true } })
      const loaded = await fetchJson(source.entryUrl, {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          Origin: 'https://www.iguopin.com',
          Referer: source.listingUrl
        },
        body
      })
      const jobs = loaded.data?.data?.list ?? []
      for (const job of jobs) {
        availableRecordKeys.add(cleanText(job.job_id))
        const company = cleanText(job.company_name || job.company_info?.show_name)
        const city = cleanText((job.district_list ?? []).map(item => item.area_cn || item.name || item.district_name || item.city_name).filter(Boolean).join('、'))
        const rawText = cleanText(job.contents)
        const normalized = {
          sourceRecordKey: cleanText(job.job_id),
          jobTitle: cleanText(job.job_name),
          company,
          companyBasis: '国聘公开职位中的用人单位字段',
          companyCountryBasis: '国聘中国招聘平台企业资料与中国大陆工作地点',
          city,
          category: cleanText(job.category_cn),
          jobLevel: inferJobLevel(job.job_name),
          jobExperience: cleanText(job.experience_cn),
          jobType: cleanText(job.recruitment_type_cn || job.nature_cn),
          publishedAt: normalizeDate(job.start_time || job.create_time || job.refresh_time),
          publishedAtOriginal: cleanText(job.start_time || job.create_time || job.refresh_time),
          publishedAtBasis: job.start_time ? '国聘公开职位开始时间' : '国聘公开职位创建或刷新时间',
          expiresAt: normalizeDate(job.end_time),
          sourceUrl: `https://www.iguopin.com/job/detail?id=${encodeURIComponent(job.job_id)}`,
          rawText
        }
        if (iguopinLocationIsMainland(job, city) && chineseCharacterCount(rawText) >= 80 && isTechnical(normalized, pattern)) records.push(normalized)
      }
    }
  }
  const unique = uniqueBy(records, record => record.sourceRecordKey)
  return {
    records: diversifiedLimit(unique, source.maximumRecords, source.maximumRecordsPerCompany),
    availableRecordCount: availableRecordKeys.size,
    matchingRecordCount: unique.length
  }
}

async function collectMeituan(source, selectionPolicy) {
  const records = []
  const pattern = technicalPattern(selectionPolicy)
  let availableRecordCount = 0
  for (let pageNo = 1; pageNo <= source.pageCount; pageNo += 1) {
    const body = JSON.stringify({
      page: { pageNo, pageSize: 100 },
      jobShareType: '1',
      keywords: '',
      cityList: [],
      department: [],
      jfJgList: [],
      jobType: [{ code: '3', subCode: [] }],
      typeCode: [],
      specialCode: []
    })
    const loaded = await fetchJson(source.entryUrl, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json;charset=UTF-8',
        Origin: 'https://zhaopin.meituan.com',
        Referer: source.listingUrl
      },
      body
    })
    const jobs = loaded.data?.data?.list ?? []
    availableRecordCount = Math.max(availableRecordCount, Number(loaded.data?.data?.page?.totalCount ?? 0))
    for (const job of jobs) {
      const city = cleanText((job.cityList ?? []).map(item => item.name).filter(Boolean).join('、'))
      const rawText = cleanText([
        job.jobDuty ? `岗位职责：\n${job.jobDuty}` : '',
        job.jobRequirement ? `任职要求：\n${job.jobRequirement}` : '',
        job.precedence ? `优先条件：\n${job.precedence}` : '',
        job.highLight ? `岗位亮点：\n${job.highLight}` : ''
      ].filter(Boolean).join('\n\n'))
      const normalized = {
        sourceRecordKey: cleanText(job.jobUnionId),
        jobTitle: cleanText(job.name),
        company: '北京三快在线科技有限公司（美团）',
        companyBasis: '美团招聘官网固定运营主体',
        companyCountryBasis: '美团中国招聘官网及中国大陆工作地点',
        city,
        category: cleanText(`${job.jobFamily ?? ''} ${job.jobFamilyGroup ?? ''}`),
        jobLevel: inferJobLevel(job.name),
        jobExperience: cleanText(job.workYear),
        jobType: '社会招聘',
        publishedAt: normalizeDate(job.firstPostTime || job.refreshTime),
        publishedAtOriginal: cleanText(job.firstPostTime || job.refreshTime),
        publishedAtBasis: job.firstPostTime ? '美团官网首次发布时间' : '美团官网刷新时间代理',
        expiresAt: normalizeDate(job.expiredTime),
        sourceUrl: `https://zhaopin.meituan.com/web/position/detail?jobUnionId=${encodeURIComponent(job.jobUnionId)}&highlightType=social`,
        rawText
      }
      if (mainlandCity(city) && chineseCharacterCount(rawText) >= 80 && isTechnical(normalized, pattern)) records.push(normalized)
    }
  }
  const unique = uniqueBy(records, record => record.sourceRecordKey)
  return { records: unique.slice(0, source.maximumRecords), availableRecordCount, matchingRecordCount: unique.length }
}

async function collectTencent(source, selectionPolicy) {
  const summaries = []
  let availableRecordCount = 0
  for (let pageIndex = 1; pageIndex <= source.pageCount; pageIndex += 1) {
    const url = new URL(source.entryUrl)
    url.searchParams.set('timestamp', String(Date.now()))
    url.searchParams.set('pageIndex', String(pageIndex))
    url.searchParams.set('pageSize', String(source.pageSize ?? 100))
    url.searchParams.set('language', 'zh-cn')
    url.searchParams.set('area', 'cn')
    const loaded = await fetchJson(url, {
      headers: { Accept: 'application/json', Referer: source.listingUrl }
    })
    const posts = loaded.data?.Data?.Posts ?? []
    availableRecordCount = Math.max(availableRecordCount, Number(loaded.data?.Data?.Count ?? 0))
    summaries.push(...posts.filter(post => post?.PostId && post?.CountryName === '中国' && mainlandCity(post?.LocationName)))
  }

  const pattern = technicalPattern(selectionPolicy)
  const likelyTechnical = uniqueBy(summaries, post => String(post.PostId))
    .filter(post => isTechnical({
      jobTitle: post.RecruitPostName,
      category: post.CategoryName,
      rawText: post.Responsibility
    }, pattern))
    .slice(0, source.maximumDetailRequests)

  const details = await mapLimit(likelyTechnical, 4, async summary => {
    try {
      const url = new URL(source.detailUrl)
      url.searchParams.set('timestamp', String(Date.now()))
      url.searchParams.set('postId', String(summary.PostId))
      url.searchParams.set('language', 'zh-cn')
      const loaded = await fetchJson(url, {
        headers: { Accept: 'application/json', Referer: source.listingUrl }
      })
      const post = loaded.data?.Data
      if (!post?.PostId) return null
      const rawText = cleanText([
        post.Responsibility ? `岗位职责：\n${post.Responsibility}` : '',
        post.Requirement ? `任职要求：\n${post.Requirement}` : ''
      ].filter(Boolean).join('\n\n'))
      const city = cleanText(post.LocationName || summary.LocationName)
      return {
        sourceRecordKey: cleanText(post.PostId),
        jobTitle: cleanText(post.RecruitPostName),
        company: source.companyName,
        companyBasis: '腾讯招聘官网固定运营主体',
        companyCountryBasis: '腾讯中国招聘官网国家字段为中国且工作地点为中国大陆城市',
        city,
        jobLevel: inferJobLevel(post.RecruitPostName),
        jobExperience: cleanText(post.RequireWorkYearsName),
        jobType: '社会招聘',
        publishedAt: normalizeDate(post.LastUpdateTime),
        publishedAtOriginal: cleanText(post.LastUpdateTime),
        publishedAtBasis: '腾讯招聘官网最后更新时间',
        expiresAt: null,
        sourceUrl: `https://careers.tencent.com/jobdesc.html?postId=${encodeURIComponent(post.PostId)}`,
        rawText
      }
    } catch {
      return null
    }
  })

  const records = details.filter(record => record && mainlandCity(record.city) && chineseCharacterCount(record.rawText) >= 80 && isTechnical(record, pattern))
  return {
    records: records.slice(0, source.maximumRecords),
    availableRecordCount,
    matchingRecordCount: records.length
  }
}

async function collectBaidu(source, selectionPolicy) {
  const loaded = await fetchText(source.entryUrl, {
    headers: { Accept: 'text/html', Referer: source.listingUrl }
  }, 5 * 1024 * 1024)
  const summaries = extractEmbeddedJson(loaded.text, '"listDetailData":', '[')
  const totalMatch = loaded.text.match(/"listDetailData":\[[\s\S]*?],"pageNum":\d+,"pageSize":\d+,"total":(\d+)/)
  const availableRecordCount = Number(totalMatch?.[1] ?? summaries.length)
  const pattern = technicalPattern(selectionPolicy)
  const likelyTechnical = uniqueBy(summaries, post => cleanText(post.postId))
    .filter(post => post.postId && mainlandCity(post.workPlace) && isTechnical({
      jobTitle: post.name,
      category: post.postType,
      rawText: `${post.workContent ?? ''}\n${post.serviceCondition ?? ''}`
    }, pattern))
    .slice(0, source.maximumDetailRequests)

  const details = await mapLimit(likelyTechnical, 3, async summary => {
    const sourceUrl = `${source.detailBaseUrl}/${encodeURIComponent(source.recruitType)}/${encodeURIComponent(summary.postId)}`
    try {
      const detail = await fetchText(sourceUrl, {
        headers: { Accept: 'text/html', Referer: source.listingUrl }
      }, 5 * 1024 * 1024)
      const detailData = extractEmbeddedJson(detail.text, '"detailData":', '{')
      const post = detailData?.postInfo
      if (!post?.postId || post.postId !== summary.postId || detailData.isValid !== true) return null
      const rawText = cleanText([
        post.workContent ? `岗位职责：\n${post.workContent}` : '',
        post.serviceCondition ? `任职要求：\n${post.serviceCondition}` : ''
      ].filter(Boolean).join('\n\n'))
      return {
        sourceRecordKey: cleanText(post.postId),
        jobTitle: cleanText(post.name),
        company: source.companyName,
        companyBasis: '百度招聘官网固定运营主体',
        companyCountryBasis: '百度中国招聘官网及中国大陆工作地点',
        city: cleanText(post.workPlace),
        category: cleanText(post.postType),
        jobLevel: inferJobLevel(post.name),
        jobExperience: cleanText(post.workYears),
        jobType: source.jobType,
        publishedAt: normalizeDate(post.publishDate || post.updateDate),
        publishedAtOriginal: cleanText(post.publishDate || post.updateDate),
        publishedAtBasis: post.publishDate ? '百度招聘官网发布时间' : '百度招聘官网更新时间代理',
        expiresAt: null,
        sourceUrl,
        rawText
      }
    } catch {
      return null
    }
  })

  const records = details.filter(record => record && mainlandCity(record.city) && chineseCharacterCount(record.rawText) >= 80 && isTechnical(record, pattern))
  return {
    records: records.slice(0, source.maximumRecords),
    availableRecordCount,
    matchingRecordCount: records.length
  }
}

function matchHtml(html, expression) {
  const match = String(html).match(expression)
  return cleanText(match?.[1] ?? '')
}

async function collectNcss(source, selectionPolicy) {
  const summaries = []
  const availableRecordKeys = new Set()
  const searchKeywords = source.searchKeywords?.length ? source.searchKeywords : ['']
  const pageCount = source.pageCountPerKeyword ?? source.pageCount
  for (const keyword of searchKeywords) {
    for (let offset = 1; offset <= pageCount; offset += 1) {
      const url = new URL(source.entryUrl)
      url.searchParams.set('offset', String(offset))
      url.searchParams.set('limit', '20')
      url.searchParams.set('sourcesName', '0')
      if (keyword) url.searchParams.set('jobName', keyword)
      const loaded = await fetchJson(url, { headers: { Accept: 'application/json', Referer: source.listingUrl } })
      const jobs = loaded.data?.data?.list ?? []
      for (const job of jobs) availableRecordKeys.add(cleanText(job.jobId))
      summaries.push(...jobs)
    }
  }
  const uniqueSummaries = uniqueBy(summaries, job => cleanText(job.jobId))
  const pattern = technicalPattern(selectionPolicy)
  const likelyTechnical = uniqueSummaries.filter(job => isTechnical({
    jobTitle: job.jobName,
    category: job.major,
    rawText: job.major
  }, pattern))
  const details = await mapLimit(likelyTechnical.slice(0, source.maximumDetailRequests), 4, async summary => {
    const sourceUrl = `https://www.ncss.cn/student/jobs/${encodeURIComponent(summary.jobId)}/detail.html`
    try {
      const loaded = await fetchText(sourceUrl, { headers: { Accept: 'text/html', Referer: source.listingUrl } }, 3 * 1024 * 1024)
      const rawText = matchHtml(loaded.text, /<pre[^>]*class="[^"]*mainContent[^"]*"[^>]*>([\s\S]*?)<\/pre>/i)
      const company = matchHtml(loaded.text, /id="realCorpName"[^>]*>([\s\S]*?)<\/[^>]+>/i) || cleanText(summary.recName)
      const city = matchHtml(loaded.text, /class="site-tag"[^>]*>([\s\S]*?)<\/div>/i) || cleanText(summary.areaCodeName)
      return {
        sourceRecordKey: cleanText(summary.jobId),
        jobTitle: cleanText(summary.jobName),
        company,
        companyBasis: '国家大学生就业服务平台公开职位中的用人单位字段',
        companyCountryBasis: '教育部主管的中国就业平台企业资料与中国大陆工作地点',
        city,
        category: cleanText(summary.major),
        jobLevel: inferJobLevel(summary.jobName),
        jobExperience: '',
        jobType: cleanText(summary.recruitType === '1' ? '招聘公告' : '校园或社会招聘'),
        publishedAt: normalizeDate(summary.publishDate || summary.updateDate),
        publishedAtOriginal: cleanText(summary.publishDate || summary.updateDate),
        publishedAtBasis: summary.publishDate ? '平台公开发布时间' : '平台更新时间代理',
        expiresAt: null,
        sourceUrl,
        rawText
      }
    } catch {
      return null
    }
  })
  const records = details.filter(record => record && mainlandCity(record.city) && /公司|集团|中心|研究院|研究所|学校|医院|事务所|机构|委员会/.test(record.company) && chineseCharacterCount(record.rawText) >= 80 && isTechnical(record, pattern))
  return { records: diversifiedLimit(records, source.maximumRecords, source.maximumRecordsPerCompany), availableRecordCount: availableRecordKeys.size, matchingRecordCount: records.length }
}

function normalizeVivoRecord(job, source = {}) {
  const locations = Array.isArray(job.job_location_list) ? job.job_location_list : []
  const city = cleanText(locations
    .map(location => location.city || location.city_name || location.cityName || location.name)
    .filter(Boolean)
    .join('、'))
  const minimumYears = Number.isFinite(Number(job.yoe_min)) ? Number(job.yoe_min) : null
  const maximumYears = Number.isFinite(Number(job.yoe_max)) ? Number(job.yoe_max) : null
  const jobExperience = minimumYears != null && maximumYears != null
    ? `${minimumYears}-${maximumYears}年`
    : minimumYears != null ? `${minimumYears}年以上` : ''
  const detailBaseUrl = source.detailBaseUrl || 'https://career.vivo.com/job-detail'
  const sourceUrl = new URL(detailBaseUrl)
  if (job.job_code) sourceUrl.searchParams.set('_irjc', cleanText(job.job_code))
  sourceUrl.searchParams.set('_irjid', cleanText(job.job_id))
  sourceUrl.searchParams.set('_collect', 'false')
  return {
    sourceRecordKey: cleanText(job.job_id),
    jobTitle: cleanText(job.job_title),
    company: source.companyName || '维沃移动通信有限公司（vivo）',
    companyBasis: 'vivo 招聘官网固定招聘主体',
    companyCountryBasis: 'vivo 中国招聘官网及中国大陆工作地点',
    city,
    category: cleanText(job.job_category),
    jobLevel: inferJobLevel(job.job_title),
    jobExperience,
    jobType: '社会招聘',
    publishedAt: normalizeDate(job.publish_timestamp),
    publishedAtOriginal: cleanText(job.publish_timestamp),
    publishedAtBasis: 'vivo 招聘官网发布时间',
    expiresAt: null,
    sourceUrl: sourceUrl.href,
    rawText: cleanText(job.job_desc)
  }
}

function normalizeJobonlineRecord(summary, detail, source = {}) {
  const sourceRecordKey = cleanText(detail.id || summary.id)
  const city = cleanText([
    detail.cityName || summary.cityName,
    detail.areaName || summary.areaName,
  ].filter(Boolean).join('-'))
  const rawText = cleanText([
    detail.description,
    detail.jobRequirements,
  ].filter(Boolean).join('\n'))
  const detailUrl = new URL(source.detailBaseUrl || 'https://www.jobonline.cn/positionDetail')
  detailUrl.searchParams.set('id', sourceRecordKey)
  const publishedAtOriginal = cleanText(detail.publishTime || summary.publishTime)
  return {
    sourceRecordKey,
    jobTitle: cleanText(detail.positionName || summary.positionName),
    company: cleanText(summary.companyName || detail.companyName || detail.companyBean?.companyName),
    companyBasis: '中国就业网公开职位中的招聘企业字段',
    companyCountryBasis: '人力资源和社会保障部门指导的中国就业平台及中国大陆工作地点',
    city,
    category: cleanText(detail.category || summary.category),
    jobLevel: inferJobLevel(detail.positionName || summary.positionName),
    jobExperience: cleanText(detail.jobAgeName || summary.jobAge),
    jobType: cleanText(detail.category || summary.category || '社会招聘'),
    publishedAt: normalizeDate(detail.publishTime || summary.publishTime),
    publishedAtOriginal,
    publishedAtBasis: '中国就业网公开职位发布时间',
    expiresAt: normalizeDate(detail.expireTime),
    sourceUrl: detailUrl.href,
    rawText,
  }
}

async function collectVivo(source, selectionPolicy) {
  const records = []
  const pattern = technicalPattern(selectionPolicy)
  let availableRecordCount = 0
  for (let page = 1; page <= source.pageCount; page += 1) {
    const body = JSON.stringify({
      city_code_list: [],
      company_id: 1,
      group_id: 1,
      user_id: null,
      job_category_id_list: [],
      keyword: '',
      max_results: source.pageSize ?? 100,
      page,
      yoe_list: [],
      loading: true
    })
    const loaded = await fetchJson(source.entryUrl, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Referer: source.listingUrl
      },
      body
    })
    const jobs = Array.isArray(loaded.data?.data) ? loaded.data.data : []
    availableRecordCount = Math.max(availableRecordCount, Number(loaded.data?.meta?.total ?? 0))
    for (const job of jobs) {
      const normalized = normalizeVivoRecord(job, source)
      if (mainlandCity(normalized.city) && chineseCharacterCount(normalized.rawText) >= 80 && isTechnical(normalized, pattern)) {
        records.push(normalized)
      }
    }
    if (jobs.length === 0 || page >= Number(loaded.data?.meta?.page_count ?? source.pageCount)) break
  }
  const unique = uniqueBy(records, record => record.sourceRecordKey)
  return {
    records: unique.slice(0, source.maximumRecords),
    availableRecordCount,
    matchingRecordCount: unique.length
  }
}

function normalizeSangforRecord(job, source = {}) {
  const minimumYears = Number.isFinite(Number(job.minExperience)) ? Number(job.minExperience) : null
  const maximumYears = Number.isFinite(Number(job.maxExperience)) ? Number(job.maxExperience) : null
  const jobExperience = minimumYears != null && maximumYears != null
    ? `${minimumYears}-${maximumYears}年`
    : minimumYears != null ? `${minimumYears}年以上` : ''
  const detailBaseUrl = String(source.detailBaseUrl || 'https://hr.sangfor.com/Delivery').replace(/\/$/, '')
  return {
    sourceRecordKey: cleanText(job.positionId),
    jobTitle: cleanText(job.title),
    company: source.companyName || '深信服科技股份有限公司',
    companyBasis: '深信服招聘官网固定招聘主体',
    companyCountryBasis: '深信服中国招聘官网及中国大陆工作地点',
    city: cleanText(job.locationText || job.workPlaceText),
    category: cleanText([job.functionName, job.departmentName].filter(Boolean).join(' ')),
    jobLevel: inferJobLevel(job.title),
    jobExperience,
    jobType: '社会招聘',
    publishedAt: normalizeDate(job.openedAt || job.appendTime),
    publishedAtOriginal: cleanText(job.openedAt || job.appendTime),
    publishedAtBasis: job.openedAt ? '深信服招聘官网开放时间' : '深信服招聘官网记录时间',
    expiresAt: null,
    sourceUrl: `${detailBaseUrl}/${encodeURIComponent(job.positionId)}`,
    rawText: cleanText(job.description)
  }
}

async function collectSangfor(source, selectionPolicy) {
  const tokenResult = await fetchJson(source.tokenUrl, {
    headers: { Accept: 'application/json', Referer: source.listingUrl }
  })
  const token = cleanText(tokenResult.data?.access_token)
  if (!token) throw new Error('深信服公开职位接口未返回匿名访问令牌')
  const loaded = await fetchJson(source.entryUrl, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json;charset=UTF-8',
      Referer: source.listingUrl
    },
    body: JSON.stringify({
      channelId: source.channelId ?? 110,
      page: 1,
      pageSize: source.pageSize ?? 500,
      departmentId: 0,
      functionId: 0,
      kw: '',
      locationId: 0,
      workPlaceId: 0
    })
  })
  const jobs = Array.isArray(loaded.data?.data?.listData) ? loaded.data.data.listData : []
  const pattern = technicalPattern(selectionPolicy)
  const records = jobs
    .map(job => normalizeSangforRecord(job, source))
    .filter(record => mainlandCity(record.city) && chineseCharacterCount(record.rawText) >= 80 && isTechnical(record, pattern))
  const unique = uniqueBy(records, record => record.sourceRecordKey)
  return {
    records: unique.slice(0, source.maximumRecords),
    availableRecordCount: Number(loaded.data?.data?.count ?? jobs.length),
    matchingRecordCount: unique.length
  }
}

async function collectJobonline(source, selectionPolicy) {
  const client = createJobonlineClient()
  const summaries = []
  const availableRecordKeys = new Set()
  const searchKeywords = source.searchKeywords?.length ? source.searchKeywords : ['']
  const listUrl = new URL(source.entryUrl)
  const listPath = `${listUrl.pathname}${listUrl.search}`
  for (const keyword of searchKeywords) {
    for (let page = 1; page <= source.pageCountPerKeyword; page += 1) {
      const loaded = await client.post(listPath, {
        target: 'INDEX_PAGE',
        param: {
          page,
          pagesize: source.pageSize ?? 50,
          positionName3: keyword,
          sortType: 1,
          category: ''
        }
      })
      if (String(loaded.code) !== '200') throw new Error(`中国就业网职位列表返回状态 ${loaded.code}`)
      const jobs = Array.isArray(loaded.object?.rows) ? loaded.object.rows : []
      for (const job of jobs) availableRecordKeys.add(cleanText(job.id))
      summaries.push(...jobs)
      if (jobs.length < (source.pageSize ?? 50)) break
    }
  }
  const uniqueSummaries = uniqueBy(summaries, job => cleanText(job.id))
  const pattern = technicalPattern(selectionPolicy)
  const likelyTechnical = uniqueSummaries.filter(job => isTechnical({
    jobTitle: job.positionName,
    category: job.category,
    rawText: job.positionName
  }, pattern))
  const details = await mapLimit(likelyTechnical.slice(0, source.maximumDetailRequests), 3, async summary => {
    try {
      const loaded = await client.post(source.detailPath, {
        positionId: summary.id,
        activityCode: '',
        businessEnum: 'POSITION_DETAIL'
      })
      if (String(loaded.code) !== '200' || !loaded.object) return null
      return normalizeJobonlineRecord(summary, loaded.object, source)
    } catch {
      return null
    }
  })
  const records = details.filter(record => record
    && record.company
    && mainlandCity(record.city)
    && chineseCharacterCount(record.rawText) >= 80
    && isTechnical(record, pattern))
  const unique = uniqueBy(records, record => record.sourceRecordKey)
  return {
    records: diversifiedLimit(unique, source.maximumRecords, source.maximumRecordsPerCompany),
    availableRecordCount: availableRecordKeys.size,
    matchingRecordCount: unique.length
  }
}

async function collectSource(source, selectionPolicy, collectedAt) {
  const startedAt = new Date().toISOString()
  try {
    const result = source.adapter === 'IGUOPIN' ? await collectIguopin(source, selectionPolicy)
      : source.adapter === 'MEITUAN' ? await collectMeituan(source, selectionPolicy)
      : source.adapter === 'TENCENT' ? await collectTencent(source, selectionPolicy)
        : source.adapter === 'BAIDU' ? await collectBaidu(source, selectionPolicy)
          : source.adapter === 'NCSS' ? await collectNcss(source, selectionPolicy)
            : source.adapter === 'VIVO' ? await collectVivo(source, selectionPolicy)
              : source.adapter === 'SANGFOR' ? await collectSangfor(source, selectionPolicy)
                : source.adapter === 'JOBONLINE' ? await collectJobonline(source, selectionPolicy)
                  : (() => { throw new Error(`未知适配器：${source.adapter}`) })()
    const records = result.records.map(record => enrichRecord(record, source, collectedAt))
    return {
      records,
      ledger: {
        sourceId: source.sourceId,
        sourceName: source.sourceName,
        sourcePlatform: source.sourcePlatform,
        sourceFamily: source.sourceFamily,
        sourceCountry: source.sourceCountry,
        sourceTier: source.sourceTier,
        sourceType: source.sourceType,
        entryUrl: source.entryUrl,
        listingUrl: source.listingUrl,
        licenseStatus: source.licenseStatus,
        licenseUrl: source.licenseUrl,
        licenseNote: source.licenseNote,
        collectionStartedAt: startedAt,
        collectedAt,
        availableRecordCount: result.availableRecordCount,
        matchingTechnicalRecordCount: result.matchingRecordCount,
        matchingTargetRoleRecordCount: result.matchingRecordCount,
        selectedRecordCount: records.length,
        status: 'COLLECTED_PENDING_REVIEW'
      }
    }
  } catch (error) {
    return {
      records: [],
      ledger: {
        sourceId: source.sourceId,
        sourceName: source.sourceName,
        sourcePlatform: source.sourcePlatform,
        sourceFamily: source.sourceFamily,
        sourceCountry: source.sourceCountry,
        sourceTier: source.sourceTier,
        sourceType: source.sourceType,
        entryUrl: source.entryUrl,
        listingUrl: source.listingUrl,
        licenseStatus: source.licenseStatus,
        licenseUrl: source.licenseUrl,
        licenseNote: source.licenseNote,
        collectionStartedAt: startedAt,
        collectedAt,
        availableRecordCount: 0,
        matchingTechnicalRecordCount: 0,
        matchingTargetRoleRecordCount: 0,
        selectedRecordCount: 0,
        status: 'COLLECTION_FAILED',
        error: String(error?.message ?? error)
      }
    }
  }
}

function countBy(records, key) {
  return Object.fromEntries([...records.reduce((counts, record) => counts.set(record[key], (counts.get(record[key]) ?? 0) + 1), new Map())]
    .sort(([left], [right]) => String(left).localeCompare(String(right), 'zh-CN')))
}

function countTargetRoles(records) {
  const counts = new Map()
  for (const record of records) {
    for (const code of record.matchedRoleCodes ?? []) counts.set(code, (counts.get(code) ?? 0) + 1)
  }
  return Object.fromEntries([...counts].sort(([left], [right]) => left.localeCompare(right)))
}

async function writeJsonLines(path, records) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, records.map(record => JSON.stringify(record)).join('\n') + (records.length ? '\n' : ''), 'utf8')
}

function csvValue(value) {
  return `"${String(value ?? '').replace(/"/g, '""')}"`
}

async function writeReviewTemplate(path, records) {
  const headers = [
    '记录编号', '来源平台', '企业', '岗位名称', '网站目标岗位代码', '主岗位代码', '自动相关性分数',
    '城市', '发布时间', '原文URL', '正文SHA256', '审核结论(ACCEPT/REJECT)', '审核说明', '审核时间', '处理状态'
  ]
  const rows = records.map(record => [
    record.recordId, record.sourcePlatform, record.company, record.jobTitle, record.matchedRoleCodes.join('|'),
    record.primaryTargetRoleCode, record.relevanceScore, record.city, record.publishedAt, record.sourceUrl,
    record.contentHash, '', '', '', '待审核'
  ])
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, `\ufeff${[headers, ...rows].map(row => row.map(csvValue).join(',')).join('\r\n')}\r\n`, 'utf8')
}

async function hashFiles(root, paths) {
  const hashes = {}
  for (const path of paths) hashes[relative(root, path).replace(/\\/g, '/')] = sha256(await readFile(path))
  return hashes
}

async function buildCorpus({ manifestPath = DEFAULT_MANIFEST, outputDirectory = DEFAULT_OUTPUT, now = new Date() } = {}) {
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
  if (manifest.schemaVersion !== '3.0.0' || manifest.sources?.length < 3) throw new Error('中国 JD 来源清单必须使用 schemaVersion 3.0.0 且至少配置 3 个来源')
  if (new Set(manifest.sources.map(source => source.sourceFamily)).size < 3) throw new Error('中国 JD 来源清单必须覆盖至少 3 类独立来源')
  if (manifest.sources.some(source => source.sourceCountry !== 'CN')) throw new Error('来源清单中存在非中国来源')

  const collectedAt = now.toISOString()
  const sourceResults = await Promise.all(manifest.sources.map(source => collectSource(source, manifest.selectionPolicy, collectedAt)))
  const candidates = sourceResults.flatMap(result => result.records).sort((left, right) => left.recordId.localeCompare(right.recordId))
  const structurallyQualified = candidates.filter(record => record.qualityGate.passed)
  const rejected = candidates.filter(record => !record.qualityGate.passed)
  const { unique, exactDuplicates } = deduplicateRecords(structurallyQualified)
  const nearDuplicates = findNearDuplicates(unique)
  const splits = partitionRecords(unique, nearDuplicates)
  const companies = new Set(unique.map(record => record.company.toLowerCase()))
  const companyBoards = new Set(unique.map(record => record.companyBoardId))
  const sourcePlatforms = new Set(unique.map(record => record.sourcePlatform))
  const sourceFamilies = new Set(unique.map(record => record.sourceFamily))
  const foreignRecords = unique.filter(record => record.sourceCountry !== 'CN' || record.companyCountry !== 'CN' || record.workCountry !== 'CN' || !mainlandCity(record.city))
  const nonChineseRecords = unique.filter(record => record.language !== 'zh-CN' || chineseCharacterCount(record.rawText) < 80)
  const nonTargetRecords = unique.filter(record => record.relevanceStatus !== 'TARGET_ROLE_MATCH' || !record.matchedRoleCodes?.length)

  const files = {
    candidates: resolve(outputDirectory, 'candidate.jsonl'),
    qualified: resolve(outputDirectory, 'qualified.jsonl'),
    rejected: resolve(outputDirectory, 'rejected.jsonl'),
    exactDuplicates: resolve(outputDirectory, 'duplicates/exact.jsonl'),
    nearDuplicates: resolve(outputDirectory, 'duplicates/near.jsonl'),
    development: resolve(outputDirectory, 'splits/development.jsonl'),
    validation: resolve(outputDirectory, 'splits/validation.jsonl'),
    test: resolve(outputDirectory, 'splits/test.jsonl'),
    goldenRegressionCandidates: resolve(outputDirectory, 'splits/golden-regression-candidates.jsonl'),
    sourceLedger: resolve(outputDirectory, 'source-ledger.json'),
    reviewTemplate: resolve(outputDirectory, 'review/annotation-template.csv'),
    reviewGuide: resolve(outputDirectory, 'review/人工审核说明.md'),
    collectionReport: resolve(outputDirectory, '采集结果报告.md')
  }
  await writeJsonLines(files.candidates, candidates)
  await writeJsonLines(files.qualified, unique)
  await writeJsonLines(files.rejected, rejected)
  await writeJsonLines(files.exactDuplicates, exactDuplicates)
  await writeJsonLines(files.nearDuplicates, nearDuplicates)
  await writeJsonLines(files.development, splits.development)
  await writeJsonLines(files.validation, splits.validation)
  await writeJsonLines(files.test, splits.test)
  await writeJsonLines(files.goldenRegressionCandidates, splits.goldenRegressionCandidates)
  await writeFile(files.sourceLedger, `${JSON.stringify({ schemaVersion: '3.0.0', collectedAt, sources: sourceResults.map(result => result.ledger) }, null, 2)}\n`, 'utf8')
  await writeReviewTemplate(files.reviewTemplate, unique)

  const reviewGuide = `# JD 审核说明

审核主文件：\`annotation-template.csv\`。该文件已按每条去重后 JD 生成一行，使用 Excel 或 WPS 打开即可。

## 审核规则

1. 核对原文 URL、来源域名、企业、城市、发布时间、正文和 SHA-256。
2. 核对岗位属于网站十二个目标计算机岗位之一，来源、企业和工作地点均为中国大陆，正文为中文原文。
3. 无异常记录填写 \`ACCEPT\`；确有异常才填写 \`REJECT\`，并写明原因。
4. 审核完成后运行 \`scripts/finalize-p0-1.mjs\`，生成正式黄金集、版本、冻结时间和哈希。
5. 以统一、可复核的审核规则和逐条结论作为完成依据。

## 防误用说明

- 本批数据只来自中国平台或中国企业官网，正文为来源页面的中文原文，不是国外 JD 翻译件。
- 正式审核表只包含 AI 应用、Java 后端、模型平台、数据与知识工程、检索、AI 评测与安全测试方向；运营、销售、客服、行政、生产制造等岗位不得接受。
- 采集阶段只生成待审核数据；冻结脚本完成一致性检查后才升级为正式语料。
`
  await writeFile(files.reviewGuide, reviewGuide, 'utf8')

  const sourceRows = Object.entries(countBy(unique, 'sourcePlatform')).map(([source, count]) => `| ${source} | ${count} |`).join('\n')
  const companyRows = Object.entries(countBy(unique, 'company')).map(([company, count]) => `| ${company} | ${count} |`).join('\n')
  const roleRows = Object.entries(countTargetRoles(unique)).map(([code, count]) => `| ${code} | ${count} |`).join('\n')
  const acceptancePassed = candidates.length >= manifest.requirements.candidateMinimum && unique.length >= manifest.requirements.structurallyQualifiedMinimum && companies.size >= manifest.requirements.companyMinimum && sourceFamilies.size >= manifest.requirements.independentSourceMinimum && foreignRecords.length === 0 && nonChineseRecords.length === 0 && nonTargetRecords.length === 0
  const report = `# 中国大陆 JD 采集结果报告

冻结时间：${collectedAt}  
状态：\`PENDING_REVIEW\`

## 结果概览

| 指标 | 结果 | 自动门槛 |
|---|---:|---:|
| 候选 JD | ${candidates.length} | ≥ ${manifest.requirements.candidateMinimum} |
| 去重后结构合格 JD | ${unique.length} | ≥ ${manifest.requirements.structurallyQualifiedMinimum} |
| 中国大陆企业 | ${companies.size} | ≥ ${manifest.requirements.companyMinimum} |
| 独立中国来源 | ${sourceFamilies.size} | ≥ ${manifest.requirements.independentSourceMinimum} |
| 非中国来源、企业或工作地点记录 | ${foreignRecords.length} | = 0 |
| 非中文原文记录 | ${nonChineseRecords.length} | = 0 |
| 非网站目标计算机岗位记录 | ${nonTargetRecords.length} | = 0 |
| 精确重复 | ${exactDuplicates.length} | — |
| 近重复关系（待人工确认） | ${nearDuplicates.length} | — |

## 中国来源覆盖

| 来源平台 | 去重后记录数 |
|---|---:|
${sourceRows}

## 网站目标岗位覆盖

一条 JD 可同时命中多个相邻岗位，因此下表合计可能大于去重后 JD 数。

| 网站岗位代码 | JD 数 |
|---|---:|
${roleRows}

## 企业覆盖

| 企业 | 去重后记录数 |
|---|---:|
${companyRows}

## 数据划分

- 开发集：${splits.development.length} 条
- 验证集：${splits.validation.length} 条
- 测试集：${splits.test.length} 条
- 黄金回归候选：${splits.goldenRegressionCandidates.length} 条

## 验收结论

- 自动结构验收：${acceptancePassed ? '**通过**' : '**未通过**'}。
- 所有正文均保持来源页面的中文原文，不包含把国外 JD 翻译成中文的记录。
- 合格记录同时满足来源国家、企业国家和工作地点均为中国；工作地点按来源结构化字段与中国大陆行政区白名单校验。
- 合格记录必须匹配网站十二个目标岗位之一，普通运营、销售、客服、行政、生产制造等岗位不会进入正式审核表。
- 审核从 \`review/annotation-template.csv\` 开始；冻结前当前数据不是正式黄金集。
`
  await writeFile(files.collectionReport, report, 'utf8')

  const outputHashes = await hashFiles(outputDirectory, Object.values(files))
  const corpusManifest = {
    schemaVersion: '3.0.0',
    corpusVersion: `cn-jd-corpus-${collectedAt.replace(/[-:.]/g, '')}`,
    frozenAt: collectedAt,
    status: 'PENDING_REVIEW',
    qualificationMeaning: '仅结构合格，完成一致性审核和冻结后升级为正式语料',
    requirements: manifest.requirements,
    counts: {
      candidates: candidates.length,
      structurallyQualifiedBeforeExactDedup: structurallyQualified.length,
      qualifiedAfterExactDedup: unique.length,
      rejected: rejected.length,
      exactDuplicates: exactDuplicates.length,
      nearDuplicateRelations: nearDuplicates.length,
      companies: companies.size,
      companyBoards: companyBoards.size,
      sourcePlatforms: sourcePlatforms.size,
      independentSourceFamilies: sourceFamilies.size,
      foreignRecords: foreignRecords.length,
      nonChineseRecords: nonChineseRecords.length,
      nonTargetRecords: nonTargetRecords.length,
      historicalRecords: 0,
      splits: Object.fromEntries(Object.entries(splits).map(([key, values]) => [key, values.length]))
    },
    coverage: {
      bySource: countBy(unique, 'sourceId'),
      bySourcePlatform: countBy(unique, 'sourcePlatform'),
      bySourceFamily: countBy(unique, 'sourceFamily'),
      byCompany: countBy(unique, 'company'),
      byJobLevel: countBy(unique, 'jobLevel'),
      byTargetRole: countTargetRoles(unique)
    },
    automatedAcceptance: {
      candidateMinimumMet: candidates.length >= manifest.requirements.candidateMinimum,
      structurallyQualifiedMinimumMet: unique.length >= manifest.requirements.structurallyQualifiedMinimum,
      companyMinimumMet: companies.size >= manifest.requirements.companyMinimum,
      independentSourceMinimumMet: sourceFamilies.size >= manifest.requirements.independentSourceMinimum,
      foreignRecordsZero: foreignRecords.length === 0,
      chineseOriginalTextOnly: nonChineseRecords.length === 0,
      targetComputerRolesOnly: nonTargetRecords.length === 0,
      allRequirementsMet: acceptancePassed,
      note: '自动验收完成结构门禁；正式冻结前仍需执行统一的一致性审核。'
    },
    splitPolicy: {
      development: '近重复连通组哈希桶 0-59',
      validation: '近重复连通组哈希桶 60-74',
      test: '近重复连通组哈希桶 75-94',
      goldenRegressionCandidates: '近重复连通组哈希桶 95-99；一致性审核通过并冻结后升级为黄金集',
      leakageControl: '精确重复先移除；近重复连通组整体进入同一数据集',
      nearDuplicateRule: '正文长度比 ≥ 0.85、SimHash 汉明距离 ≤ 6、字符三元组 Jaccard ≥ 0.90'
    },
    outputFileHashes: outputHashes
  }
  const manifestOutput = resolve(outputDirectory, 'manifest.json')
  await writeFile(manifestOutput, `${JSON.stringify(corpusManifest, null, 2)}\n`, 'utf8')
  const checksumLines = Object.entries({ ...outputHashes, 'manifest.json': sha256(await readFile(manifestOutput)) })
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, hash]) => `${hash}  ${name}`)
  await writeFile(resolve(outputDirectory, 'checksums.sha256'), `${checksumLines.join('\n')}\n`, 'utf8')
  return corpusManifest
}

function parseArguments(argumentsList) {
  const options = {}
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index]
    if (argument === '--refresh' || argument === '--refresh-ats') continue
    if (argument === '--manifest') options.manifestPath = resolve(argumentsList[++index])
    else if (argument === '--output') options.outputDirectory = resolve(argumentsList[++index])
    else throw new Error(`未知参数：${argument}`)
  }
  return options
}

export {
  buildCorpus,
  classifyTargetRole,
  cleanText,
  deduplicateRecords,
  extractEmbeddedJson,
  inferJobLevel,
  iguopinLocationIsMainland,
  mainlandCity,
  normalizeJobonlineRecord,
  normalizeSangforRecord,
  normalizeVivoRecord,
  normalizeDate,
  partitionRecords,
  qualityGate,
  technicalPattern
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (import.meta.url === invokedPath) {
  buildCorpus(parseArguments(process.argv.slice(2)))
    .then(result => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`))
    .catch(error => {
      process.stderr.write(`${error.stack ?? error.message}\n`)
      process.exitCode = 1
    })
}
