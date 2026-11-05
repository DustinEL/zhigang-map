import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { readFile } from 'node:fs/promises'
import { createPublicDataCache, publishWithCacheInvalidation, type PublicCacheInvalidationChannel, type PublicCacheRecord, type PublicCacheStore } from '../src/api/public-data-cache.ts'

class MemoryStore implements PublicCacheStore {
  readonly records = new Map<string, PublicCacheRecord<unknown>>()

  async get<T>(key: string) {
    return this.records.get(key) as PublicCacheRecord<T> | undefined
  }

  async set<T>(record: PublicCacheRecord<T>) {
    this.records.set(record.key, record as PublicCacheRecord<unknown>)
  }

  async delete(prefixes: string[]) {
    for (const key of this.records.keys()) {
      if (prefixes.some(prefix => key.startsWith(prefix))) this.records.delete(key)
    }
  }
}

const nextTask = () => new Promise(resolve => setTimeout(resolve, 0))
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

class MemoryChannelHub {
  private readonly listeners = new Set<(prefixes: string[]) => void>()

  create(): PublicCacheInvalidationChannel {
    let ownListener: ((prefixes: string[]) => void) | undefined
    return {
      post: prefixes => {
        this.listeners.forEach(listener => {
          if (listener !== ownListener) listener(prefixes)
        })
      },
      subscribe: listener => {
        ownListener = listener
        this.listeners.add(listener)
        return () => this.listeners.delete(listener)
      }
    }
  }
}

describe('public data persistent cache', () => {
  it('persists the first successful database response with its version', async () => {
    const store = new MemoryStore()
    const cache = createPublicDataCache({ store, namespace: 'test-api', getVersion: async () => 'db-v1' })

    const result = await cache.get('public-overview', async () => ({ rawDocumentCount: 317 }))

    assert.deepEqual(result, { rawDocumentCount: 317 })
    assert.deepEqual(store.records.get('test-api::public-overview'), {
      schema: 1,
      key: 'test-api::public-overview',
      version: 'db-v1',
      savedAt: store.records.get('test-api::public-overview')?.savedAt,
      data: { rawDocumentCount: 317 }
    })
  })

  it('returns cached data before background version validation finishes', async () => {
    const store = new MemoryStore()
    await store.set({ schema: 1, key: 'test-api::roles', version: 'db-v1', savedAt: 1, data: [{ id: 1 }] })
    let resolveVersion!: (version: string) => void
    const version = new Promise<string>(resolve => { resolveVersion = resolve })
    let fetchCount = 0
    const cache = createPublicDataCache({ store, namespace: 'test-api', getVersion: () => version })

    const result = await cache.get('roles', async () => {
      fetchCount += 1
      return [{ id: 2 }]
    })

    assert.deepEqual(result, [{ id: 1 }])
    assert.equal(fetchCount, 0)
    resolveVersion('db-v1')
    await nextTask()
    assert.equal(fetchCount, 0)
  })

  it('refreshes cached data in the background when the database version changes', async () => {
    const store = new MemoryStore()
    await store.set({ schema: 1, key: 'test-api::graph-overview', version: 'db-v1', savedAt: 1, data: { releaseVersion: 'v1' } })
    const cache = createPublicDataCache({ store, namespace: 'test-api', getVersion: async () => 'db-v2' })

    const immediate = await cache.get('graph-overview', async () => ({ releaseVersion: 'v2' }))
    await nextTask()

    assert.deepEqual(immediate, { releaseVersion: 'v1' })
    assert.deepEqual(store.records.get('test-api::graph-overview')?.data, { releaseVersion: 'v2' })
    assert.equal(store.records.get('test-api::graph-overview')?.version, 'db-v2')
  })

  it('keeps a real cached response when version validation fails', async () => {
    const store = new MemoryStore()
    const existing = { schema: 1 as const, key: 'test-api::role-7-graph', version: 'db-v1', savedAt: 1, data: { nodes: ['database'] } }
    await store.set(existing)
    const cache = createPublicDataCache({ store, namespace: 'test-api', getVersion: async () => { throw new Error('offline') } })

    const result = await cache.get('role-7-graph', async () => ({ nodes: ['demo'] }))
    await nextTask()

    assert.deepEqual(result, existing.data)
    assert.deepEqual(store.records.get('test-api::role-7-graph'), existing)
  })

  it('deduplicates concurrent first loads and invalidates selected records', async () => {
    const store = new MemoryStore()
    let fetchCount = 0
    const cache = createPublicDataCache({ store, namespace: 'test-api', getVersion: async () => 'db-v1' })
    const fetcher = async () => {
      fetchCount += 1
      return { rawDocumentCount: 317 }
    }

    const [first, second] = await Promise.all([
      cache.get('public-overview', fetcher),
      cache.get('public-overview', fetcher)
    ])
    await store.set({ schema: 1, key: 'test-api::unrelated', version: 'db-v1', savedAt: 1, data: true })
    await cache.invalidate(['public-overview'])

    assert.deepEqual(first, second)
    assert.equal(fetchCount, 1)
    assert.equal(store.records.has('test-api::public-overview'), false)
    assert.equal(store.records.has('test-api::unrelated'), true)
  })

  it('returns the database response when persistent storage cannot write', async () => {
    const store = new MemoryStore()
    store.set = async () => { throw new Error('storage full') }
    const cache = createPublicDataCache({ store, namespace: 'test-api', getVersion: async () => 'db-v1' })

    const result = await cache.get('public-overview', async () => ({ rawDocumentCount: 317 }))

    assert.deepEqual(result, { rawDocumentCount: 317 })
  })

  it('does not label an old response with a newer database version', async () => {
    const store = new MemoryStore()
    const firstFetch = deferred<{ value: string }>()
    const versions = ['db-v1', 'db-v2', 'db-v2', 'db-v2']
    let fetchCount = 0
    const cache = createPublicDataCache({
      store,
      namespace: 'test-api',
      getVersion: async () => versions.shift() ?? 'db-v2'
    })
    const resultPromise = cache.get('public-overview', async () => {
      fetchCount += 1
      if (fetchCount === 1) return firstFetch.promise
      return { value: 'new-data' }
    })

    await nextTask()
    firstFetch.resolve({ value: 'old-data' })
    const result = await resultPromise

    assert.deepEqual(result, { value: 'new-data' })
    assert.deepEqual(store.records.get('test-api::public-overview')?.data, { value: 'new-data' })
    assert.equal(store.records.get('test-api::public-overview')?.version, 'db-v2')
  })

  it('does not let an invalidated in-flight request repopulate the cache', async () => {
    const store = new MemoryStore()
    const response = deferred<{ value: string }>()
    const cache = createPublicDataCache({ store, namespace: 'test-api', getVersion: async () => 'db-v1' })
    const loading = cache.get('public-overview', () => response.promise)

    await nextTask()
    await cache.invalidate(['public-overview'])
    response.resolve({ value: 'stale' })
    await loading
    await nextTask()

    assert.equal(store.records.has('test-api::public-overview'), false)
  })

  it('keeps cache records isolated by API namespace', async () => {
    const store = new MemoryStore()
    const first = createPublicDataCache({ store, namespace: 'api-a', getVersion: async () => 'v1' })
    const second = createPublicDataCache({ store, namespace: 'api-b', getVersion: async () => 'v1' })

    await first.get('roles', async () => [{ id: 'a' }])
    const result = await second.get('roles', async () => [{ id: 'b' }])

    assert.deepEqual(result, [{ id: 'b' }])
    assert.deepEqual(store.records.get('api-a::roles')?.data, [{ id: 'a' }])
    assert.deepEqual(store.records.get('api-b::roles')?.data, [{ id: 'b' }])
  })

  it('propagates invalidation to another tab and blocks its in-flight write', async () => {
    const store = new MemoryStore()
    const hub = new MemoryChannelHub()
    const response = deferred<{ value: string }>()
    const firstTab = createPublicDataCache({ store, namespace: 'test-api', channel: hub.create(), getVersion: async () => 'v1' })
    const secondTab = createPublicDataCache({ store, namespace: 'test-api', channel: hub.create(), getVersion: async () => 'v1' })
    const loading = secondTab.get('public-overview', () => response.promise)

    await nextTask()
    await firstTab.invalidate(['public-overview'])
    response.resolve({ value: 'stale-from-second-tab' })
    await loading
    await nextTask()

    assert.equal(store.records.has('test-api::public-overview'), false)
  })
})

it('publication returns the backend result when cache invalidation fails', async () => {
  const published = { id: 7, publishedRoleId: 99 }
  let publishCount = 0
  let invalidateCount = 0
  const result = await publishWithCacheInvalidation(
    async () => {
      publishCount += 1
      return published
    },
    async () => {
      invalidateCount += 1
      throw new Error('IndexedDB unavailable')
    }
  )

  assert.equal(result, published)
  assert.equal(publishCount, 1)
  assert.equal(invalidateCount, 1)
})

it('emerging discovery and publication use the real write path', async () => {
  const source = await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8')
  assert.match(source, /discoverEmergingQuery:[\s\S]*?realWrite<EmergingQueryDiscovery>/)
  assert.match(source, /publishEmergingQuery:[\s\S]*?realWrite<EmergingQueryDiscovery>/)
})
