import assert from 'node:assert/strict'
import test from 'node:test'

import { mergeCandidatePools, repairJobonlineCandidate } from '../prepare-official-jd-candidates.mjs'


test('repairs Jobonline Unix-second publication dates and reruns the quality gate', () => {
  const record = {
    sourceId: 'jobonline-current-cn',
    sourceRecordKey: 'position-1',
    jobTitle: '大模型平台工程师',
    company: '示例科技有限公司',
    city: '深圳市-南山区',
    publishedAt: null,
    publishedAtOriginal: '1784103614',
    sourceUrl: 'https://www.jobonline.cn/positionDetail?id=position-1',
    originalPostingUrl: 'https://www.jobonline.cn/positionDetail?id=position-1',
    sourceCountry: 'CN',
    companyCountry: 'CN',
    language: 'zh-CN',
    liveAtCollection: true,
    relevanceStatus: 'TARGET_ROLE_MATCH',
    matchedRoleCodes: ['AI_PLATFORM_ENGINEER'],
    rawText: '岗位职责与任职要求'.repeat(30),
  }

  const repaired = repairJobonlineCandidate(record)

  assert.equal(repaired.publishedAt, '2026-07-15T08:20:14.000Z')
  assert.equal(repaired.qualityGate.passed, true)
  assert.deepEqual(repaired.qualityGate.reasons, [])
})

test('merges supplemental qualified records and removes exact body duplicates', () => {
  const base = [{ recordId: 'base-1', rawText: 'same body', contentHash: 'hash-1' }]
  const supplement = [
    { recordId: 'supplement-duplicate', rawText: 'same body', contentHash: 'hash-1', qualityGate: { passed: true } },
    { recordId: 'supplement-new', rawText: 'new body', contentHash: 'hash-2', qualityGate: { passed: true } },
    { recordId: 'supplement-rejected', rawText: 'rejected body', contentHash: 'hash-3', qualityGate: { passed: false } },
  ]

  const result = mergeCandidatePools(base, supplement)

  assert.deepEqual(result.records.map(record => record.recordId), ['base-1', 'supplement-new'])
  assert.equal(result.supplementQualifiedCount, 2)
  assert.equal(result.exactDuplicateCount, 1)
})
