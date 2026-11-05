import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { compactResponsibility, planSkillLabel, planTaskCopy, shortRoleDescription } from '../src/utils/role-copy.ts'

const sourceRoot = new URL('../src/', import.meta.url)
const source = (path: string) => readFile(new URL(path, sourceRoot), 'utf8')

test('shortRoleDescription returns concise user-facing role copy', () => {
  assert.equal(shortRoleDescription({ name: 'AI Agent 开发工程师', definition: '负责智能体任务规划、工具调用、记忆检索与效果评估。' }), '负责智能体任务规划、工具调用与效果评估。')
  assert.equal(shortRoleDescription({ name: 'RAG 应用开发工程师', definition: '负责检索增强应用、知识库问答与引用质量。' }), '负责检索增强应用、知识库问答与引用质量。')
  assert.equal(shortRoleDescription({ name: '未知岗位', definition: '负责构建可靠的服务接口并完成上线维护。' }), '负责构建可靠的服务接口并完成上线维护。')
})

test('compactResponsibility removes numbering across role data formats', () => {
  assert.equal(compactResponsibility('1、负责服务设计；保障稳定运行'), '负责服务设计')
  assert.equal(compactResponsibility('1.具备扎实的后端开发基础。'), '具备扎实的后端开发基础')
  assert.equal(compactResponsibility('2. 使用 Java 与 Spring Boot 开发、维护后端系统'), '使用 Java 与 Spring …')
  assert.equal(compactResponsibility('2'), '')
})

test('plan copy replaces broad skills with executable role-specific tasks', () => {
  assert.equal(planSkillLabel('后端开发'), '后端接口与业务模块')
  assert.equal(planSkillLabel('高并发'), '并发性能优化')
  const copy = planTaskCopy('后端开发', 'Java 开发工程师')
  assert.match(copy.task, /参数校验|异常处理|接口测试/)
  assert.doesNotMatch(copy.task, /完成一个使用后端开发的可运行任务/)
})

test('public career pages use concise descriptions and omit processing metadata copy', async () => {
  const paths = ['views/StudentHomeView.vue', 'views/JobLibraryView.vue', 'views/RoleEvolutionView.vue', 'views/GraphExplorerView.vue', 'views/DiagnosisView.vue']
  const content = (await Promise.all(paths.map(source))).join('\n')
  assert.doesNotMatch(content, /基于当前试验快照|不代表历史市场趋势|当前为只读静态快照|当前查看岗位|只展示当前岗位相关的一层关系/)
  assert.match(content, /shortRoleDescription/)
  assert.doesNotMatch(content, /data-disclaimer/)
  assert.match(content, /planSkillLabel/)
  assert.match(content, /planTaskCopy/)
})

test('role evolution declares real change and evidence panels', async () => {
  const evolution = await source('views/RoleEvolutionView.vue')
  assert.match(evolution, /evolution-change-board/)
  assert.match(evolution, /evolution-change-detail/)
  assert.match(evolution, /evolution-evidence-board/)
  assert.match(evolution, /查看原文证据/)
  assert.match(evolution, /变化类型|可信度|影响说明/)
})

test('relation view exposes full relation toggle and responsibility content', async () => {
  const graph = await source('views/GraphExplorerView.vue')
  assert.match(graph, /relation-expand-toggle/)
  assert.match(graph, /展开全部关系/)
  assert.match(graph, /FULL_RELATIONS/)
  assert.match(graph, /岗位职责/)
  assert.doesNotMatch(graph, /只展示当前岗位相关的一层关系，不展开全量网络/)
})

test('role evidence cards do not render responsibility descriptions', async () => {
  const graphExplorer = await source('views/GraphExplorerView.vue')
  assert.doesNotMatch(graphExplorer, /对应任务：/)
})
