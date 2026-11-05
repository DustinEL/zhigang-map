import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const sourceRoot = new URL('../src/', import.meta.url)
const source = (path: string) => readFile(new URL(path, sourceRoot), 'utf8')

test('职业诊断使用中文姓名脱敏提示并移除内部解析说明', async () => {
  const view = await source('views/DiagnosisView.vue')
  assert.match(view, /NAME:\s*'姓名'/)
  assert.match(view, /redactedFieldLabel/)
  assert.doesNotMatch(view, /上传内容已先脱敏：.*NAME/)
  assert.doesNotMatch(view, /Apache Tika|PaddleOCR/)
})

test('行动计划不展示内部估时和演示证据', async () => {
  const view = await source('views/GrowthPlanView.vue')
  assert.doesNotMatch(view, /item\.estimatedHours.*小时/)
  assert.doesNotMatch(view, /3 分钟演示证据/)
})

test('岗位演化原文证据按钮使用高对比样式和精简影响说明', async () => {
  const [view, styles] = await Promise.all([source('views/RoleEvolutionView.vue'), source('styles.css')])
  assert.match(view, /建议核对岗位原文/)
  assert.match(styles, /\.evolution-inline-evidence[^}]*background:\s*#(?:0f2f5f|173b6b)/s)
  assert.match(styles, /\.evolution-inline-evidence[^}]*color:\s*#fff/s)
})

test('岗位图谱证据统计与可打开证据保持同一口径', async () => {
  const api = await source('api/frozen-snapshot.ts')
  assert.match(api, /evidenceFor[\s\S]*filter\(item => item\.quote/i)
  assert.match(api, /documentCount:\s*evidence\.length/)
})

test('证据按钮复用关系视图的白底蓝字设计，提交弹窗保持完整可见', async () => {
  const [view, styles] = await Promise.all([source('views/GrowthPlanView.vue'), source('styles.css')])
  assert.match(view, /class="verification-dialog"/)
  assert.match(styles, /\.evolution-inline-evidence[^}]*background:\s*#ffffff/s)
  assert.match(styles, /\.verification-dialog\.el-dialog[^}]*max-height:\s*calc\(100vh/s)
  assert.match(styles, /\.verification-dialog\.el-dialog[^}]*overflow:\s*hidden/s)
  assert.match(styles, /\.verification-dialog \.el-dialog__body[^}]*flex:\s*1/s)
  assert.match(styles, /\.verification-dialog \.el-dialog__header[^}]*flex-shrink:\s*0/s)
  assert.match(styles, /\.verification-dialog \.el-dialog__footer[^}]*flex-shrink:\s*0/s)
})

test('行动计划在显示前修复所有计划文本的 UTF-8 乱码', async () => {
  const view = await source('views/GrowthPlanView.vue')
  assert.match(view, /repairPublicText/)
  assert.match(view, /item\.resources\s*=\s*item\.resources\.map/)
  assert.match(view, /item\.practicalTask\s*=\s*repairPublicText/)
})

test('岗位演化概览的变化数值和类型标签不使用按钮样式', async () => {
  const styles = await source('styles.css')
  assert.doesNotMatch(styles, /\.evolution-change-card > div span,\s*\.evolution-change-card > strong,\s*\.evolution-inline-evidence/)
})

test('岗位演化概览左侧显示名称，右侧数值固定对齐，行动按钮悬停为浅蓝色', async () => {
  const styles = await source('styles.css')
  assert.match(styles, /\.evolution-change-card\s*\{[^}]*grid-template-columns:\s*minmax\(0,1fr\)\s+150px/s)
  assert.match(styles, /\.evolution-change-card > strong\s*\{[^}]*text-align:\s*right/s)
  assert.match(styles, /\.now-task-v10 \.el-button--primary:hover,[\s\S]*?background:\s*#dbeafe/s)
})

test('岗位图谱能力基线提示使用简洁统一文案', async () => {
  const view = await source('views/GraphExplorerView.vue')
  assert.match(view, /当前为能力基线，可用于了解岗位方向和进行能力自测。/g)
  assert.doesNotMatch(view, /尚无真实 JD 样本|不代表市场需求率/)
})

test('岗位图谱无 JD 证据时明确显示基线状态，关系图展开按钮使用浅蓝悬停样式', async () => {
  const [view, styles] = await Promise.all([source('views/GraphExplorerView.vue'), source('styles.css')])
  assert.match(view, /暂无企业 JD 证据/)
  assert.match(view, /当前为能力基线，暂无企业 JD 原文证据/)
  assert.match(styles, /\.graph-summary-bar \.el-button:hover,[\s\S]*?background:\s*#dbeafe/s)
})

test('岗位图谱证据接口为空时回退到冻结岗位快照，避免卡片与抽屉口径不一致', async () => {
  const api = await source('api/index.ts')
  const view = await source('views/GraphExplorerView.vue')
  assert.match(api, /result\.evidence\?\.length[\s\S]*frozenSnapshot\.roleSkillEvidence/) 
  assert.match(view, />查看证据线索</)
  assert.doesNotMatch(view, /查看 \{\{ item\.evidenceCount \}\} 条证据线索/)
})

test('岗位演化按岗位快照生成专属技能和证据，不复用统一离线演示结果', async () => {
  const api = await source('api/frozen-snapshot.ts')
  const index = await source('api/index.ts')
  const view = await source('views/RoleEvolutionView.vue')
  assert.match(api, /尚未形成可比较的双时间窗口/)
  assert.doesNotMatch(index, /frozenSnapshot\.roleEvolution\(id\)\.catch\(\(\) => offline\.roleEvolution\(id\)\)/)
  assert.match(view, /基于当前岗位资料的观察信号/)
})

test('岗位演化不展示查看来源区块，只保留原文证据按钮', async () => {
  const view = await source('views/RoleEvolutionView.vue')
  assert.doesNotMatch(view, /证据线索.*name="evidence"/)
  assert.match(view, /查看 \{\{ change\.evidence\.length \}\} 条原文证据/)
})

test('在线岗位演化结果按岗位校验，异常时回退到岗位专属快照', async () => {
  const api = await source('api/index.ts')
  assert.match(api, /async function loadRoleEvolution\(id: number\)/)
  assert.match(api, /result\??\.roleId === id/)
  assert.match(api, /frozenSnapshot\.roleEvolution\(id\)/)
})
