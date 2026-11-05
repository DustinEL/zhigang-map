import type { JobRole } from '../types/models'

type RoleCopyInput = {
  name?: string
  definition?: string
  responsibilities?: string[]
}

export function isActionableRole(role: Pick<JobRole, 'emerging' | 'requiredSkills'>) {
  return !role.emerging && role.requiredSkills.length > 0
}

const ROLE_DESCRIPTIONS: Record<string, string> = {
  'AI Agent 开发工程师': '负责智能体任务规划、工具调用与效果评估。',
  'AI Agent工程师': '负责智能体任务规划、工具调用与效果评估。',
  'RAG 应用开发工程师': '负责检索增强应用、知识库问答与引用质量。',
  'RAG应用开发工程师': '负责检索增强应用、知识库问答与引用质量。',
  '提示词工程师': '负责提示词设计、任务编排与模型效果优化。',
  'LLMOps / 模型服务工程师': '负责模型服务部署、监控与稳定性保障。',
  'AI 应用后端工程师': '负责 AI 应用服务、接口设计与稳定性建设。',
  'Java 开发工程师': '负责 Java 服务开发、接口设计与系统维护。'
}

function firstSentence(value: string) {
  const text = value.trim()
  if (!text) return ''
  const match = text.match(/^(.+?[。！？.!?])(?:\s|$)/)
  return (match?.[1] ?? text).slice(0, 88)
}

export function shortRoleDescription(input: RoleCopyInput) {
  const name = input.name?.trim() ?? ''
  if (ROLE_DESCRIPTIONS[name]) return ROLE_DESCRIPTIONS[name]
  const responsibility = firstSentence(input.responsibilities?.[0] ?? '')
  if (responsibility) return responsibility
  const definition = firstSentence(input.definition ?? '')
  return definition || '围绕岗位核心能力完成相关设计、开发与交付。'
}

export function compactResponsibility(value: string) {
  let text = value.trim().replace(/\s+/g, ' ')
  while (text) {
    const next = text.replace(/^(?:岗位职责\s*)?(?:\d+\s*[.．、,，:：)）]\s*|\d+\s+)/u, '').trim()
    if (next === text) break
    text = next
  }
  if (!text || /^\d+$/.test(text)) return ''
  const firstClause = text.split(/[；。!?！？]/)[0].trim()
  if (!firstClause) return ''
  return firstClause.length > 18 ? `${firstClause.slice(0, 17)}…` : firstClause
}

const BROAD_SKILL_LABELS: Record<string, string> = {
  '后端开发': '后端接口与业务模块',
  '软件开发': '岗位服务模块开发',
  '人工智能': 'AI 应用功能实现',
  '机器学习': '模型训练与验证',
  '深度学习': '模型训练与评测',
  '高并发': '并发性能优化',
  '分布式系统': '分布式服务设计',
  '平台开发': '平台功能开发',
  '算法工程': '算法工程实现',
  '自然语言处理': '文本处理与模型评测',
  '多模态': '多模态数据处理',
  '模型微调': '模型微调与效果验证'
}

export function planSkillLabel(skill: string) {
  return BROAD_SKILL_LABELS[skill.trim()] ?? skill.trim()
}

export function planTaskCopy(skill: string, roleName: string) {
  const label = planSkillLabel(skill)
  const taskByLabel: Record<string, { reason: string; task: string; resources: string; acceptance: string }> = {
    '后端接口与业务模块': {
      reason: '把岗位要求拆成接口、业务逻辑和异常处理三个可验证模块',
      task: '为目标岗位实现一个带参数校验、异常处理和持久化的业务接口，并补齐接口测试',
      resources: '接口设计、异常处理与集成测试',
      acceptance: '接口可运行，包含参数校验、异常分支和至少 3 条自动化测试'
    },
    '并发性能优化': {
      reason: '把并发要求落实到可复现的压测指标和优化记录',
      task: '为一个岗位核心接口设计并执行并发压测，定位瓶颈后完成一次优化对比',
      resources: '并发模型、压测与性能分析',
      acceptance: '提交压测脚本、前后对比指标、瓶颈分析和优化代码'
    },
    '分布式服务设计': {
      reason: '通过服务拆分、重试和一致性处理验证分布式设计能力',
      task: '设计一个可拆分为两个服务的业务流程，补充超时、重试和幂等处理',
      resources: '服务拆分、幂等与故障处理',
      acceptance: '提交架构图、接口说明、故障场景测试和运行记录'
    },
    '模型训练与验证': {
      reason: '将模型训练要求落到数据、指标和可复现实验',
      task: '选择一个岗位相关数据集完成训练、验证和误差分析，记录关键参数与指标',
      resources: '数据划分、训练流程与指标评估',
      acceptance: '提交训练代码、数据说明、评估指标和误差分析结论'
    },
    '模型训练与评测': {
      reason: '用可复现实验验证模型训练效果和改进方向',
      task: '完成一次模型训练与基线对比，输出评测指标、失败样本和改进方案',
      resources: '训练监控、基线对比与效果评估',
      acceptance: '提交训练日志、基线与改进指标、失败样本分析和代码'
    },
    '文本处理与模型评测': {
      reason: '把文本处理能力落到可测量的检索或生成质量指标',
      task: '构建一条文本处理链路并设计评测集，比较基线与优化后的质量指标',
      resources: '文本处理、评测集与质量指标',
      acceptance: '提交处理代码、评测集、指标计算脚本和对比报告'
    },
    '模型微调与效果验证': {
      reason: '通过小规模微调实验验证数据、参数和效果变化',
      task: '使用岗位相关样例完成一次小规模微调或参数实验，记录效果变化与成本',
      resources: '数据准备、微调配置与效果评估',
      acceptance: '提交样例数据、配置、前后指标和资源消耗记录'
    }
  }
  return taskByLabel[label] ?? {
    reason: `将${label}落实为 ${roleName} 中一个可独立验收的能力单元`,
    task: `围绕${label}完成一个与${roleName}相关的最小可运行模块，并记录设计、测试和结果`,
    resources: `${label}基础与岗位实践`,
    acceptance: '提交可运行代码、实现说明、测试结果和一段演示记录'
  }
}
