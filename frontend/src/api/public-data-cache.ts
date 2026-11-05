export interface PublicCacheRecord<T> {
  schema: 1
  key: string
  version: string
  savedAt: number
  data: T
}

export interface PublicCacheStore {
  get<T>(key: string): Promise<PublicCacheRecord<T> | undefined>
  set<T>(record: PublicCacheRecord<T>): Promise<void>
  delete(prefixes: string[]): Promise<void>
}

export interface PublicCacheInvalidationChannel {
  post(prefixes: string[]): void
  subscribe(listener: (prefixes: string[]) => void): () => void
}

interface PublicDataCacheOptions {
  store?: PublicCacheStore
  namespace: string
  channel?: PublicCacheInvalidationChannel
  getVersion: () => Promise<string>
  now?: () => number
}

export async function publishWithCacheInvalidation<T>(publish: () => Promise<T>, invalidate: () => Promise<void>) {
  const result = await publish()
  await invalidate().catch(() => undefined)
  return result
}

const DATABASE_NAME = 'zhigang-public-data'
const STORE_NAME = 'responses'

function browserInvalidationChannel(namespace: string): PublicCacheInvalidationChannel | undefined {
  if (typeof window === 'undefined' || typeof BroadcastChannel === 'undefined') return undefined
  const channel = new BroadcastChannel(`zhigang-public-data:${namespace}`)
  return {
    post: prefixes => channel.postMessage(prefixes),
    subscribe: listener => {
      const receive = (event: MessageEvent<unknown>) => {
        if (Array.isArray(event.data) && event.data.every(item => typeof item === 'string')) listener(event.data)
      }
      channel.addEventListener('message', receive)
      return () => channel.removeEventListener('message', receive)
    }
  }
}

function indexedDbStore(): PublicCacheStore {
  const open = () => new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME, { keyPath: 'key' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })

  return {
    async get<T>(key: string) {
      const database = await open()
      return await new Promise<PublicCacheRecord<T> | undefined>((resolve, reject) => {
        const request = database.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).get(key)
        request.onsuccess = () => resolve(request.result as PublicCacheRecord<T> | undefined)
        request.onerror = () => reject(request.error)
      }).finally(() => database.close())
    },
    async set<T>(record: PublicCacheRecord<T>) {
      const database = await open()
      await new Promise<void>((resolve, reject) => {
        const transaction = database.transaction(STORE_NAME, 'readwrite')
        transaction.objectStore(STORE_NAME).put(record)
        transaction.oncomplete = () => resolve()
        transaction.onerror = () => reject(transaction.error)
      }).finally(() => database.close())
    },
    async delete(prefixes: string[]) {
      const database = await open()
      await new Promise<void>((resolve, reject) => {
        const transaction = database.transaction(STORE_NAME, 'readwrite')
        const objectStore = transaction.objectStore(STORE_NAME)
        const request = objectStore.openKeyCursor()
        request.onsuccess = () => {
          const cursor = request.result
          if (!cursor) return
          const key = String(cursor.primaryKey)
          if (prefixes.some(prefix => key.startsWith(prefix))) objectStore.delete(cursor.primaryKey)
          cursor.continue()
        }
        transaction.oncomplete = () => resolve()
        transaction.onerror = () => reject(transaction.error)
      }).finally(() => database.close())
    }
  }
}

export function createPublicDataCache(options: PublicDataCacheOptions) {
  const store = options.store ?? indexedDbStore()
  const cacheKey = (key: string) => `${options.namespace}::${key}`
  const channel = options.channel ?? browserInvalidationChannel(options.namespace)
  const now = options.now ?? Date.now
  const pending = new Map<string, { epoch: number; promise: Promise<unknown> }>()
  let epoch = 0
  let pendingVersion: Promise<string> | null = null
  const getVersion = () => {
    pendingVersion ??= options.getVersion().finally(() => { pendingVersion = null })
    return pendingVersion
  }

  const fetchAndPersist = <T>(key: string, fetcher: () => Promise<T>) => {
    const requestEpoch = epoch
    const existing = pending.get(key)
    if (existing?.epoch === requestEpoch) return existing.promise as Promise<T>
    const run = async () => {
      for (let attempt = 0; attempt < 2; attempt += 1) {
        let versionBefore: string
        try {
          versionBefore = await getVersion()
        } catch {
          return fetcher()
        }
        const data = await fetcher()
        let versionAfter: string
        try {
          versionAfter = await getVersion()
        } catch {
          return data
        }
        if (versionBefore === versionAfter) {
          if (requestEpoch === epoch) {
            await store.set({ schema: 1, key, version: versionAfter, savedAt: now(), data }).catch(() => undefined)
          }
          return data
        }
        if (attempt === 1) return data
      }
      throw new Error('无法读取一致的公开数据版本')
    }
    let operation: Promise<T>
    operation = run().finally(() => {
      if (pending.get(key)?.promise === operation) pending.delete(key)
    })
    pending.set(key, { epoch: requestEpoch, promise: operation })
    return operation
  }

  const fetchWithoutStore = <T>(key: string, fetcher: () => Promise<T>) => {
    const requestEpoch = epoch
    const existing = pending.get(key)
    if (existing?.epoch === requestEpoch) return existing.promise as Promise<T>
    let operation: Promise<T>
    operation = fetcher().finally(() => {
      if (pending.get(key)?.promise === operation) pending.delete(key)
    })
    pending.set(key, { epoch: requestEpoch, promise: operation })
    return operation
  }

  const revalidate = async <T>(record: PublicCacheRecord<T>, fetcher: () => Promise<T>) => {
    const requestEpoch = epoch
    try {
      const version = await getVersion()
      if (requestEpoch === epoch && version !== record.version) await fetchAndPersist(record.key, fetcher)
    } catch {
      // The last successful database response remains authoritative while offline.
    }
  }

  const applyInvalidation = async (prefixes: string[]) => {
    epoch += 1
    pending.clear()
    await store.delete(prefixes.map(cacheKey))
  }

  channel?.subscribe(prefixes => { void applyInvalidation(prefixes) })

  return {
    async get<T>(key: string, fetcher: () => Promise<T>): Promise<T> {
      const physicalKey = cacheKey(key)
      let record: PublicCacheRecord<T> | undefined
      try {
        record = await store.get<T>(physicalKey)
      } catch {
        return fetchWithoutStore(physicalKey, fetcher)
      }
      if (record?.schema === 1) {
        void revalidate(record, fetcher)
        return record.data
      }
      return fetchAndPersist(physicalKey, fetcher)
    },
    async invalidate(prefixes: string[]) {
      const deletion = applyInvalidation(prefixes)
      channel?.post(prefixes)
      await deletion
    }
  }
}
