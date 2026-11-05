import assert from 'node:assert/strict'
import test from 'node:test'
import { inferRuntimeMode } from '../src/utils/runtime-mode.ts'

test('keeps declared runtime modes', () => {
  assert.equal(inferRuntimeMode({ dataMode: 'REAL' }), 'REAL')
  assert.equal(inferRuntimeMode({ dataMode: 'REAL_PENDING' }), 'REAL_PENDING')
  assert.equal(inferRuntimeMode({ dataMode: 'NO_DATA' }), 'NO_DATA')
  assert.equal(inferRuntimeMode({ dataMode: 'ERROR' }), 'ERROR')
  assert.equal(inferRuntimeMode({ metadata: { dataMode: 'FROZEN_CACHE' } }), 'FROZEN_CACHE')
})

test('detects demo and empty authoritative responses', () => {
  assert.equal(inferRuntimeMode({ demoData: true }), 'DEMO')
  assert.equal(inferRuntimeMode({ rawDocumentCount: 0, publishedRoleCount: 0 }), 'NO_DATA')
  assert.equal(inferRuntimeMode([]), 'NO_DATA')
})
