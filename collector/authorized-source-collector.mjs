import { createHash } from 'node:crypto'
import { lookup } from 'node:dns/promises'
import { readFile } from 'node:fs/promises'
import { isIP } from 'node:net'
import { basename, dirname, extname, resolve } from 'node:path'
import { inflateRawSync } from 'node:zlib'

export const SOURCE_TIERS = new Set(['TIER_1', 'TIER_2', 'TIER_3'])
export const TIME_SCOPES = new Set(['CURRENT', 'HISTORICAL', 'UNKNOWN_TIME'])
export const ADAPTERS = new Set([
  'MANUAL_BATCH',
  'STRUCTURED_DATASET',
  'OFFICIAL_ATS',
  'PUBLIC_EMPLOYMENT',
  'ENTERPRISE_CAREERS'
])

const PLACEHOLDER = /REPLACE|UNKNOWN|UNCONFIRMED|TODO|待补|未确认/i
const MAX_SOURCE_BYTES = 10 * 1024 * 1024

function requireValue(condition, message) {
  if (!condition) throw new Error(message)
}

function privateAddress(address) {
  const normalized = address.toLowerCase()
  if (normalized === '::1' || normalized.startsWith('fc') || normalized.startsWith('fd') || normalized.startsWith('fe80:')) return true
  return /^(10\.|127\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(normalized)
}

export async function assertPublicHttps(value) {
  const url = new URL(value)
  requireValue(url.protocol === 'https:', `只允许 HTTPS 数据源：${value}`)
  requireValue(!url.username && !url.password, `URL 中不能携带账号密码：${value}`)
  const hostname = url.hostname.toLowerCase()
  requireValue(hostname !== 'localhost' && !hostname.endsWith('.local') && !hostname.endsWith('.internal'), `禁止访问本机或内部域名：${hostname}`)
  if (isIP(hostname)) {
    requireValue(!privateAddress(hostname), `禁止访问私网地址：${hostname}`)
  } else {
    const addresses = await lookup(hostname, { all: true, verbatim: true })
    requireValue(addresses.length > 0 && addresses.every(item => !privateAddress(item.address)), `域名解析到了私网地址：${hostname}`)
  }
  return url
}

export function validateManifest(manifest) {
  requireValue(manifest?.schemaVersion === '2.0.0' && Array.isArray(manifest.sources), '数据源清单必须使用 schemaVersion 2.0.0 并包含 sources')
  for (const source of manifest.sources) {
    const required = ['sourceId', 'sourceName', 'sourceTier', 'adapter', 'timeScope', 'entryUrl', 'licenseStatus', 'licenseNote']
    requireValue(required.every(field => String(source[field] ?? '').trim()), `来源 ${source.sourceId ?? '(未命名)'} 缺少必填字段：${required.join('、')}`)
    requireValue(/^[A-Za-z0-9][A-Za-z0-9._:-]{2,63}$/.test(source.sourceId), `sourceId 格式不正确：${source.sourceId}`)
    requireValue(SOURCE_TIERS.has(source.sourceTier), `sourceTier 不受支持：${source.sourceTier}`)
    requireValue(ADAPTERS.has(source.adapter), `adapter 不受支持：${source.adapter}`)
    requireValue(TIME_SCOPES.has(source.timeScope), `timeScope 不受支持：${source.timeScope}`)
    requireValue(!PLACEHOLDER.test(source.licenseStatus) && !PLACEHOLDER.test(source.licenseNote), `来源 ${source.sourceId} 尚未填写有效许可依据`)
  }
  return manifest
}

function plainText(value) {
  return String(value ?? '')
    .replace(/<!\[CDATA\[([\s\S]*?)]]>/g, '$1')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ').trim()
}

function xmlValue(block, names) {
  for (const name of names) {
    const match = block.match(new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${name}>`, 'i'))
    if (match) return plainText(match[1])
  }
  return ''
}

export function parseFeed(body) {
  const blocks = [...body.matchAll(/<(?:item|entry)(?:\s[^>]*)?>([\s\S]*?)<\/(?:item|entry)>/gi)].map(match => match[1])
  return blocks.map((block, index) => {
    const title = xmlValue(block, ['title']) || `feed-entry-${index + 1}`
    const rawText = xmlValue(block, ['description', 'content', 'content:encoded', 'summary'])
    const link = block.match(/<link[^>]+href=["']([^"']+)["'][^>]*>/i)?.[1] || xmlValue(block, ['link', 'guid'])
    return { sourceRecordKey: link || `${title}-${index}`, jobTitle: title, company: '', city: '', publishedAt: xmlValue(block, ['pubDate', 'published', 'updated']) || null, sourceUrl: link, rawText }
  }).filter(document => document.rawText.length >= 20)
}

export function parseCsv(text) {
  const rows = []
  let row = []
  let field = ''
  let quoted = false
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index]
    if (quoted && character === '"' && text[index + 1] === '"') {
      field += '"'
      index += 1
    } else if (character === '"') {
      quoted = !quoted
    } else if (!quoted && character === ',') {
      row.push(field)
      field = ''
    } else if (!quoted && (character === '\n' || character === '\r')) {
      if (character === '\r' && text[index + 1] === '\n') index += 1
      row.push(field)
      if (row.some(value => value.trim())) rows.push(row)
      row = []
      field = ''
    } else {
      field += character
    }
  }
  row.push(field)
  if (row.some(value => value.trim())) rows.push(row)
  if (!rows.length) return []
  const headers = rows[0].map(value => value.replace(/^\uFEFF/, '').trim())
  return rows.slice(1).map(values => Object.fromEntries(headers.map((header, index) => [header, values[index] ?? ''])))
}

function unzipEntries(buffer) {
  let eocd = -1
  for (let index = buffer.length - 22; index >= Math.max(0, buffer.length - 65_557); index -= 1) {
    if (buffer.readUInt32LE(index) === 0x06054b50) { eocd = index; break }
  }
  requireValue(eocd >= 0, 'XLSX ZIP 目录损坏')
  const count = buffer.readUInt16LE(eocd + 10)
  let offset = buffer.readUInt32LE(eocd + 16)
  const entries = new Map()
  for (let index = 0; index < count; index += 1) {
    requireValue(buffer.readUInt32LE(offset) === 0x02014b50, 'XLSX ZIP 条目损坏')
    const method = buffer.readUInt16LE(offset + 10)
    const compressedSize = buffer.readUInt32LE(offset + 20)
    const nameLength = buffer.readUInt16LE(offset + 28)
    const extraLength = buffer.readUInt16LE(offset + 30)
    const commentLength = buffer.readUInt16LE(offset + 32)
    const localOffset = buffer.readUInt32LE(offset + 42)
    const name = buffer.subarray(offset + 46, offset + 46 + nameLength).toString('utf8')
    const localNameLength = buffer.readUInt16LE(localOffset + 26)
    const localExtraLength = buffer.readUInt16LE(localOffset + 28)
    const dataOffset = localOffset + 30 + localNameLength + localExtraLength
    const compressed = buffer.subarray(dataOffset, dataOffset + compressedSize)
    requireValue(method === 0 || method === 8, `XLSX 使用了不支持的压缩方式：${method}`)
    entries.set(name, method === 8 ? inflateRawSync(compressed) : compressed)
    offset += 46 + nameLength + extraLength + commentLength
  }
  return entries
}

function xmlText(value) {
  return String(value ?? '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
}

function columnIndex(reference) {
  const letters = reference.match(/^[A-Z]+/i)?.[0]?.toUpperCase() ?? 'A'
  return [...letters].reduce((total, letter) => total * 26 + letter.charCodeAt(0) - 64, 0) - 1
}

export function parseXlsx(buffer) {
  const entries = unzipEntries(buffer)
  const sharedXml = entries.get('xl/sharedStrings.xml')?.toString('utf8') ?? ''
  const shared = [...sharedXml.matchAll(/<si(?:\s[^>]*)?>([\s\S]*?)<\/si>/gi)]
    .map(match => [...match[1].matchAll(/<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/gi)].map(item => xmlText(item[1])).join(''))
  const sheetName = [...entries.keys()].filter(name => /^xl\/worksheets\/sheet\d+\.xml$/i.test(name)).sort()[0]
  requireValue(sheetName, 'XLSX 中没有工作表')
  const sheet = entries.get(sheetName).toString('utf8')
  const rows = [...sheet.matchAll(/<row(?:\s[^>]*)?>([\s\S]*?)<\/row>/gi)].map(rowMatch => {
    const values = []
    for (const cell of rowMatch[1].matchAll(/<c\s([^>]*)>([\s\S]*?)<\/c>/gi)) {
      const reference = cell[1].match(/\br=["']([^"']+)["']/i)?.[1] ?? 'A1'
      const type = cell[1].match(/\bt=["']([^"']+)["']/i)?.[1] ?? ''
      const raw = cell[2].match(/<v>([\s\S]*?)<\/v>/i)?.[1]
        ?? cell[2].match(/<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/i)?.[1] ?? ''
      values[columnIndex(reference)] = type === 's' ? shared[Number(raw)] ?? '' : xmlText(raw)
    }
    return values
  })
  if (!rows.length) return []
  const headers = rows[0].map(value => String(value ?? '').trim())
  return rows.slice(1).map(values => Object.fromEntries(headers.map((header, index) => [header, values[index] ?? ''])))
}

function value(item, aliases) {
  for (const alias of aliases) {
    if (item?.[alias] != null && String(item[alias]).trim()) return String(item[alias]).trim()
  }
  return ''
}

export function normalizeDocuments(items) {
  return items.map((item, index) => ({
    sourceRecordKey: value(item, ['sourceRecordKey', 'source_record_key', 'id', 'jobId', 'url', 'sourceUrl']) || `record-${index + 1}`,
    jobTitle: value(item, ['jobTitle', 'job_title', 'title', '岗位名称', '职位名称']),
    company: value(item, ['company', 'companyName', 'company_name', '企业名称', '公司名称']),
    city: value(item, ['city', 'location', '城市', '工作地点']),
    publishedAt: value(item, ['publishedAt', 'published_at', 'date', '发布时间']) || null,
    sourceUrl: value(item, ['sourceUrl', 'source_url', 'url', 'link']),
    rawText: value(item, ['rawText', 'raw_text', 'description', 'content', 'text', 'jd_text', '岗位正文', '职位描述'])
  })).filter(document => document.jobTitle && document.rawText.length >= 20)
}

function parseJson(body) {
  const parsed = JSON.parse(body)
  return Array.isArray(parsed) ? parsed : Array.isArray(parsed.documents) ? parsed.documents : Array.isArray(parsed.items) ? parsed.items : []
}

function canonicalUrl(value, fallback) {
  try {
    const url = new URL(value || fallback)
    if (url.protocol !== 'https:') return ''
    url.username = ''
    url.password = ''
    url.search = ''
    url.hash = ''
    return url.href
  } catch {
    return ''
  }
}

function contentFormat(source, entryUrl, contentType = '') {
  const configured = String(source.format ?? '').toLowerCase()
  if (configured) return configured
  const extension = extname(new URL(entryUrl, 'file:///').pathname).slice(1).toLowerCase()
  if (extension) return extension
  if (/rss|atom|xml/i.test(contentType)) return 'rss'
  if (/csv/i.test(contentType)) return 'csv'
  return 'json'
}

async function loadSource(source, manifestDirectory) {
  const remote = /^https:/i.test(source.entryUrl)
  if (!remote) {
    requireValue(['MANUAL_BATCH', 'STRUCTURED_DATASET'].includes(source.adapter), `${source.adapter} 只允许读取经确认的公网 HTTPS 来源`)
    const filePath = resolve(manifestDirectory, source.entryUrl)
    const buffer = await readFile(filePath)
    requireValue(buffer.byteLength <= MAX_SOURCE_BYTES, `本地来源超过 10MB：${filePath}`)
    return { buffer, finalUrl: filePath, format: contentFormat(source, `file:///${filePath.replaceAll('\\', '/')}`) }
  }
  const configuredUrl = await assertPublicHttps(source.entryUrl)
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20_000)
  try {
    const response = await fetch(configuredUrl, {
      redirect: 'manual', signal: controller.signal,
      headers: { Accept: 'application/json, text/csv, application/rss+xml, application/atom+xml, text/xml;q=0.9', 'User-Agent': 'ZhigangMapAuthorizedCollector/2.0 (+documented-permission-only)' }
    })
    requireValue(!(response.status >= 300 && response.status < 400), '采集器不自动跟随重定向，请在清单中填写最终获准地址')
    requireValue(response.ok, `HTTP ${response.status}`)
    requireValue(Number(response.headers.get('content-length') ?? 0) <= MAX_SOURCE_BYTES, '响应超过 10MB 限制')
    const buffer = Buffer.from(await response.arrayBuffer())
    requireValue(buffer.byteLength <= MAX_SOURCE_BYTES, '响应超过 10MB 限制')
    return { buffer, finalUrl: configuredUrl.href, format: contentFormat(source, configuredUrl.href, response.headers.get('content-type') ?? '') }
  } finally {
    clearTimeout(timeout)
  }
}

function fingerprintTokens(text) {
  const normalized = text.toLowerCase().replace(/\s+/g, '')
  const words = normalized.match(/[a-z0-9_+#.-]+/g) ?? []
  const chinese = normalized.replace(/[a-z0-9_+#.\-]/g, '')
  for (let index = 0; index + 2 <= chinese.length; index += 1) words.push(chinese.slice(index, index + 2))
  return words.length ? words : [normalized]
}

export function simHash(text) {
  const weights = Array(64).fill(0)
  for (const token of fingerprintTokens(text)) {
    const hash = createHash('sha256').update(token).digest()
    for (let bit = 0; bit < 64; bit += 1) weights[bit] += (hash[Math.floor(bit / 8)] & (1 << (bit % 8))) ? 1 : -1
  }
  let value = 0n
  weights.forEach((weight, bit) => { if (weight >= 0) value |= 1n << BigInt(bit) })
  return value.toString(16).padStart(16, '0')
}

function hamming(left, right) {
  let value = BigInt(`0x${left}`) ^ BigInt(`0x${right}`)
  let count = 0
  while (value) { count += Number(value & 1n); value >>= 1n }
  return count
}

export function deduplicateDocuments(documents) {
  const unique = []
  const exactDuplicates = []
  const byHash = new Map()
  for (const document of documents) {
    const existing = byHash.get(document.contentHash)
    if (existing) {
      exactDuplicates.push({ contentHash: document.contentHash, retainedSourceRecordKey: existing.sourceRecordKey, duplicateSourceRecordKey: document.sourceRecordKey })
    } else {
      byHash.set(document.contentHash, document)
      unique.push(document)
    }
  }
  const nearDuplicates = []
  for (let left = 0; left < unique.length; left += 1) {
    for (let right = left + 1; right < unique.length; right += 1) {
      const lengthRatio = Math.min(unique[left].rawText.length, unique[right].rawText.length) / Math.max(1, Math.max(unique[left].rawText.length, unique[right].rawText.length))
      const distance = hamming(unique[left].simHash, unique[right].simHash)
      if (lengthRatio >= 0.8 && distance <= 3) {
        nearDuplicates.push({ leftSourceRecordKey: unique[left].sourceRecordKey, rightSourceRecordKey: unique[right].sourceRecordKey, hammingDistance: distance, status: 'PENDING_HUMAN_CONFIRMATION' })
      }
    }
  }
  return { documents: unique, exactDuplicates, nearDuplicates }
}

async function collectSource(source, manifestDirectory, observedAt) {
  const loaded = await loadSource(source, manifestDirectory)
  let items
  if (loaded.format === 'xlsx') items = parseXlsx(loaded.buffer)
  else if (loaded.format === 'csv') items = parseCsv(loaded.buffer.toString('utf8'))
  else if (['rss', 'atom', 'xml'].includes(loaded.format)) return parseFeed(loaded.buffer.toString('utf8')).map(item => enrich(item, source, observedAt, loaded.finalUrl))
  else if (loaded.format === 'json') items = parseJson(loaded.buffer.toString('utf8'))
  else throw new Error(`不支持的数据格式：${loaded.format}`)
  return normalizeDocuments(items).map(item => enrich(item, source, observedAt, loaded.finalUrl))
}

function enrich(document, source, observedAt, entryUrl) {
  const contentHash = createHash('sha256').update(document.rawText).digest('hex')
  return {
    ...document,
    observedAt,
    timeScope: source.timeScope,
    contentHash,
    simHash: simHash(document.rawText),
    processingStatus: 'PENDING_LOCAL_REVIEW',
    source: {
      sourceId: source.sourceId,
      name: source.sourceName,
      sourceTier: source.sourceTier,
      adapter: source.adapter,
      sourceType: 'RECRUITMENT_JD',
      licenseStatus: source.licenseStatus,
      licenseNote: source.licenseNote,
      originalUrl: document.sourceUrl || entryUrl,
      canonicalUrl: canonicalUrl(document.sourceUrl, entryUrl)
    }
  }
}

export async function collectAuthorizedSources(manifest, manifestPath, now = new Date()) {
  validateManifest(manifest)
  const enabled = manifest.sources.filter(source => source.enabled)
  requireValue(enabled.length > 0, '没有启用的数据源；只可启用具有明确许可依据的来源')
  const observedAt = now.toISOString()
  const results = []
  for (const source of enabled) {
    const documents = await collectSource(source, dirname(manifestPath), observedAt)
    results.push({ sourceId: source.sourceId, collectedCount: documents.length, documents })
  }
  const deduplicated = deduplicateDocuments(results.flatMap(result => result.documents))
  const stamp = observedAt.replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z')
  return {
    schemaVersion: '2.0.0',
    batchKey: `authorized-${stamp}`,
    observedAt,
    status: 'PENDING_LOCAL_REVIEW',
    sourceManifest: basename(manifestPath),
    sourceResults: results.map(({ sourceId, collectedCount }) => ({ sourceId, collectedCount })),
    exactDuplicates: deduplicated.exactDuplicates,
    nearDuplicates: deduplicated.nearDuplicates,
    documents: deduplicated.documents
  }
}
