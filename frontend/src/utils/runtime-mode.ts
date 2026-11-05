import type { RuntimeMode } from '../types/models'

export function inferRuntimeMode(data: unknown): RuntimeMode {
  if (data == null || Array.isArray(data) && data.length === 0) return 'NO_DATA'
  if (typeof data !== 'object') return 'REAL'
  const record = data as Record<string, unknown>
  const metadata = record.metadata as Record<string, unknown> | undefined
  const declared = record.dataMode ?? metadata?.dataMode
  if (['REAL', 'REAL_PENDING', 'DEMO', 'FROZEN_CACHE', 'NO_DATA', 'ERROR'].includes(String(declared))) {
    return declared as RuntimeMode
  }
  if (record.demoData === true) return 'DEMO'
  if (record.operatingMode && String(record.operatingMode).includes('DEMO')) return 'DEMO'
  if (record.documentCount === 0 || record.rawDocumentCount === 0 && record.publishedRoleCount === 0) return 'NO_DATA'
  return 'REAL'
}
