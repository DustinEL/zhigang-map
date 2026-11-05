import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeGraphViewText } from '../src/utils/public-text.ts'
import type { GraphView } from '../src/types/models.ts'

test('AI Agent 关系图补齐三条精简岗位职责', () => {
  const graph: GraphView = {
    releaseVersion: 'test',
    viewType: 'CORE_RELATIONS',
    nodes: [
      { id: 'role-3', label: 'AI Agent 开发工程师', type: 'JOB_ROLE', group: 'AI 应用研发', evidenceCount: 4, status: 'PUBLISHED' },
      { id: 'skill-0', label: 'AI Agent', type: 'SKILL', group: 'AI 应用', evidenceCount: 16, status: 'PUBLISHED' }
    ],
    edges: [
      { id: 'skill-edge-0', source: 'role-3', target: 'skill-0', relation: 'REQUIRES', weight: 1, status: 'PUBLISHED' }
    ]
  }

  const result = normalizeGraphViewText(graph)
  const responsibilities = result.nodes.filter(node => node.type === 'RESPONSIBILITY')
  const responsibilityEdges = result.edges.filter(edge => edge.relation === 'PERFORMS')

  assert.deepEqual(responsibilities.map(node => node.label), [
    '设计智能体任务规划',
    '接入工具调用与工作流',
    '构建记忆检索与效果评测'
  ])
  assert.equal(responsibilityEdges.length, 3)
  assert.ok(responsibilityEdges.every(edge => edge.source === 'role-3'))
})
