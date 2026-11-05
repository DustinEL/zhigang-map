import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('三个岗位拥有相互隔离的真实演化结果', async () => {
  const snapshot = JSON.parse(await readFile(new URL('../public/data/current.json', import.meta.url), 'utf8'))
  for (const code of ['LLM_APP_ENGINEER', 'AI_DATA_ENGINEER', 'AI_SECURITY_TEST_ENGINEER']) {
    const role = snapshot.roles.find((item: { code: string }) => item.code === code)
    assert.ok(role, `${code} 必须存在于岗位目录`)
    const run = snapshot.roleEvolutions?.[String(role.id)]
    assert.ok(run, `${code} 必须有岗位演化运行`)
    assert.equal(run.roleCode, code)
    assert.equal(run.roleId, role.id)
    assert.equal(run.windows.length, 2)
    assert.ok(run.windows.some((item: { documentCount: number }) => item.documentCount > 0))
    for (const change of run.changes) {
      assert.ok(change.evidence.every((item: { documentId: number }) => item.documentId > 0))
    }
  }
})
