import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { filterPublicEmergingQueries, redactSnapshotSensitiveText, repairSnapshotText, resolvePublicDataFile } from './snapshot-quality.mjs'

const currentPath = resolve(process.argv[2] ?? 'public/data/current.json')
const dataRoot = dirname(currentPath)
const originalSnapshot = JSON.parse(await readFile(currentPath, 'utf8'))
const snapshot = redactSnapshotSensitiveText(repairSnapshotText(originalSnapshot))
const beforeCount = Array.isArray(snapshot.emergingQueries) ? snapshot.emergingQueries.length : 0
snapshot.emergingQueries = filterPublicEmergingQueries(snapshot.emergingQueries)
const removedCount = beforeCount - snapshot.emergingQueries.length
const serialized = `${JSON.stringify(snapshot, null, 2)}\n`

await writeFile(currentPath, serialized, 'utf8')
if (snapshot.snapshot?.code) {
  const versionedPath = resolvePublicDataFile(dataRoot, `/data/snapshots/${snapshot.snapshot.code}.json`)
  await writeFile(versionedPath, serialized, 'utf8')
}

let repairedFiles = JSON.stringify(originalSnapshot) === JSON.stringify(snapshot) ? 0 : 1
for (const publicPath of Object.values(snapshot.dataFiles ?? {})) {
  const targetPath = resolvePublicDataFile(dataRoot, publicPath)
  const original = JSON.parse(await readFile(targetPath, 'utf8'))
  const repaired = redactSnapshotSensitiveText(repairSnapshotText(original))
  if (JSON.stringify(original) === JSON.stringify(repaired)) continue
  await writeFile(targetPath, `${JSON.stringify(repaired)}\n`, 'utf8')
  repairedFiles += 1
}

console.log(`公开快照清理完成：保留 ${snapshot.emergingQueries.length} 条查询，过滤 ${removedCount} 条空查询、乱码或无公开证据记录，修复 ${repairedFiles} 个文件的可恢复乱码。`)
