export interface ParsedResumeSkill {
  skill: string
  level: number
  levelName: string
  quote: string
  sourceType: string
  confidence: number
}

export interface ParsedResumeText {
  name?: string
  education?: string
  major?: string
  abilities: ParsedResumeSkill[]
  projects: string[]
  certificates: string[]
}

const KNOWN_MAJORS = [
  '计算机科学与技术', '软件工程', '人工智能', '数据科学与大数据技术', '数据科学',
  '信息管理与信息系统', '网络工程', '物联网工程', '电子信息工程', '通信工程',
  '自动化', '数学与应用数学', '统计学', '电子商务'
]

const INVALID_MAJOR_WORDS = /(?:^|\s)(?:我|本人)|精通|熟悉|掌握|擅长|会使用|项目|技能|开发|负责/i
const MAJOR_SUFFIX = /(?:专业|工程|科学|技术|管理|信息系统|数学|统计学|自动化|电子商务)$/
const NEGATED_CONTEXT = /不(?:会|熟悉|掌握|了解|具备|使用)|未(?:接触|学习|使用|掌握)|没有(?:用过|学过)/
const VERIFIED_CONTEXT = /测试结果|验收|代码仓库|作品链接|上线|生产环境|获得证书|通过认证/
const PROJECT_CONTEXT = /项目|实习|工作经历|负责|实现|开发|搭建|系统|平台/
const LEARNING_CONTEXT = /课程|学习|了解|入门|培训|证书|认证/

function labeledValue(text: string, labels: string[]) {
  const label = labels.join('|')
  return text.match(new RegExp(`(?:^|\\n)\\s*(?:${label})\\s*[:：]\\s*([^\\n，,；;。]{1,40})`, 'i'))?.[1]?.trim()
}

function plausibleMajor(value?: string) {
  if (!value) return undefined
  const normalized = value.replace(/[。；;，,]+$/g, '').trim()
  if (INVALID_MAJOR_WORDS.test(normalized) || normalized.length < 2 || normalized.length > 24) return undefined
  return KNOWN_MAJORS.find(item => normalized.includes(item)) ?? (MAJOR_SUFFIX.test(normalized) ? normalized : undefined)
}

function lines(text: string) {
  return text.split(/[；;。\n]/).map(item => item.trim()).filter(Boolean)
}

function escaped(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function skillPattern(skill: string) {
  const source = escaped(skill)
  return /^[\x00-\x7F]+$/.test(skill)
    ? new RegExp(`(?<![A-Za-z0-9+#.])${source}(?![A-Za-z0-9+#.])`, 'i')
    : new RegExp(source, 'i')
}

function skillEvidence(skill: string, textLines: string[]): ParsedResumeSkill | null {
  const pattern = skillPattern(skill)
  const quote = textLines.find(line => pattern.test(line))
  if (!quote || NEGATED_CONTEXT.test(quote)) return null
  const level = VERIFIED_CONTEXT.test(quote) ? 3 : PROJECT_CONTEXT.test(quote) ? 2 : LEARNING_CONTEXT.test(quote) ? 1 : 0
  const levelName = ['L0 自述', 'L1 学习', 'L2 实践', 'L3 验证'][level]
  const sourceType = level >= 3 ? 'VERIFIED_RESULT' : level >= 2 ? 'PROJECT' : level === 1 ? 'LEARNING' : 'SELF_REPORT'
  return { skill, level, levelName, quote, sourceType, confidence: level >= 2 ? 0.96 : 0.9 }
}

export function parseResumeText(text: string, vocabulary: string[]): ParsedResumeText {
  const textLines = lines(text)
  const labeledMajor = plausibleMajor(labeledValue(text, ['专业', '所学专业', '就读专业']))
  const fallbackMajor = KNOWN_MAJORS.find(item => text.includes(item))
  const education = labeledValue(text, ['学历', '最高学历'])?.match(/博士|硕士|本科|专科|大专/)?.[0]
    ?? text.match(/博士|硕士|本科|专科|大专/)?.[0]
  const abilities = [...new Set(vocabulary)]
    .sort((left, right) => right.length - left.length)
    .map(skill => skillEvidence(skill, textLines))
    .filter((item): item is ParsedResumeSkill => Boolean(item))

  return {
    name: labeledValue(text, ['姓名', '称呼']),
    education,
    major: labeledMajor ?? fallbackMajor,
    abilities,
    projects: textLines.filter(line => /项目|实习|负责|实现|开发|搭建/.test(line)).slice(0, 8),
    certificates: textLines.filter(line => /证书|认证|软考|竞赛|获奖/.test(line)).slice(0, 5)
  }
}
