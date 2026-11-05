import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('resolves the backend hostname at request time after container restarts', async () => {
  const config = await readFile(new URL('../nginx.conf', import.meta.url), 'utf8')

  assert.match(config, /resolver\s+127\.0\.0\.11\s+valid=10s\b/)
  assert.match(config, /set\s+\$backend_upstream\s+backend:8080;/)
  assert.match(config, /proxy_pass\s+http:\/\/\$backend_upstream\$request_uri;/)
})
