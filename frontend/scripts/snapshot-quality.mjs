import { createHash } from 'node:crypto'
import { lstatSync, realpathSync } from 'node:fs'
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path'

const mojibakePattern = /(?:�|ï¿½|(?:Ã|Â|â€|æ|ç|å)[\x80-\xff])/i
const placeholderPattern = /\?{3,}/
const emailPattern = /[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/
const mobilePattern = /(?<!\d)1[3-9]\d{9}(?!\d)/
const identityPattern = /(?<!\d)\d{17}[\dXx](?!\d)/
const emailGlobalPattern = /[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/g
const mobileGlobalPattern = /(?<!\d)1[3-9]\d{9}(?!\d)/g
const identityGlobalPattern = /(?<!\d)\d{17}[\dXx](?!\d)/g
const secretPatterns = [
  /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,
  /\bsk-[A-Za-z0-9_-]{16,}\b/,
  /\bAKIA[0-9A-Z]{16}\b/,
  /(?:OPENAI_API_KEY|SECRET_ACCESS_KEY|MYSQL_PASSWORD|PROFESSIONAL_PASSWORD)\s*[:=]/i
]
const windows1252Bytes = new Map([
  ['€', 0x80], ['‚', 0x82], ['ƒ', 0x83], ['„', 0x84], ['…', 0x85], ['†', 0x86], ['‡', 0x87],
  ['ˆ', 0x88], ['‰', 0x89], ['Š', 0x8a], ['‹', 0x8b], ['Œ', 0x8c], ['Ž', 0x8e], ['‘', 0x91],
  ['’', 0x92], ['“', 0x93], ['”', 0x94], ['•', 0x95], ['–', 0x96], ['—', 0x97], ['˜', 0x98],
  ['™', 0x99], ['š', 0x9a], ['›', 0x9b], ['œ', 0x9c], ['ž', 0x9e], ['Ÿ', 0x9f]
])

export function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

export function resolvePublicDataFile(dataRoot, publicPath) {
  if (typeof publicPath !== 'string' || !publicPath.startsWith('/data/')) {
    throw new Error(`快照数据文件必须位于 /data/ 白名单内：${String(publicPath)}`)
  }
  if (publicPath.includes('\\')) throw new Error(`快照数据文件路径不能包含反斜杠：${publicPath}`)
  const relativePath = publicPath.slice('/data/'.length)
  const segments = relativePath.split('/')
  if (!relativePath || relativePath.startsWith('/') || /^[A-Za-z]:/.test(relativePath)
      || segments.some(segment => !segment || segment === '.' || segment === '..')) {
    throw new Error(`快照数据文件路径不合法：${publicPath}`)
  }
  const root = resolve(dataRoot)
  const target = resolve(root, ...segments)
  const resolvedRelativePath = relative(root, target)
  if (!resolvedRelativePath || resolvedRelativePath === '..'
      || resolvedRelativePath.startsWith(`..${sep}`) || isAbsolute(resolvedRelativePath)) {
    throw new Error(`快照数据文件越出公开数据根目录：${publicPath}`)
  }

  const realRoot = realpathSync(root)
  let targetEntryExists = false
  try {
    const targetEntry = lstatSync(target)
    targetEntryExists = true
    if (targetEntry.isSymbolicLink()) {
      throw new Error(`快照数据文件不能是符号链接：${publicPath}`)
    }
  } catch (error) {
    if (!(error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT')) throw error
  }
  const realTarget = targetEntryExists ? realpathSync(target) : resolve(realpathSync(dirname(target)), basename(target))
  const realRelativePath = relative(realRoot, realTarget)
  if (!realRelativePath || realRelativePath === '..'
      || realRelativePath.startsWith(`..${sep}`) || isAbsolute(realRelativePath)) {
    throw new Error(`快照数据文件真实路径越出公开数据根目录：${publicPath}`)
  }
  return target
}

export function normalizePublicQuery(value) {
  return String(value ?? '').normalize('NFKC').trim().toLocaleLowerCase('zh-CN')
    .replace(/[\s·•・,，。.!！?？:：;；/\\|_—–-]+/g, '')
    .replace(/(?:工程师|岗位|职位)$/u, '')
}

export function hasBrokenText(value) {
  return placeholderPattern.test(value) || mojibakePattern.test(value)
}

export function repairMojibake(value) {
  if (!mojibakePattern.test(value)) return value
  const bytes = []
  for (const character of value) {
    const codePoint = character.codePointAt(0)
    if (codePoint <= 0xff) bytes.push(codePoint)
    else if (windows1252Bytes.has(character)) bytes.push(windows1252Bytes.get(character))
    else return value
  }
  const repaired = Buffer.from(bytes).toString('utf8')
  if (repaired.includes('�') || (repaired.match(mojibakePattern)?.length ?? 0) >= (value.match(mojibakePattern)?.length ?? 0)) return value
  return repaired
}

export function repairSnapshotText(value) {
  if (typeof value === 'string') return repairMojibake(value)
  if (Array.isArray(value)) return value.map(repairSnapshotText)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, repairSnapshotText(child)]))
}

function isTechnicalPath(path) {
  return /(?:^|\.)(?:[^.]*id|[^.]*hash|[^.]*key|[^.]*url|startOffset|endOffset|citation_id|sourceRecordKey|contentHash)$/i.test(path)
    || /(?:^|\.)issues\[\d+\]\.detail$/i.test(path)
}

export function redactSnapshotSensitiveText(value, path = '') {
  if (typeof value === 'string') {
    if (isTechnicalPath(path)) return value
    return value
      .replace(emailGlobalPattern, '[邮箱已脱敏]')
      .replace(mobileGlobalPattern, '[手机号已脱敏]')
      .replace(identityGlobalPattern, '[证件号已脱敏]')
  }
  if (Array.isArray(value)) return value.map((item, index) => redactSnapshotSensitiveText(item, `${path}[${index}]`))
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, redactSnapshotSensitiveText(child, path ? `${path}.${key}` : key)]))
}

export function isPublicEmergingQuery(item) {
  if (!item || typeof item !== 'object') return false
  const query = String(item.query ?? '').trim()
  const normalized = normalizePublicQuery(item.normalizedQuery || query)
  const evidenceStateValid = item.status !== 'WAITING_GENERATION'
    && (item.status !== 'EVIDENCE_INSUFFICIENT' || (Number(item.evidenceCount) > 0 && Number(item.sourceCount) > 0))
  return query.length > 0
    && normalized.length >= 2
    && evidenceStateValid
    && !hasBrokenText(query)
    && !hasBrokenText(String(item.normalizedQuery ?? ''))
    && !hasBrokenText(String(item.name ?? ''))
}

export function filterPublicEmergingQueries(items) {
  return Array.isArray(items) ? items.filter(isPublicEmergingQuery) : []
}

export function serializedTextIssues(content, label = 'snapshot') {
  const issues = []
  if (content.charCodeAt(0) === 0xfeff) issues.push(`${label}: 文件含 UTF-8 BOM`)
  if (/\\u[0-9a-f]{4}/i.test(content)) issues.push(`${label}: 业务文本含 Unicode 转义序列`)
  return issues
}

export function collectSnapshotQualityIssues(value, label = 'snapshot', maxIssues = 50) {
  const issues = []
  function add(message) {
    if (issues.length < maxIssues) issues.push(message)
  }
  function visit(item, path) {
    if (issues.length >= maxIssues || item == null) return
    if (typeof item === 'string') {
      if (hasBrokenText(item)) add(`${label}:${path}: 存在问号占位或乱码`)
      const technicalField = isTechnicalPath(path)
      if (!technicalField && emailPattern.test(item)) add(`${label}:${path}: 存在邮箱地址`)
      if (!technicalField && mobilePattern.test(item)) add(`${label}:${path}: 存在手机号`)
      if (!technicalField && identityPattern.test(item)) add(`${label}:${path}: 存在证件号码`)
      if (secretPatterns.some(pattern => pattern.test(item))) add(`${label}:${path}: 存在密钥或敏感配置`)
      return
    }
    if (Array.isArray(item)) {
      item.forEach((child, index) => visit(child, `${path}[${index}]`))
      return
    }
    if (typeof item !== 'object') return
    if ('query' in item || 'normalizedQuery' in item) {
      const query = String(item.query ?? '').trim()
      if (!query) add(`${label}:${path}.query: 查询为空`)
      if (!normalizePublicQuery(item.normalizedQuery || query)) add(`${label}:${path}.normalizedQuery: 规范化查询为空`)
    }
    Object.entries(item).forEach(([key, child]) => visit(child, path ? `${path}.${key}` : key))
  }
  visit(value, '')
  return issues
}
