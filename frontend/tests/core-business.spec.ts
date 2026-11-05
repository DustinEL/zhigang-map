import { describe, expect, it } from 'vitest'
import type { GraphView, PublicRoleOverview } from '../src/types/models'
import { filterGraphByRoles, normalizeGraphFilters, roleMatchesGraphFilters } from '../src/utils/graph-filters'
import { parseResumeText } from '../src/utils/resume-parser'
import { reviewLabel, reviewTone } from '../src/utils/review-display'
import { inferRuntimeMode } from '../src/utils/runtime-mode'

const roles: PublicRoleOverview[] = [
  { id: 1, code: 'JAVA', name: 'Java 开发工程师', family: '后端', emerging: false, definition: '', suitableBackground: '', status: 'PUBLISHED', coreSkills: ['Java', 'Spring Boot'], responsibilities: [], jobLevel: '初中级', techStack: ['编程语言', 'Java', '后端框架'], roleVersion: 'v2', evidenceLayer: 'REAL_EVIDENCE' },
  { id: 2, code: 'AGENT', name: 'AI Agent 工程师', family: 'AI 应用', emerging: true, definition: '', suitableBackground: '', status: 'BASELINE', coreSkills: ['Python', 'AI Agent'], responsibilities: [], jobLevel: '中级', techStack: ['编程语言', 'Python', 'AI应用'], roleVersion: 'v1', evidenceLayer: 'BASELINE' }
]
const graph: GraphView = {
  releaseVersion: 'v2', viewType: 'PUBLIC_PANORAMA', metadata: { dataMode: 'REAL' },
  nodes: [
    { id: 'family-backend', label: '后端', type: 'JOB_FAMILY', group: '后端', evidenceCount: 0, status: 'DIRECTORY' },
    { id: 'family-ai', label: 'AI 应用', type: 'JOB_FAMILY', group: 'AI 应用', evidenceCount: 0, status: 'DIRECTORY' },
    { id: 'role-1', label: 'Java 开发工程师', type: 'JOB_ROLE', group: '后端', evidenceCount: 3, status: 'PUBLISHED' },
    { id: 'role-2', label: 'AI Agent 工程师', type: 'JOB_ROLE', group: 'AI 应用', evidenceCount: 0, status: 'BASELINE' },
    { id: 'skill-java', label: 'Java', type: 'SKILL', group: '编程语言', evidenceCount: 3, status: 'REAL_EVIDENCE' },
    { id: 'skill-python', label: 'Python', type: 'SKILL', group: '编程语言', evidenceCount: 0, status: 'BASELINE' }
  ],
  edges: [
    { id: 'e1', source: 'family-backend', target: 'role-1', relation: 'CONTAINS', weight: 1, status: 'PUBLISHED' },
    { id: 'e2', source: 'family-ai', target: 'role-2', relation: 'CONTAINS', weight: 1, status: 'BASELINE' },
    { id: 'e3', source: 'role-1', target: 'skill-java', relation: 'REQUIRES', weight: 0.9, status: 'PUBLISHED' },
    { id: 'e4', source: 'role-2', target: 'skill-python', relation: 'REQUIRES', weight: 0.7, status: 'BASELINE' }
  ]
}

describe('图谱筛选核心逻辑', () => {
  it('清理空筛选并按技术栈、级别、版本和可信层匹配岗位', () => {
    expect(normalizeGraphFilters({ techStack: ' ALL ', jobLevel: '中级' })).toEqual({ jobLevel: '中级' })
    expect(roleMatchesGraphFilters(roles[0], { techStack: 'Java', jobLevel: '初中级', roleVersion: 'v2', evidenceLayer: 'REAL_EVIDENCE' })).toBe(true)
    expect(roleMatchesGraphFilters(roles[1], { evidenceLayer: 'REAL_EVIDENCE' })).toBe(false)
  })

  it('筛选会同时裁剪节点和关系，且未知条件返回空图', () => {
    const filtered = filterGraphByRoles(graph, roles, { techStack: 'Java', evidenceLayer: 'REAL_EVIDENCE' })
    expect(filtered.nodes.map(node => node.id)).toEqual(['family-backend', 'role-1', 'skill-java'])
    expect(filtered.edges.map(edge => edge.id)).toEqual(['e1', 'e3'])
    expect(filterGraphByRoles(graph, roles, { techStack: '不存在' }).nodes).toEqual([])
    expect(filterGraphByRoles(graph, roles, {})).toBe(graph)
  })

  it('技术栈使用规范化精确匹配，不把 JavaScript 当成 Java', () => {
    const withJavaScript = { ...graph, nodes: [...graph.nodes, { id: 'skill-js', label: 'JavaScript', type: 'SKILL', group: '编程语言', evidenceCount: 2, status: 'REAL_EVIDENCE' }], edges: [...graph.edges, { id: 'e5', source: 'role-1', target: 'skill-js', relation: 'REQUIRES', weight: 0.5, status: 'PUBLISHED' }] } satisfies GraphView
    const filtered = filterGraphByRoles(withJavaScript, roles, { techStack: 'Java' })
    expect(filtered.nodes.some(node => node.label === 'JavaScript')).toBe(false)
  })
})

describe('简历、审核与运行模式核心逻辑', () => {
  it('只保留边界明确且未被否定的简历事实', () => {
    const parsed = parseResumeText('姓名：匿名同学\n学历：本科\n专业：软件工程\n项目：使用 JavaScript 开发平台并提交测试结果。\n目前不会 Java。', ['Java', 'JavaScript'])
    expect(parsed.name).toBe('匿名同学')
    expect(parsed.major).toBe('软件工程')
    expect(parsed.abilities.map(item => [item.skill, item.level])).toEqual([['JavaScript', 3]])
  })

  it('统一审核标签、状态色和权威运行模式', () => {
    expect(reviewLabel('APPROVED')).toBe('批次已通过')
    expect(reviewLabel()).toBe('未处理')
    expect(reviewTone('REJECTED')).toBe('failed')
    expect(reviewTone('ELIGIBLE')).toBe('passed')
    expect(reviewTone('PENDING_REVIEW')).toBe('warning')
    expect(inferRuntimeMode({ metadata: { dataMode: 'FROZEN_CACHE' } })).toBe('FROZEN_CACHE')
    expect(inferRuntimeMode({ rawDocumentCount: 0, publishedRoleCount: 0 })).toBe('NO_DATA')
    expect(inferRuntimeMode({ operatingMode: 'DEMO_MEMORY' })).toBe('DEMO')
    expect(inferRuntimeMode('ok')).toBe('REAL')
  })
})
