import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile } from 'node:fs/promises'

test('所有岗位计划路径都不能限制为前三项或复用统一示例计划', async () => {
  const [frozenSource, apiSource, offlineSource, snapshotSource] = await Promise.all([
    readFile(new URL('../src/api/frozen-personal.ts', import.meta.url), 'utf8'),
    readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8'),
    readFile(new URL('../src/api/offline.ts', import.meta.url), 'utf8'),
    readFile(new URL('../public/data/current.json', import.meta.url), 'utf8')
  ])
  const snapshot = JSON.parse(snapshotSource) as { roles: Array<{ requiredSkills: unknown[] }> }

  assert.ok(snapshot.roles.some(role => role.requiredSkills.length > 3))
  assert.doesNotMatch(frozenSource, /\.slice\(0,\s*3\)/)
  assert.match(apiSource, /offline\.matchForRole/)
  assert.match(offlineSource, /offlineMatches\.get\(matchRunId\)/)
  assert.doesNotMatch(apiSource, /\.\.\.offline\.samplePlan\s*,\s*matchRunId/)
})
