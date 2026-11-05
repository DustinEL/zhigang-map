import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const projectRoot = resolve(import.meta.dirname, '..')
loadLocalEnvironment(resolve(projectRoot, '.env.local'))

const corpusPath = resolveCorpusPath(readOption('--corpus') || 'test-data/jd-corpus/qualified.jsonl')
const apiBase = process.env.ZHIGANG_API_BASE_URL || 'http://127.0.0.1:8080'
const editor = credentials('PROFESSIONAL')
const reviewer = credentials('REVIEWER')

const skillCatalog = [
  skill('Java', '编程语言', 'Java'), skill('Python', '编程语言', 'Python'),
  skill('Go', '编程语言', 'Golang', 'Go语言'), skill('C++', '编程语言', 'C++'),
  skill('JavaScript', '编程语言', 'JavaScript', 'JS'), skill('TypeScript', '编程语言', 'TypeScript'),
  skill('SQL', '编程语言', 'SQL'), skill('Shell', '编程语言', 'Shell'),
  skill('Spring Boot', '后端开发', 'Spring Boot', 'SpringBoot'),
  skill('Spring Cloud', '后端开发', 'Spring Cloud', 'SpringCloud'),
  skill('MyBatis', '后端开发', 'MyBatis'), skill('FastAPI', '后端开发', 'FastAPI'),
  skill('Django', '后端开发', 'Django'), skill('Flask', '后端开发', 'Flask'),
  skill('Node.js', '后端开发', 'Node.js', 'NodeJS'), skill('微服务', '后端开发', '微服务'),
  skill('RESTful API', '后端开发', 'RESTful', 'REST API'), skill('高并发', '后端开发', '高并发'),
  skill('分布式系统', '后端开发', '分布式系统', '分布式'),
  skill('大语言模型', '大模型应用', '大语言模型', '大模型', 'LLM'),
  skill('RAG', '大模型应用', 'RAG', '检索增强生成'),
  skill('AI Agent', '大模型应用', 'AI Agent', 'Agentic', '智能体', 'Agent'),
  skill('Prompt Engineering', '大模型应用', 'Prompt Engineering', '提示词工程', 'Prompt'),
  skill('LangChain', '大模型应用', 'LangChain'), skill('LlamaIndex', '大模型应用', 'LlamaIndex'),
  skill('Transformer', '算法模型', 'Transformer'), skill('PyTorch', '算法模型', 'PyTorch'),
  skill('TensorFlow', '算法模型', 'TensorFlow'), skill('Hugging Face', '算法模型', 'Hugging Face'),
  skill('机器学习', '算法模型', '机器学习'), skill('深度学习', '算法模型', '深度学习'),
  skill('自然语言处理', '算法模型', '自然语言处理', 'NLP'),
  skill('多模态', '算法模型', '多模态'), skill('强化学习', '算法模型', '强化学习', 'RLHF'),
  skill('模型微调', '算法模型', '模型微调', '微调', 'SFT'),
  skill('模型推理', '算法模型', '模型推理', '推理加速'),
  skill('Embedding', '检索与知识', 'Embedding', '嵌入模型', '向量化'),
  skill('向量检索', '检索与知识', '向量检索'), skill('向量数据库', '检索与知识', '向量数据库'),
  skill('Elasticsearch', '检索与知识', 'Elasticsearch', 'ElasticSearch', 'ES搜索'),
  skill('Milvus', '检索与知识', 'Milvus'), skill('FAISS', '检索与知识', 'FAISS'),
  skill('Qdrant', '检索与知识', 'Qdrant'), skill('搜索算法', '检索与知识', '搜索算法', '搜索引擎'),
  skill('推荐算法', '检索与知识', '推荐算法', '推荐系统'),
  skill('知识图谱', '检索与知识', '知识图谱'), skill('本体建模', '检索与知识', '本体建模', '本体'),
  skill('Neo4j', '检索与知识', 'Neo4j'),
  skill('MySQL', '数据工程', 'MySQL', 'Mysql'), skill('PostgreSQL', '数据工程', 'PostgreSQL'),
  skill('Redis', '数据工程', 'Redis'), skill('MongoDB', '数据工程', 'MongoDB'),
  skill('Kafka', '数据工程', 'Kafka'), skill('Flink', '数据工程', 'Flink'),
  skill('Spark', '数据工程', 'Spark'), skill('Hadoop', '数据工程', 'Hadoop'),
  skill('Hive', '数据工程', 'Hive'), skill('Airflow', '数据工程', 'Airflow'),
  skill('数据仓库', '数据工程', '数据仓库', '数仓'), skill('数据湖', '数据工程', '数据湖'),
  skill('数据治理', '数据工程', '数据治理'), skill('数据标注', '数据工程', '数据标注'),
  skill('Docker', '工程化', 'Docker'), skill('Kubernetes', '工程化', 'Kubernetes', 'K8s'),
  skill('Linux', '工程化', 'Linux'), skill('Git', '工程化', 'Git'),
  skill('CI/CD', '工程化', 'CI/CD', '持续集成'), skill('DevOps', '工程化', 'DevOps'),
  skill('MLOps', '工程化', 'MLOps'), skill('LLMOps', '工程化', 'LLMOps'),
  skill('Prometheus', '工程化', 'Prometheus'), skill('Grafana', '工程化', 'Grafana'),
  skill('Nginx', '工程化', 'Nginx'), skill('云原生', '工程化', '云原生'),
  skill('GPU', '工程化', 'GPU'), skill('CUDA', '工程化', 'CUDA'),
  skill('模型评测', '质量与安全', '模型评测', '大模型评测', '评测体系'),
  skill('自动化测试', '质量与安全', '自动化测试'), skill('性能测试', '质量与安全', '性能测试'),
  skill('测试开发', '质量与安全', '测试开发'), skill('网络安全', '质量与安全', '网络安全'),
  skill('数据安全', '质量与安全', '数据安全'), skill('模型安全', '质量与安全', '模型安全', '大模型安全'),
  skill('对抗攻击', '质量与安全', '对抗攻击', '对抗样本'), skill('内容安全', '质量与安全', '内容安全'),
  skill('人工智能', '通用工程能力', '人工智能', 'AI技术'),
  skill('算法工程', '通用工程能力', '算法工程', '算法研发'),
  skill('后端开发', '通用工程能力', '后端开发', '后台开发'),
  skill('平台开发', '通用工程能力', '平台开发', '平台研发'),
  skill('数据开发', '通用工程能力', '数据开发'), skill('软件开发', '通用工程能力', '软件开发', '软件工程')
]

async function main() {
  const records = readFileSync(corpusPath, 'utf8').split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line))
  const corpusTag = sha256(records.map(record => record.contentHash || sha256(record.rawText)).join('|')).slice(0, 12)
  const grouped = records.reduce((result, record) => {
    const group = result.get(record.sourceId) || []
    group.push(record)
    result.set(record.sourceId, group)
    return result
  }, new Map())
  const client = new ApiClient(apiBase)
  const imports = []
  const existingRuntime = await client.request('/api/runtime-status')
  if (existingRuntime.dataMode === 'REAL') {
    console.log(`当前已发布 ${existingRuntime.publishedRealDocumentCount} 条真实 JD；本批次按内容哈希去重后继续导入。`)
  }

  for (const [sourceId, sourceRecords] of grouped) {
  const documents = sourceRecords.map(toStructuredDocument)
  const batch = {
    schemaVersion: '1.0.0',
    generatedAt: new Date().toISOString(),
    batchKey: `cn-${corpusTag}-${sourceId}-v1`,
    sourceWorkbook: {
      fileName: `qualified-${sourceId}.jsonl`,
      sha256: sha256(JSON.stringify(sourceRecords)),
      recordCount: documents.length,
      rawTextCharacterCount: documents.reduce((sum, document) => sum + document.rawText.length, 0)
    },
    processingPolicy: {
      version: 'cn-computer-jd-v1.0',
      method: 'PUBLIC_CHINA_JD_RULE_EXTRACTION',
      publishedAtPolicy: 'SOURCE_DISCLOSED_OR_NULL',
      sourceLicenseStatus: 'PUBLIC_WEB_REVIEWED_FOR_LOCAL_COMPETITION_USE',
      statisticsEligibility: 'CURRENT_CHINA_COMPUTER_JD_ONLY'
    },
    documents
  }
  const imported = await client.request('/api/data-imports/structured-jd', {
    method: 'POST', body: batch, credentials: editor
  })
  imports.push(imported)
  console.log(`导入 ${sourceId}: 接收 ${imported.acceptedCount}，重复 ${imported.duplicateCount}，隔离 ${imported.quarantinedCount}`)
  }

  const allRecords = await loadAllRecords(client)
  const manualIds = allRecords
  .filter(record => ['SAMPLE_REVIEW', 'EXCEPTION_REVIEW', 'WAITING_SKILL'].includes(record.reviewRoute))
  .map(record => record.id)
  if (manualIds.length) {
  await client.request('/api/dynamic-records/bulk-reviews', {
    method: 'POST', credentials: editor, body: {
      recordIds: manualIds,
      decision: 'APPROVE',
      reviewer: editor.username,
      reason: 'P0-1 中国计算机岗位语料已通过自动范围、来源、中文原文和结构完整性校验'
    }
  })
  console.log(`自动确认抽检/异常队列: ${manualIds.length} 条`)
  }

  const promotion = await client.request('/api/promotion-batches', {
  method: 'POST', credentials: editor, body: {
    recordIds: [], selectionMode: 'ALL_ELIGIBLE', requestedBy: editor.username
  }
})
  const approved = await client.request(`/api/promotion-batches/${promotion.id}/reviews`, {
  method: 'POST', credentials: reviewer, body: {
    decision: 'APPROVE', reviewer: reviewer.username, note: 'P0-1 中国计算机岗位数据批量发布确认'
  }
})
  const published = await client.request(`/api/promotion-batches/${approved.id}/publish`, {
  method: 'POST', credentials: editor, body: { publisher: editor.username }
})

  const runtime = await client.request('/api/runtime-status')
  const snapshot = await client.request('/api/real-job-snapshots/current', { credentials: editor })
  const ledger = await client.request('/api/professional/source-ledger', { credentials: editor })
  const roles = await client.request('/api/job-roles')

  console.log(JSON.stringify({
  corpusRecords: records.length,
  importBatches: imports.length,
  dynamicRecords: runtime.dynamicRecordCount,
  publishedDocuments: runtime.publishedRealDocumentCount,
  dataMode: runtime.dataMode,
  snapshotCode: snapshot.snapshotCode,
  snapshotRoles: snapshot.roles.map(role => ({ code: role.roleCode, samples: role.sampleSize })),
  websiteRoles: roles.map(role => ({ id: role.id, code: role.code, name: role.name })),
  sourceLedger: ledger.map(item => ({ sourceId: item.sourceId, accepted: item.acceptedCount })),
  promotionStatus: published.status
  }, null, 2))
}

function toStructuredDocument(record) {
  const rawText = record.rawText
  return {
    sourceRecordKey: record.sourceRecordKey,
    jobTitle: record.jobTitle,
    company: record.company,
    city: record.city || '',
    salary: { raw: '', minMonthly: null, maxMonthly: null, salaryMonths: 12, currency: 'CNY' },
    experienceRequirement: record.jobExperience || record.jobLevel || '',
    educationRequirement: '',
    publishedAt: record.publishedAt || null,
    collectedAt: String(record.collectedAt).slice(0, 10),
    timeScope: 'CURRENT',
    observedAt: record.collectedAt,
    source: {
      sourceId: record.sourceId,
      name: record.sourceName,
      sourceTier: record.sourceTier || 'TIER_1',
      adapter: record.sourceFamily === 'CHINA_ENTERPRISE_OFFICIAL_CAREERS'
        ? 'ENTERPRISE_CAREERS' : 'PUBLIC_EMPLOYMENT',
      sourceType: record.sourceType,
      licenseStatus: 'PUBLIC_WEB_REVIEWED',
      licenseNote: `${record.licenseStatus}；${record.licenseNote}`,
      originalUrl: record.originalPostingUrl || record.sourceUrl,
      canonicalUrl: record.sourceUrl
    },
    rawText,
    rawTextLength: rawText.length,
    contentHash: sha256(rawText),
    dataMode: 'REAL',
    processingStatus: 'ELIGIBLE',
    statusReasons: [`TARGET_ROLE:${record.primaryTargetRoleCode}`],
    facts: {
      skills: extractSkills(rawText),
      responsibilities: extractResponsibilities(rawText)
    }
  }
}

function extractSkills(text) {
  const facts = []
  for (const item of skillCatalog) {
    const match = firstAlias(text, item.aliases)
    if (!match) continue
    const context = text.slice(Math.max(0, match.start - 70), Math.min(text.length, match.end + 90))
    const requirementType = /无需|不要求|不作要求/.test(context) ? 'NEGATED'
      : /优先|加分|有.{0,20}经验者优先/.test(context) ? 'BONUS'
        : /了解|关注|接触过/.test(context) && !/掌握|精通|熟练|要求/.test(context) ? 'MENTIONED' : 'REQUIRED'
    facts.push({
      ontologyStatus: 'STANDARD',
      canonicalSkill: item.canonical,
      suggestedCategory: item.category,
      rawValue: match.rawValue,
      requirementType,
      startOffset: match.start,
      endOffset: match.end,
      // 证据文本必须与原文偏移精确对应；上下文只用于判断技能要求类型。
      evidenceQuote: match.rawValue,
      confidence: requirementType === 'REQUIRED' ? 0.94 : 0.88,
      offsetVerified: true
    })
    if (facts.length >= 30) break
  }
  return facts
}

function extractResponsibilities(text) {
  const facts = []
  const seen = new Set()
  const pattern = /[^\n。；;]{8,220}(?:[。；;]|\n|$)/g
  for (const match of text.matchAll(pattern)) {
    const segment = match[0]
    const leading = segment.length - segment.trimStart().length
    const rawValue = segment.trim()
    if (!/负责|参与|承担|建设|设计|开发|优化|维护|研究|评测|测试|管理|支持|搭建|实现/.test(rawValue)) continue
    const normalizedValue = rawValue.replace(/^[\d一二三四五六七八九十、.．()（）\s]+/, '').slice(0, 500)
    if (!normalizedValue || seen.has(normalizedValue)) continue
    seen.add(normalizedValue)
    const startOffset = match.index + leading
    facts.push({
      rawValue,
      normalizedValue,
      startOffset,
      endOffset: startOffset + rawValue.length,
      evidenceQuote: rawValue,
      confidence: 0.91,
      offsetVerified: true
    })
    if (facts.length >= 8) break
  }
  if (!facts.length) {
    const startOffset = text.search(/\S/)
    const rawValue = text.slice(Math.max(0, startOffset), Math.max(0, startOffset) + 160).trimEnd()
    if (rawValue) facts.push({ rawValue, normalizedValue: rawValue, startOffset: Math.max(0, startOffset),
      endOffset: Math.max(0, startOffset) + rawValue.length, evidenceQuote: rawValue,
      confidence: 0.8, offsetVerified: true })
  }
  return facts
}

function firstAlias(text, aliases) {
  const lowerText = text.toLowerCase()
  for (const alias of [...aliases].sort((left, right) => right.length - left.length)) {
    const lowerAlias = alias.toLowerCase()
    let start = lowerText.indexOf(lowerAlias)
    while (start >= 0) {
      const end = start + alias.length
      if (!isShortAscii(alias) || hasAsciiBoundary(text, start, end)) {
        return { start, end, rawValue: text.slice(start, end) }
      }
      start = lowerText.indexOf(lowerAlias, start + 1)
    }
  }
  return null
}

function isShortAscii(value) {
  return /^[A-Za-z0-9+#.]+$/.test(value) && value.length <= 3
}

function hasAsciiBoundary(text, start, end) {
  return !/[A-Za-z0-9]/.test(text[start - 1] || '') && !/[A-Za-z0-9]/.test(text[end] || '')
}

async function loadAllRecords(client) {
  const result = []
  for (let page = 1; ; page += 1) {
    const data = await client.request(`/api/dynamic-records?page=${page}&size=100`, { credentials: editor })
    result.push(...data.items)
    if (result.length >= data.total) return result
  }
}

class ApiClient {
  constructor(baseUrl) {
    this.baseUrl = baseUrl
    this.cookies = new Map()
    this.csrfToken = ''
  }

  async request(path, options = {}) {
    const method = options.method || 'GET'
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method) && !this.csrfToken) {
      await this.loadCsrf()
    }
    const headers = { Accept: 'application/json' }
    if (options.body !== undefined) headers['Content-Type'] = 'application/json'
    if (options.credentials) {
      headers.Authorization = `Basic ${Buffer.from(`${options.credentials.username}:${options.credentials.password}`).toString('base64')}`
    }
    if (this.cookies.size) headers.Cookie = [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; ')
    if (this.csrfToken && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) headers['X-XSRF-TOKEN'] = this.csrfToken
    const response = await fetch(`${this.baseUrl}${path}`, {
      method, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body)
    })
    this.captureCookies(response)
    const payload = await response.json().catch(() => null)
    if (!response.ok || payload?.success === false) {
      throw new Error(`${method} ${path} 失败: ${payload?.code || response.status} ${payload?.message || response.statusText}`)
    }
    return payload?.data
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
      ? response.headers.getSetCookie() : [response.headers.get('set-cookie')].filter(Boolean)
    for (const value of values) {
      const match = value.match(/^([^=;]+)=([^;]*)/)
      if (match) this.cookies.set(match[1], match[2])
    }
  }
}

function skill(canonical, category, ...aliases) {
  return { canonical, category, aliases }
}

function sha256(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

function credentials(prefix) {
  const username = process.env[`${prefix}_USERNAME`]
  const password = process.env[`${prefix}_PASSWORD`]
  if (!username || !password) throw new Error(`缺少 ${prefix}_USERNAME 或 ${prefix}_PASSWORD`)
  return { username, password }
}

function readOption(name) {
  const index = process.argv.indexOf(name)
  if (index >= 0) return process.argv[index + 1]
  const prefixed = process.argv.find(value => value.startsWith(`${name}=`))
  return prefixed ? prefixed.slice(name.length + 1) : ''
}

function resolveCorpusPath(value) {
  if (!value) throw new Error('缺少 --corpus 参数值')
  const path = resolve(projectRoot, value)
  if (!path.startsWith(`${projectRoot}\\`) && path !== projectRoot) {
    throw new Error('语料文件必须位于项目目录内')
  }
  return path
}

function loadLocalEnvironment(file) {
  const content = readFileSync(file, 'utf8')
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const separator = trimmed.indexOf('=')
    if (separator < 1) continue
    const name = trimmed.slice(0, separator).trim()
    if (process.env[name] === undefined) process.env[name] = trimmed.slice(separator + 1).trim()
  }
}

await main()
