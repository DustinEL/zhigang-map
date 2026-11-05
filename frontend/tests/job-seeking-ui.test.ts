import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { evidenceDrawerState, fillRoleSlots, publicSkillRanking, workspaceSnapshot } from '../src/utils/public-ui.ts'

const sourceRoot = new URL('../src/', import.meta.url)

test('drawer redesign exposes readable sections and responsive action bar', async () => {
  const graph = await readFile(new URL('views/GraphExplorerView.vue', sourceRoot), 'utf8')
  const styles = await readFile(new URL('styles.css', sourceRoot), 'utf8')
  assert.match(graph, /panorama-detail-evidence/)
  assert.match(graph, /panorama-detail-actions-bar/)
  assert.match(styles, /role-detail-drawer[^}]*width:\s*min\(700px/)
  assert.match(styles, /panorama-detail-scroll[^}]*overflow-y:\s*auto/)
  assert.doesNotMatch(styles, /\\u[0-9a-fA-F]{4}/)
})

async function source(path: string) {
  return readFile(new URL(path, sourceRoot), 'utf8')
}

test('首页使用四张岗位卡与真实技能统计，不展示固定迁移示例', async () => {
  const home = await source('views/StudentHomeView.vue')
  assert.match(home, /slice\(0, 4\)/)
  assert.doesNotMatch(home, /TransferCompass/)
  assert.doesNotMatch(home, /Math\.max\(34, 100 - index \* 11\)/)
  assert.match(home, /publicSkillRanking\(overview\.value\?\.topSkills/)
  assert.doesNotMatch(home, /api\.roles\(\)/)
  assert.doesNotMatch(home, /api\.currentRelease\(\)|currentRelease|当前版本/)
  assert.doesNotMatch(home, /v-if="publishedRoles\.length"/)
})

test('首页公开概览使用独立的长请求超时，不放大所有接口的全局超时', async () => {
  const api = await source('api/index.ts')
  assert.match(api, /timeout:\s*7000/)
  assert.match(api, /publicOverview:\s*\(\)\s*=>\s*cachedPublicGet<PublicOverview>\([^\n]+timeout:\s*1[5-9]\d{3}/)
})

test('首页收到数据库或缓存样本数后立即显示最终值', async () => {
  const home = await source('views/StudentHomeView.vue')
  assert.match(home, /liveSampleCount\.value\s*=\s*overview\.value\.sampleCount/)
  assert.doesNotMatch(home, /setInterval|Math\.ceil\(target\s*\/\s*24\)|liveSampleCount\.value\s*=\s*0/)
})

test('首页技能榜只消费公开已发布岗位统计，保留后端排序并去重', () => {
  assert.deepEqual(publicSkillRanking(['Java', 'Python', 'Java', 'RAG']), [
    { name: 'Java', rank: 1 }, { name: 'Python', rank: 2 }, { name: 'RAG', rank: 3 }
  ])
})

test('首页无论有零到三个已发布岗位都固定渲染四个槽位', () => {
  for (let count = 0; count < 4; count += 1) {
    const roles = Array.from({ length: count }, (_, index) => ({ id: index + 1 }))
    const slots = fillRoleSlots(roles, 4)
    assert.equal(slots.length, 4)
    assert.equal(slots.filter(Boolean).length, count)
  }
})

test('工作台未诊断时不展示演示目标、分数和任务', async () => {
  const workspace = await source('views/WorkspaceView.vue')
  assert.doesNotMatch(workspace, /数据分析师/)
  assert.doesNotMatch(workspace, />72</)
  assert.match(workspace, /workspaceSnapshot\(career\)/)
  assert.match(workspace, /完成诊断后/)
  for (const fixed of ['72%', '4 / 7', "value: '12'", "value: '48'", '+5–8%']) assert.doesNotMatch(workspace, new RegExp(fixed.replace(/[+]/g, '\\+')))
})

test('工作台仅在有诊断结果时派生岗位、准备度和任务统计', () => {
  assert.deepEqual(workspaceSnapshot({ profile: null, match: null, plan: null, verifiedOrders: [] }), {
    diagnosed: false, targetRole: '目标岗位暂定', readiness: null, abilityCount: 0, planCount: 0, verifiedCount: 0
  })
  assert.deepEqual(workspaceSnapshot({ profile: { abilities: [{ skill: 'Java' }] }, match: { roleName: 'Java 开发工程师', readiness: 45 }, plan: { items: [{ order: 1 }] }, verifiedOrders: [1] }), {
    diagnosed: true, targetRole: 'Java 开发工程师', readiness: 45, abilityCount: 1, planCount: 1, verifiedCount: 1
  })
})

test('公开求职页面不展示内部处理元数据或英文事实类型', async () => {
  const paths = [
    'views/StudentHomeView.vue',
    'views/JobLibraryView.vue',
    'views/RoleEvolutionView.vue',
    'views/GraphExplorerView.vue',
    'views/DiagnosisView.vue',
    'views/GrowthPlanView.vue',
    'views/PublicEmergingDiscoveryView.vue'
  ]
  const content = (await Promise.all(paths.map(source))).join('\n')
  for (const forbidden of ['统计方法', '策略版本', '输入哈希', '草稿版本', '当前试验快照']) {
    assert.doesNotMatch(content, new RegExp(forbidden))
  }
  assert.doesNotMatch(content, /prop="factType"/)
  assert.doesNotMatch(content, /TransferCompass/)
  assert.doesNotMatch(content, /calibrationDatasetHash|评分策略|校准版本/)
  assert.doesNotMatch(content, /缓存检索|bge-m3|联网补证|字段引用校验|自动保存|证据入图/)
})

test('公开枚举始终映射为中文而不直接透传', async () => {
  const [graph, diagnosis] = await Promise.all([source('views/GraphExplorerView.vue'), source('views/DiagnosisView.vue')])
  assert.match(graph, /requirementTypeLabel\(skillEvidence\.requirementType\)/)
  assert.match(diagnosis, /EXACT_OR_ALIAS: '精确或别名匹配'/)
  assert.match(diagnosis, /\?\? '已完成能力比对'/)
})

test('已加入图谱的新兴岗位可以确认后移出图谱', async () => {
  const [view, api] = await Promise.all([
    source('views/PublicEmergingDiscoveryView.vue'),
    source('api/index.ts')
  ])
  assert.match(view, /从图谱移除/)
  assert.match(view, /ElMessageBox\.confirm/)
  assert.match(view, /api\.removeEmergingQueryFromGraph/)
  assert.match(view, /v-if="item\.publishedRoleId"/)
  assert.match(view, /removeFromGraph\(item\)/)
  assert.match(api, /removeEmergingQueryFromGraph/)
  assert.match(api, /method:\s*'DELETE'/)
})

test('岗位证据抽屉区分加载、失败、空数据与成功状态', () => {
  assert.equal(evidenceDrawerState({ loading: true, error: '', hasEvidence: false }), 'loading')
  assert.equal(evidenceDrawerState({ loading: false, error: '网络异常', hasEvidence: false }), 'error')
  assert.equal(evidenceDrawerState({ loading: false, error: '', hasEvidence: false }), 'empty')
  assert.equal(evidenceDrawerState({ loading: false, error: '', hasEvidence: true }), 'ready')
})

test('岗位证据请求失败时在抽屉内显示错误并允许重新加载', async () => {
  const graph = await source('views/GraphExplorerView.vue')
  assert.match(graph, /evidenceError\.value = errorMessage\(error\)/)
  assert.match(graph, /evidenceState === 'error'/)
  assert.match(graph, />重新加载<\/el-button>/)
})

test('岗位图谱与演化默认折叠长列表，并提供详情抽屉', async () => {
  const graph = await source('views/GraphExplorerView.vue')
  const evolution = await source('views/RoleEvolutionView.vue')
  assert.match(graph, /el-drawer/)
  assert.match(graph, /岗位详情/)
  assert.match(evolution, /evolution-change-board/)
  assert.match(evolution, /evolution-evidence-board/)
  assert.match(evolution, /变化节点/)
})

test('文件上传请求不覆盖浏览器生成的 multipart content type', async () => {
  const api = await source('api/index.ts')
  assert.match(api, /data instanceof FormData/)
  assert.match(api, /if \(!\(config\.data instanceof FormData\)\)\s*\{\s*config\.headers\s*=\s*\{ \.\.\.config\.headers, 'Content-Type': 'application\/json; charset=utf-8'/s)
})

test('在线岗位演化无变化时保留空结果而不是回退演示数据', async () => {
  const api = await source('api/index.ts')
  assert.match(api, /if \(result\?\.roleId === id\) return result/)
})

test('公开源码不存在常见 UTF-8 误解码字符', async () => {
  const paths = [
    'api/frozen-snapshot.ts',
    'api/offline.ts',
    'views/RoleEvolutionView.vue',
    'views/GrowthPlanView.vue'
  ]
  const content = (await Promise.all(paths.map(source))).join('\n')
  const replacementCharacter = String.fromCodePoint(0xfffd)
  const commonMojibakePattern = new RegExp(`[锟${replacementCharacter}]`)
  assert.doesNotMatch(content, commonMojibakePattern)
  assert.doesNotMatch(content, /(?:å|æ|ç|è|é|ð|Â|Ã){2,}/)
})
