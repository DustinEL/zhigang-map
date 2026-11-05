import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeVivoRecord } from '../build-jd-corpus.mjs'

test('normalizeVivoRecord preserves the official title, description, location, and publication time', () => {
  const normalized = normalizeVivoRecord({
    job_id: 123,
    job_title: 'AI 安全测试工程师',
    requirement_org_name: '安全研发部',
    job_location_list: [{ city: '深圳', city_code: '440300', location: '深圳' }],
    job_category: '技术研发',
    yoe_min: 3,
    yoe_max: 5,
    job_desc: '<p>岗位职责：负责大模型安全评测、提示注入与对抗测试。</p><p>任职要求：熟悉 Python、安全测试和模型评测方法。</p>',
    publish_timestamp: 1786752000000
  })

  assert.equal(normalized.sourceRecordKey, '123')
  assert.equal(normalized.jobTitle, 'AI 安全测试工程师')
  assert.equal(normalized.company, '维沃移动通信有限公司（vivo）')
  assert.equal(normalized.city, '深圳')
  assert.match(normalized.rawText, /大模型安全评测/)
  assert.match(normalized.rawText, /任职要求/)
  assert.equal(normalized.jobExperience, '3-5年')
  assert.match(normalized.sourceUrl, /123/)
  assert.equal(normalized.publishedAt, '2026-08-15T00:00:00.000Z')
})
