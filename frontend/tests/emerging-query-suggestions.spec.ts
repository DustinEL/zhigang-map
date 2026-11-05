import { describe, expect, it } from 'vitest'
import type { EmergingQueryDiscovery } from '../src/types/models'
import { findPublicEmergingQuery, publicEmergingSuggestions } from '../src/utils/emerging-query-cache'

function record(overrides: Partial<EmergingQueryDiscovery> = {}): EmergingQueryDiscovery {
  return {
    id: 1,
    query: 'verified-role',
    normalizedQuery: 'verified-role',
    status: 'EMERGING_ROLE',
    domain: 'computer',
    name: 'Verified Role',
    definition: 'verified definition',
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

    expect(suggestions.map(item => item.id)).toEqual([1])
    suggestions.forEach(item => {
      expect(findPublicEmergingQuery(item.name || item.query, items)?.item.id).toBe(item.id)
    })
  })

  it('keeps unknown input refused instead of fabricating a result', () => {
    expect(findPublicEmergingQuery('unknown-role', [record()])).toBeNull()
  })
})
