import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { roles } from '../src/api/offline.ts'
import { isActionableRole } from '../src/utils/role-copy.ts'

const targetRoleCodes = [
  'LLM_APP_ENGINEER',
  'AI_DATA_ENGINEER',
  'AI_SECURITY_TEST_ENGINEER'
]

test('三个已有完整 JD 的岗位在离线回退中保持可诊断', () => {
  for (const code of targetRoleCodes) {
    const role = roles.find(item => item.code === code)
    assert.ok(role, `${code} 必须存在`)
    assert.equal(role.emerging, false, `${code} 不能标为新兴岗位`)
    assert.ok(role.requiredSkills.length > 0, `${code} 必须包含必备技能画像`)
    assert.equal(isActionableRole(role), true, `${code} 必须进入岗位诊断`)
  }
})

test('真正的新兴岗位仍不能进入岗位诊断', () => {
  const role = roles.find(item => item.name === '大模型推理加速工程师')
  assert.ok(role, '大模型推理加速工程师必须保留在离线岗位图谱')
  assert.equal(role.emerging, true)
  assert.ok(role.requiredSkills.length > 0, '新兴岗位必须保留能力画像')
  assert.equal(isActionableRole(role), false, '新兴岗位不能进入岗位诊断')
})

test('新兴岗位发现页提供推理加速岗位入口和移除图谱操作', async () => {
  const source = await readFile(new URL('../src/views/PublicEmergingDiscoveryView.vue', import.meta.url), 'utf8')
  assert.match(source, /大模型推理加速工程师/)
  assert.match(source, /api\.emergingQueries\(100\)/)
  assert.match(source, /normalizeEmergingQuery/)
  assert.match(source, /new Map<string, EmergingQueryDiscovery>/)
  assert.match(source, /publishedRoleId/)
  assert.match(source, /removeFromGraph/)
  assert.match(source, /从图谱移除/)
  assert.match(source, /v-if="result\.publishedRoleId"[\s\S]*从图谱移除/)
})
