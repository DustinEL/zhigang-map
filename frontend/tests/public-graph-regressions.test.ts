import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const sourceRoot = new URL('../src/', import.meta.url)

async function source(path: string) {
  return readFile(new URL(path, sourceRoot), 'utf8')
}

test('全景岗位图以可换行网格展示岗位，不再使用横向滚动长带', async () => {
  const [component, styles] = await Promise.all([
    source('components/PublicPanoramaGraph.vue'),
    source('styles.css')
  ])

  assert.match(component, /class="atlas-role-grid"/)
  assert.match(styles, /\.atlas-role-grid\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*repeat\(auto-fit,/s)
  assert.doesNotMatch(styles, /\.atlas-role-grid\s*\{[^}]*overflow-x:\s*auto/s)
})

test('全景岗位图允许主动显示核心技能，并使用可交互区域语义', async () => {
  const component = await source('components/PublicPanoramaGraph.vue')

  assert.match(component, /showSkills = !showSkills/)
  assert.match(component, /显示核心技能/)
  assert.match(component, /隐藏核心技能/)
  assert.match(component, /role="region"/)
  assert.doesNotMatch(component, /role="img"/)
  assert.doesNotMatch(component, /十二岗位/)
})

test('企业 JD 证据抽屉固定标题，并让正文区域独立滚动', async () => {
  const [view, styles] = await Promise.all([
    source('views/GraphExplorerView.vue'),
    source('styles.css')
  ])

  assert.match(view, /class="evidence-drawer"/)
  assert.match(view, /class="evidence-drawer"[^>]*append-to-body/)
  assert.match(view, /class="v10-evidence-scroll"/)
  assert.match(styles, /\.evidence-drawer\.el-drawer\s*\{[^}]*top:\s*0[^}]*bottom:\s*0[^}]*height:\s*100vh[^}]*max-height:\s*100vh[^}]*display:\s*flex[^}]*flex-direction:\s*column[^}]*overflow:\s*hidden/s)
  assert.match(styles, /\.evidence-drawer\s+\.el-drawer__body\s*\{[^}]*flex:\s*1[^}]*min-height:\s*0[^}]*display:\s*flex[^}]*overflow:\s*hidden/s)
  assert.match(styles, /\.v10-evidence-scroll\s*\{[^}]*flex:\s*1[^}]*min-height:\s*0[^}]*overflow-y:\s*auto/s)
})

test('关系图默认限制技能和职责数量，并提供展开与收起入口', async () => {
  const graph = await source('components/GraphCanvas.vue')

  assert.match(graph, /const expanded = ref\(false\)/)
  assert.match(graph, /slice\(0,\s*6\)/)
  assert.match(graph, /slice\(0,\s*3\)/)
  assert.match(graph, /展开全部/)
  assert.match(graph, /收起/)
})

test('关系图技能节点同时支持鼠标、Enter 与 Space 键', async () => {
  const graph = await source('components/GraphCanvas.vue')

  assert.doesNotMatch(graph, /class="graph-desktop"[^>]*role="img"/)
  assert.match(graph, /class="graph-desktop"[^>]*role="group"/)
  assert.match(graph, /@keyup\.enter=/)
  assert.match(graph, /@keydown\.space\.prevent=/)
  assert.match(graph, /@keyup\.space\.prevent=/)
  assert.match(graph, /<button[^>]*class="graph-mobile-node"[^>]*@click=/)
})

test('发现新岗位始终展示六个静态建议词，并支持点击查询', async () => {
  const discovery = await source('views/PublicEmergingDiscoveryView.vue')
  const expected = ['AI Agent', 'RAG 应用工程师', 'LLMOps', '提示词工程师', '模型评测工程师', '向量检索工程师']

  for (const suggestion of expected) assert.match(discovery, new RegExp(suggestion))
  assert.match(discovery, /v-for="item in suggestions"/)
  assert.match(discovery, /@click="chooseSuggestion\(item\)"/)
})

test('发现新岗位公开页面不展示内部处理步骤', async () => {
  const discovery = await source('views/PublicEmergingDiscoveryView.vue')

  assert.doesNotMatch(discovery, /pipeline-hint|缓存检索|bge-m3|联网补证|字段引用校验|自动保存|证据入图/)
  assert.doesNotMatch(discovery, /result\.errorMessage|星火模型/)
})

test('发现新岗位按运行模式展示真实查询状态', async () => {
  const discovery = await source('views/PublicEmergingDiscoveryView.vue')

  assert.match(discovery, /const searchProgress = computed/)
  assert.match(discovery, /frozenCache/)
  assert.match(discovery, /正在查询当前资料/)
  assert.match(discovery, /正在查询公开证据快照/)
  assert.match(discovery, /大模型正在分析/)
  assert.match(discovery, /正在联网搜索证据/)
  assert.match(discovery, /searchProgress\.title/)
  assert.match(discovery, /searchProgress\.detail/)
})

test('加入图谱按钮使用完整发布门禁并保留已发布图谱入口', async () => {
  const discovery = await source('views/PublicEmergingDiscoveryView.vue')
  assert.match(discovery, /canPublishEmergingQuery\(result\)/)
  assert.match(discovery, /v-if="result\.publishedRoleId"[^>]+abilities\?role=/)
})
