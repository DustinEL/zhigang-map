import assert from 'node:assert/strict'
import test from 'node:test'
import type { GraphView } from '../src/types/models.ts'
import { normalizeGraphViewText, repairPublicText } from '../src/utils/public-text.ts'

test('可逆的 UTF-8 误解码文本恢复为正常公开文本', () => {
  assert.equal(repairPublicText('å¤§æ¨¡åž‹'), '大模型')
  assert.equal(repairPublicText('正常中文'), '正常中文')
  assert.equal(repairPublicText('Prompt Engineering'), 'Prompt Engineering')
  assert.equal(repairPublicText('Café'), 'Café')
})

test('图谱只规范化节点公开文本并保留原对象和关系数据', () => {
  const graph: GraphView = {
    releaseVersion: '2026.08',
    viewType: 'PANORAMA',
    nodes: [
      { id: 'course', label: 'å¤§æ¨¡åž‹', type: 'LEARNING_RESOURCE', group: 'COURSE', evidenceCount: 2, status: 'PUBLISHED' },
      { id: 'project', label: 'Prompt Engineering', type: 'LEARNING_RESOURCE', group: 'PROJECT', evidenceCount: 3, status: 'PUBLISHED' },
      { id: 'skill', label: 'Python', type: 'SKILL', group: '开发语言', evidenceCount: 4, status: 'PUBLISHED' }
    ],
    edges: [{ id: 'edge-1', source: 'skill', target: 'course', relation: 'LEARNS', weight: 0.8, status: 'PUBLISHED' }],
    metadata: { dataMode: 'REAL', graphRelease: '2026.08' }
  }

  const normalized = normalizeGraphViewText(graph)

  assert.notEqual(normalized, graph)
  assert.notEqual(normalized.nodes, graph.nodes)
  assert.deepEqual(normalized.nodes.map(node => [node.label, node.group]), [
    ['大模型', '课程'],
    ['Prompt Engineering', '项目'],
    ['Python', '开发语言']
  ])
  assert.equal(normalized.edges, graph.edges)
  assert.equal(normalized.metadata, graph.metadata)
  assert.equal(graph.nodes[0]?.label, 'å¤§æ¨¡åž‹')
  assert.equal(graph.nodes[0]?.group, 'COURSE')
})

test('不可安全恢复的学习资源名称使用固定回退文案', () => {
  const base: GraphView = {
    releaseVersion: '2026.08',
    viewType: 'PANORAMA',
    nodes: [],
    edges: []
  }

  for (const label of [`课程${String.fromCodePoint(0xfffd)}名称`, 'å¼ ??']) {
    const normalized = normalizeGraphViewText({
      ...base,
      nodes: [{ id: label, label, type: 'LEARNING_RESOURCE', group: 'COURSE', evidenceCount: 1, status: 'PUBLISHED' }]
    })
    assert.equal(normalized.nodes[0]?.label, '资源名称编码异常')
  }
})
