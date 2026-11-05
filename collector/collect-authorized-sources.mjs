import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { collectAuthorizedSources } from './authorized-source-collector.mjs'

const manifestPath = resolve(process.argv[2] ?? 'collector/sources.local.json')
const inboxDirectory = resolve(process.argv[3] ?? 'collector/inbox')
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
const output = await collectAuthorizedSources(manifest, manifestPath)

await mkdir(inboxDirectory, { recursive: true })
const stamp = output.observedAt.replace(/[-:]/g, '').replace(/\..+/, '').replace('T', '-')
const target = resolve(inboxDirectory, `collected-${stamp}.json`)
const temporary = `${target}.tmp`
await writeFile(temporary, `${JSON.stringify(output, null, 2)}\n`, 'utf8')
await rename(temporary, target)

console.log(`Collected ${output.documents.length} unique documents into ${target}`)
console.log(`Exact duplicates: ${output.exactDuplicates.length}; near duplicates awaiting review: ${output.nearDuplicates.length}`)
console.log('Status is PENDING_LOCAL_REVIEW; this file never replaces the published snapshot automatically.')
