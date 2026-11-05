import type { EmergingQueryDiscovery } from '../types/models'

export type FrozenQueryMatchType = 'EXACT' | 'ALIAS' | 'FUZZY'

export interface FrozenQueryMatch {
  item: EmergingQueryDiscovery
  matchType: FrozenQueryMatchType
}

export function canPublishEmergingQuery(item: EmergingQueryDiscovery) {
  return (item.status === 'EMERGING_ROLE' || item.status === 'EVIDENCE_INSUFFICIENT')
    && item.publishable
    && item.fieldEvidenceComplete
    && item.evidenceCount >= 1
    && Boolean(item.name?.trim())
    && Boolean(item.definition?.trim())
    && item.publishedRoleId == null
}

const controlledAliases = new Map<string, string>([
  ['llm推理加速', '大模型推理加速'],
  ['llm推理优化', '大模型推理加速'],
  ['大模型推理优化', '大模型推理加速'],
  ['大模型推理性能优化', '大模型推理加速']
])

const placeholderPattern = /\?{3,}/
const replacementCharacter = String.fromCodePoint(65533)
const mojibakeMarkers = [194, 195, 226, 228, 229, 230, 231, 232, 233, 239]
  .map(codePoint => String.fromCodePoint(codePoint))
const chineseCharacterPattern = /\p{Script=Han}/u
const acceptedTechnicalRoleNames = new Set(['aiagent', 'rag', 'llmops'])

export function hasPublicRoleName(value: string) {
  const name = value.trim()
  return chineseCharacterPattern.test(name)
    || acceptedTechnicalRoleNames.has(name.toLocaleLowerCase('en-US').replace(/[\s_-]+/g, ''))
}

export function normalizeEmergingQuery(value: string) {
  return value.normalize('NFKC')
    .trim()
    .toLocaleLowerCase('zh-CN')
    .replace(/[\s·•・,，。.!！?？:：;；/\\|_—–-]+/g, '')
    .replace(/(?:工程师|岗位|职位)$/u, '')
}

export function hasBrokenPublicText(value: string) {
  return placeholderPattern.test(value)
    || value.includes(replacementCharacter)
    || mojibakeMarkers.some(marker => value.includes(marker))
}

export function isPublicEmergingQuery(item: EmergingQueryDiscovery) {
  const query = item.query?.trim() ?? ''
  const roleName = item.name?.trim() || query
  const normalized = normalizeEmergingQuery(item.normalizedQuery || query)
  const evidenceStateValid = item.status !== 'WAITING_GENERATION'
    && (item.status !== 'EVIDENCE_INSUFFICIENT' || (item.evidenceCount > 0 && item.sourceCount > 0))
  return query.length > 0
    && normalized.length >= 2
    && evidenceStateValid
    && !hasBrokenPublicText(query)
    && !hasBrokenPublicText(item.normalizedQuery ?? '')
    && !hasBrokenPublicText(item.name ?? '')
    && hasPublicRoleName(roleName)
}

export function publicEmergingQueries(items: EmergingQueryDiscovery[], limit = items.length) {
  return items.filter(isPublicEmergingQuery).slice(0, Math.max(0, limit))
}

function qualityScore(item: EmergingQueryDiscovery) {
  const statusScore = ({ EMERGING_ROLE: 5, EXISTING_ROLE: 4, EVIDENCE_INSUFFICIENT: 3, WAITING_GENERATION: 2, OUT_OF_SCOPE: 1 } as const)[item.status]
  return statusScore * 1000 + Number(item.fieldEvidenceComplete) * 100 + item.sourceCount * 10 + item.evidenceCount
}

function canonicalQuery(value: string) {
  const normalized = normalizeEmergingQuery(value)
  return controlledAliases.get(normalized) ?? normalized
}

function recordQueries(item: EmergingQueryDiscovery) {
  return [item.query, item.normalizedQuery, item.name].filter(Boolean).map(canonicalQuery)
}

function deduplicateRecords(items: EmergingQueryDiscovery[]) {
  const selected = new Map<string, EmergingQueryDiscovery>()
  items.forEach(item => {
    const key = canonicalQuery(item.query || item.normalizedQuery || item.name)
    const current = selected.get(key)
    if (!current || qualityScore(item) > qualityScore(current)) selected.set(key, item)
  })
  return [...selected.values()]
}

function bigrams(value: string) {
  if (value.length < 2) return new Set([value])
  return new Set(Array.from({ length: value.length - 1 }, (_, index) => value.slice(index, index + 2)))
}

function similarity(left: string, right: string) {
  if (left === right) return 1
  const leftPairs = bigrams(left)
  const rightPairs = bigrams(right)
  let overlap = 0
  leftPairs.forEach(pair => { if (rightPairs.has(pair)) overlap += 1 })
  return 2 * overlap / Math.max(1, leftPairs.size + rightPairs.size)
}

function cachedResult(item: EmergingQueryDiscovery, input: string, score: number) {
  return {
    ...item,
    fromCache: true,
    cacheSimilarity: Number(score.toFixed(3)),
    matchedQuery: item.query || input
  }
}

export function findPublicEmergingQuery(input: string, items: EmergingQueryDiscovery[]): FrozenQueryMatch | null {
  const normalizedInput = normalizeEmergingQuery(input)
  if (normalizedInput.length < 2 || hasBrokenPublicText(input)) return null

  const records = publicEmergingQueries(items)
  const directMatches = records.filter(item => recordQueries(item).includes(normalizedInput))
  if (directMatches.length) {
    const selected = [...directMatches].sort((left, right) => qualityScore(right) - qualityScore(left))[0]
    return { item: cachedResult(selected, input, 1), matchType: 'EXACT' }
  }

  const canonicalInput = canonicalQuery(normalizedInput)
  if (canonicalInput !== normalizedInput) {
    const aliasMatches = records.filter(item => recordQueries(item).includes(canonicalInput))
    if (aliasMatches.length) {
      const selected = [...aliasMatches].sort((left, right) => qualityScore(right) - qualityScore(left))[0]
      return { item: cachedResult(selected, input, 1), matchType: 'ALIAS' }
    }
  }

  if (canonicalInput.length < 4) return null
  const ranked = deduplicateRecords(records).map(item => ({
    item,
    score: Math.max(...recordQueries(item).map(value => similarity(canonicalInput, value)))
  })).sort((left, right) => right.score - left.score || qualityScore(right.item) - qualityScore(left.item))
  const best = ranked[0]
  const runnerUp = ranked[1]
  if (!best || best.score < 0.78 || (runnerUp && best.score - runnerUp.score < 0.08)) return null
  return { item: cachedResult(best.item, input, best.score), matchType: 'FUZZY' }
}

/**
 * Return only recommendation records that can be resolved by the same frozen
 * query matcher used by the search form. This keeps stale or partial records
 * from becoming dead-end buttons in the public snapshot UI.
 */
export function publicEmergingSuggestions(items: EmergingQueryDiscovery[], limit = 6) {
  const records = publicEmergingQueries(items)
  return records
    .filter(item => item.status === 'EMERGING_ROLE'
      && item.publishedRoleId != null
      && item.publishedReleaseId != null)
    .filter(item => {
      const label = item.name || item.query
      return Boolean(label && findPublicEmergingQuery(label, records)?.item.id === item.id)
    })
    .slice(0, Math.max(0, limit))
}
