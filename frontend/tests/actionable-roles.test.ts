import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const sourceRoot = new URL('../src/', import.meta.url)

async function source(path: string) {
  return readFile(new URL(path, sourceRoot), 'utf8')
}

test('统一岗位筛选只允许非新型且有必备技能的岗位进入行动链路', async () => {
  const roleCopy = await source('utils/role-copy.ts')
  assert.match(roleCopy, /export function isActionableRole/)
  assert.match(roleCopy, /!role\.emerging/)
  assert.match(roleCopy, /role\.requiredSkills\.length > 0/)
})

test('诊断和岗位演化都使用统一的可行动岗位筛选', async () => {
  const [diagnosis, evolution] = await Promise.all([
    source('views/DiagnosisView.vue'),
    source('views/RoleEvolutionView.vue')
  ])
  assert.match(diagnosis, /isActionableRole\(role\)/)
  assert.match(evolution, /isActionableRole\(role\)/)
  assert.match(evolution, /isActionableRole\(role\)/)
})

test('直接传入新型岗位时不能写入诊断目标或生成行动计划', async () => {
  const [career, api] = await Promise.all([
    source('stores/career.ts'),
    source('api/index.ts')
  ])
  assert.match(career, /isActionableRole\(role\)/)
  assert.match(career, /目标岗位.*新型|ROLE_NOT_ACTIONABLE/)
  assert.match(api, /isActionableRole\(role\)/)
  assert.match(api, /ROLE_NOT_ACTIONABLE/)
})
