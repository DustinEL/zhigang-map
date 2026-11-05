import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const root = new URL('../src/', import.meta.url)
const source = () => readFile(new URL('views/RoleEvolutionView.vue', root), 'utf8')

test('岗位演化是公开只读页面，不含登录、生成或发布入口', async () => {
  const view = await source()
  assert.doesNotMatch(view, /loginForm|professionalLogin|登录|密码|首次演化运行|生成首次|publishRoleEvolution|updateRoleEvolutionDraft|startRoleEvolution|canEdit|evolution-login/)
  assert.doesNotMatch(view, /当前查看岗位/)
  assert.doesNotMatch(view, /只读静态快照/)
  assert.match(view, /roleEvolution\(selectedRole\)/)
})

test('岗位演化选择支持 URL、career 目标岗位、手动选择和公开数据回退', async () => {
  const view = await source()
  assert.match(view, /route\.query\.role/)
  assert.match(view, /career\.targetRoleId/)
  assert.match(view, /公开演化数据|findFirstPublicEvolutionRole|首个/) 
  assert.match(view, /router\.replace\([^)]*evolution[^)]*role: selectedRole/s)
})

test('岗位演化默认 Java，但选择范围包含所有有能力画像的传统岗位', async () => {
  const view = await source()
  assert.match(view, /role\.code === 'JAVA_ENGINEER'/)
  assert.match(view, /roleOptions.*isActionableRole/s)
})

test('岗位演化按所选传统岗位加载，不把非 Java 岗位固定回退为 Java', async () => {
  const [view, api] = await Promise.all([source(), readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8')])
  assert.match(view, /loadRoleEvolution\(selectedRole\.value\)/)
  assert.doesNotMatch(api, /id !== 2.*frozenSnapshot\.roleEvolution/s)
  assert.match(api, /offline\.roleEvolution\(id\)/)
})

test('未核验的大模型评测岗位不进入已确认岗位目录', async () => {
  const [offline, frozen] = await Promise.all([
    readFile(new URL('../src/api/offline.ts', import.meta.url), 'utf8'),
    readFile(new URL('../src/api/frozen-snapshot.ts', import.meta.url), 'utf8')
  ])
  assert.doesNotMatch(offline, /LLM_EVALUATION_ENGINEER.*大模型评测工程师/)
  assert.match(frozen, /catalogRoles.*LLM_EVALUATION_ENGINEER/s)
})

test('岗位演化无数据展示中文空态而非错误提示', async () => {
  const view = await source()
  assert.match(view, /暂无公开岗位演化数据|选择其他岗位/)
  assert.doesNotMatch(view, /el-alert v-if="pageError".*type="error"/s)
})

test('零变化结果展示稳定或样本不足说明而不是空白主面板', async () => {
  const view = await source()
  assert.match(view, /hasEvolutionChanges/)
  assert.match(view, /岗位要求整体保持稳定/)
  assert.match(view, /样本暂不足以形成变化结论/)
  assert.match(view, /run\.value\?\.conclusionLevel === 'STABLE'/)
  assert.match(view, /run\.value\?\.status === 'INSUFFICIENT_DATA'/)
})

test('RAG 与 AI Agent 是 AI 应用研发正式岗位，不应被归入新兴岗位', async () => {
  const [offline, frozen] = await Promise.all([
    readFile(new URL('../src/api/offline.ts', import.meta.url), 'utf8'),
    readFile(new URL('../public/data/current.json', import.meta.url), 'utf8')
  ])
  assert.match(offline, /code: 'RAG_APP_ENGINEER'[\s\S]*?family: 'AI 应用研发'[\s\S]*?emerging: false/)
  assert.match(offline, /code: 'AI_AGENT_ENGINEER'[\s\S]*?family: 'AI 应用研发'[\s\S]*?emerging: false/)
  const snapshot = JSON.parse(frozen)
  const roles = snapshot.roles
  const publicCodes = snapshot.publicOverview.roles.map((item: { code: string }) => item.code)
  for (const code of ['RAG_APP_ENGINEER', 'AI_AGENT_ENGINEER']) {
    const role = roles.find((item: { code: string }) => item.code === code)
    assert.equal(role?.family, 'AI 应用研发')
    assert.equal(role?.emerging, false)
    assert.ok(publicCodes.includes(code), `${code} 必须保留在正式岗位目录`)
  }
  assert.equal(roles.find((item: { code: string }) => item.code === 'LLM_EVALUATION_ENGINEER')?.emerging, true)
})

test('未核验的大模型评测岗位不会出现在全景岗位图谱', async () => {
  const api = await readFile(new URL('../src/api/frozen-snapshot.ts', import.meta.url), 'utf8')
  assert.match(api, /function publicGraph\(snapshot: FrozenSnapshot, graph: GraphView\)/)
  assert.match(api, /role\.code === 'LLM_EVALUATION_ENGINEER'/)
  assert.match(api, /hiddenNodeIds/) 
  assert.match(api, /publicGraph\(snapshot, snapshot\.publicGraphOverview\)/)
  assert.match(api, /publicGraph\(snapshot, filteredGraph\)/)
})
