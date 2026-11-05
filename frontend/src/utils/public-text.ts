import type { GraphView } from '../types/models'

const roleResponsibilities: Record<string, [string, string, string]> = {
  'RAG 应用开发工程师': ['建立引用与幻觉评测', '构建企业知识库问答系统', '设计文档切分、检索、重排和生成链路'],
  'Java 开发工程师': ['开发后端接口与业务服务', '设计数据库与缓存方案', '保障系统性能与稳定性'],
  'AI Agent 开发工程师': ['设计智能体任务规划', '接入工具调用与工作流', '构建记忆检索与效果评测'],
  'AI 应用后端工程师': ['接入大模型与智能体能力', '开发高可用后端服务', '完成性能优化与故障排查'],
  'LLMOps / 模型服务工程师': ['部署与管理模型服务', '建设监控告警与版本管理', '优化推理性能与服务稳定性'],
  '大模型评测工程师': ['设计模型评测指标', '建设自动化评测流程', '分析结果并推动模型优化'],
  '大模型应用开发工程师': ['开发大模型应用功能', '完成模型调优与效果评测', '保障应用部署与稳定运行'],
  'AI 数据工程师': ['建设数据采集处理链路', '设计数仓与数据模型', '保障数据质量与安全'],
  'AI 安全测试工程师': ['开展提示注入与泄露测试', '建设自动化安全评测', '跟踪风险并推动修复'],
  '大模型推理加速工程师': ['优化模型量化与算子', '优化缓存与批处理性能', '适配模型部署与性能调优'],
  '向量检索工程师': ['建设向量索引与召回', '优化检索精度与性能', '保障检索服务稳定运行'],
  '智能物流调度员': ['优化物流运输路线', '协调运输节点与计划', '监控车辆与任务状态'],
  '机器学习工程师': ['设计机器学习解决方案', '训练并优化预测模型', '协同保障训练数据质量'],
  '数据科学家': ['分析复杂业务数据', '构建预测与决策模型', '输出数据洞察与建议']
}

const windows1252Bytes = new Map<string, number>([
  ['€', 0x80], ['‚', 0x82], ['ƒ', 0x83], ['„', 0x84], ['…', 0x85],
  ['†', 0x86], ['‡', 0x87], ['ˆ', 0x88], ['‰', 0x89], ['Š', 0x8a],
  ['‹', 0x8b], ['Œ', 0x8c], ['Ž', 0x8e], ['‘', 0x91], ['’', 0x92],
  ['“', 0x93], ['”', 0x94], ['•', 0x95], ['–', 0x96], ['—', 0x97],
  ['˜', 0x98], ['™', 0x99], ['š', 0x9a], ['›', 0x9b], ['œ', 0x9c],
  ['ž', 0x9e], ['Ÿ', 0x9f]
])

const mojibakeMarker = new RegExp(
  `[${String.fromCodePoint(0xc0)}-${String.fromCodePoint(0xff)}${String.fromCodePoint(0x100)}-${String.fromCodePoint(0x17f)}]`,
  'g'
)
const replacementCharacter = String.fromCodePoint(0xfffd)
const commonMojibakePrefixes = [String.fromCodePoint(0xc3), String.fromCodePoint(0xc2)]

function hasUnsafePlaceholder(value: string) {
  return value.includes(replacementCharacter) || /\?{2,}/.test(value)
}

function mojibakeScore(value: string) {
  return value.match(mojibakeMarker)?.length ?? 0
}

function windows1252BytesOf(value: string) {
  const bytes: number[] = []
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0
    if (codePoint <= 0xff) {
      bytes.push(codePoint)
      continue
    }
    const byte = windows1252Bytes.get(character)
    if (byte === undefined) return null
    bytes.push(byte)
  }
  return Uint8Array.from(bytes)
}

export function repairPublicText(value: string): string | null {
  if (hasUnsafePlaceholder(value)) return null
  const initialScore = mojibakeScore(value)
  if (initialScore === 0) return value
  if (initialScore === 1 && !commonMojibakePrefixes.some(prefix => value.includes(prefix))) return value

  const bytes = windows1252BytesOf(value)
  if (!bytes) return null

  try {
    const candidate = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    if (hasUnsafePlaceholder(candidate) || mojibakeScore(candidate) >= initialScore) return null
    return candidate
  } catch {
    return null
  }
}

export function normalizeGraphViewText(graph: GraphView): GraphView {
  const repaired = {
    ...graph,
    nodes: graph.nodes.map(node => {
      const learningResource = node.type === 'LEARNING_RESOURCE'
      const label = repairPublicText(node.label)
      const group = repairPublicText(node.group)
      return {
        ...node,
        label: label ?? (learningResource ? '资源名称编码异常' : node.label),
        group: learningResource && node.group === 'COURSE'
          ? '课程'
          : learningResource && node.group === 'PROJECT'
            ? '项目'
            : group ?? node.group
      }
    })
  }
  const roleNode = repaired.nodes.find(node => node.type === 'JOB_ROLE')
  const responsibilities = roleNode ? roleResponsibilities[roleNode.label] : undefined
  if (!roleNode || !responsibilities) return repaired

  const existingResponsibilities = repaired.nodes.filter(node => node.type === 'RESPONSIBILITY')
  const responsibilityIds = new Set(existingResponsibilities.map(node => node.id))
  const nodes = repaired.nodes.filter(node => node.type !== 'RESPONSIBILITY')
  const edges = repaired.edges.filter(edge => !responsibilityIds.has(edge.source) && !responsibilityIds.has(edge.target))

  responsibilities.forEach((label, index) => {
    const id = `${roleNode.id}-responsibility-${index}`
    nodes.push({
      id,
      label,
      type: 'RESPONSIBILITY',
      group: '职责',
      evidenceCount: existingResponsibilities[index]?.evidenceCount ?? 0,
      status: roleNode.status
    })
    edges.push({
      id: `${roleNode.id}-performs-${index}`,
      source: roleNode.id,
      target: id,
      relation: 'PERFORMS',
      weight: 1,
      status: roleNode.status
    })
  })

  return { ...repaired, nodes, edges }
}
