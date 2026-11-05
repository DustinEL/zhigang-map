import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildExpandedManifest,
  excludeExactExposedRecords
} from '../official-jd-pipeline.mjs'

test('buildExpandedManifest broadens public-source coverage without mutating the base manifest', () => {
  const base = {
    schemaVersion: '3.0.0',
    requirements: { candidateMinimum: 120 },
    selectionPolicy: { minimumTextLength: 200 },
    sources: [
      {
        adapter: 'IGUOPIN',
        searchKeywords: ['Java'],
        pageCountPerKeyword: 6,
        maximumRecords: 260,
        maximumRecordsPerCompany: 10
      },
      {
        adapter: 'NCSS',
        searchKeywords: ['人工智能'],
        pageCountPerKeyword: 5,
        maximumDetailRequests: 240,
        maximumRecords: 140
      },
      {
        adapter: 'TENCENT',
        pageCount: 12,
        maximumDetailRequests: 180,
        maximumRecords: 120
      }
    ]
  }

  const expanded = buildExpandedManifest(base)

  assert.deepEqual(base.sources[0].searchKeywords, ['Java'])
  assert(expanded.sources[0].searchKeywords.includes('向量数据库'))
  assert(expanded.sources[0].pageCountPerKeyword >= 15)
  assert(expanded.sources[0].maximumRecordsPerCompany <= 6)
  assert(expanded.sources[1].searchKeywords.includes('AI安全测试'))
  assert(expanded.sources[1].maximumDetailRequests >= 400)
  assert(expanded.sources[2].maximumRecords >= 200)
  assert(expanded.sources.some(source => source.adapter === 'VIVO'))
  assert(expanded.sources.some(source => source.adapter === 'SANGFOR'))
  const jobonline = expanded.sources.find(source => source.adapter === 'JOBONLINE')
  assert(jobonline)
  assert(jobonline.searchKeywords.includes('知识工程'))
  assert(jobonline.maximumDetailRequests >= 400)
  assert.equal(jobonline.maximumRecordsPerCompany, 6)
})

test('excludeExactExposedRecords removes candidates whose body hash was previously exposed', () => {
  const candidates = [
    { recordId: 'new-1', contentHash: 'a'.repeat(64) },
    { recordId: 'old-1', contentHash: 'b'.repeat(64) },
    { recordId: 'new-2', contentSha256: 'c'.repeat(64) }
  ]

  const result = excludeExactExposedRecords(candidates, new Set(['b'.repeat(64)]))

  assert.deepEqual(result.retained.map(record => record.recordId), ['new-1', 'new-2'])
  assert.deepEqual(result.excluded.map(record => record.recordId), ['old-1'])
})
