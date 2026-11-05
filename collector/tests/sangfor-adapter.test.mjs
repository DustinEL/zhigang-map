import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeSangforRecord } from '../build-jd-corpus.mjs'

test('normalizeSangforRecord preserves the public job description and traceable detail URL', () => {
  const normalized = normalizeSangforRecord({
    positionId: 4559,
    title: '大模型安全测试工程师',
    description: '<p>岗位职责：负责大模型安全评测、提示注入和对抗测试。</p><p>任职要求：熟悉 Python、自动化测试和安全评估。</p>',
    minExperience: 3,
    maxExperience: 5,
    education: '本科',
    openedAt: '2026-08-12 09:30:00',
    functionName: '研发类',
    departmentName: '安全研发部',
    locationText: '',
    workPlaceText: '深圳市'
  })

  assert.equal(normalized.sourceRecordKey, '4559')
  assert.equal(normalized.jobTitle, '大模型安全测试工程师')
  assert.equal(normalized.company, '深信服科技股份有限公司')
  assert.equal(normalized.city, '深圳市')
  assert.match(normalized.rawText, /提示注入/)
  assert.equal(normalized.jobExperience, '3-5年')
  assert.equal(normalized.publishedAt, '2026-08-12T09:30:00+08:00')
  assert.equal(normalized.sourceUrl, 'https://hr.sangfor.com/Delivery/4559')
})
