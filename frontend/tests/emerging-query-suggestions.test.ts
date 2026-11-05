import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import type { EmergingQueryDiscovery } from '../src/types/models'
import { canPublishEmergingQuery, findPublicEmergingQuery, isPublicEmergingQuery, publicEmergingSuggestions } from '../src/utils/emerging-query-cache.ts'

function record(overrides: Partial<EmergingQueryDiscovery> = {}): EmergingQueryDiscovery {
  return {
    id: 1,
    query: '机器学习工程师',
    normalizedQuery: '机器学习工程师',
    status: 'EMERGING_ROLE',
    domain: '人工智能',
    name: '机器学习工程师',
    definition: '负责机器学习系统设计与实现',
    responsibilities: [],
    requiredSkills: [],
    bonusSkills: [],
    scenarios: [],
    nearestRoleSimilarity: 0,
    noveltyScore: 1,
    evidenceCount: 2,
    sourceCount: 2,
    fieldEvidenceComplete: true,
    webSearchUsed: false,
    publishable: true,
    publishedRoleId: 101,
    publishedReleaseId: 1,
    modelName: 'frozen',
    evidence: [],
    fieldEvidence: [],
    fromCache: true,
    cacheSimilarity: 1,
    createdAt: '2026-08-07T00:00:00.000Z',
    updatedAt: '2026-08-07T00:00:00.000Z',
    ...overrides
  }
}

describe('public emerging-query recommendations', () => {
  it('renders only records that the frozen matcher can resolve', () => {
    const items = [
      record(),
      record({ id: 2, query: 'partial-role', normalizedQuery: 'partial-role', name: 'Partial Role', status: 'EVIDENCE_INSUFFICIENT', evidenceCount: 0, sourceCount: 0, publishable: false, publishedRoleId: undefined, publishedReleaseId: undefined }),
      record({ id: 3, query: 'out-of-scope', normalizedQuery: 'out-of-scope', name: 'Out Of Scope', status: 'OUT_OF_SCOPE', evidenceCount: 0, sourceCount: 0, publishable: false, publishedRoleId: undefined, publishedReleaseId: undefined })
    ]

    const suggestions = publicEmergingSuggestions(items)

    assert.deepEqual(suggestions.map(item => item.id), [1])
    suggestions.forEach(item => {
      assert.equal(findPublicEmergingQuery(item.name || item.query, items)?.item.id, item.id)
    })
  })

  it('keeps unknown input refused instead of fabricating a result', () => {
    const items = [record()]
    assert.equal(findPublicEmergingQuery('unknown-role', items), null)
  })

  it('allows publication only for a complete publishable emerging role', () => {
    const eligible = record({ publishedRoleId: undefined, publishedReleaseId: undefined })
    assert.equal(canPublishEmergingQuery(eligible), true)

    for (const status of ['EXISTING_ROLE', 'WAITING_GENERATION', 'OUT_OF_SCOPE'] as const) {
      assert.equal(canPublishEmergingQuery(record({ status, publishedRoleId: undefined, publishedReleaseId: undefined })), false)
    }
    assert.equal(canPublishEmergingQuery(record({ status: 'EVIDENCE_INSUFFICIENT', evidenceCount: 1, sourceCount: 1, publishedRoleId: undefined, publishedReleaseId: undefined })), true)
    assert.equal(canPublishEmergingQuery(record({ status: 'EVIDENCE_INSUFFICIENT', evidenceCount: 0, publishedRoleId: undefined, publishedReleaseId: undefined })), false)
    assert.equal(canPublishEmergingQuery({ ...eligible, publishable: false }), false)
    assert.equal(canPublishEmergingQuery({ ...eligible, fieldEvidenceComplete: false }), false)
    assert.equal(canPublishEmergingQuery({ ...eligible, name: '' }), false)
    assert.equal(canPublishEmergingQuery({ ...eligible, definition: '' }), false)
    assert.equal(canPublishEmergingQuery({ ...eligible, publishedRoleId: 101 }), false)
  })

  it('hides legacy emerging records whose role title is pure English', () => {
    assert.equal(isPublicEmergingQuery(record({ name: 'Machine Learning Engineer' })), false)
    assert.equal(isPublicEmergingQuery(record({ name: 'AI Engineer' })), false)
    assert.equal(isPublicEmergingQuery(record({ name: 'AI Agent' })), true)
    assert.equal(isPublicEmergingQuery(record({ name: 'AI Agent 开发工程师' })), true)
  })
})
