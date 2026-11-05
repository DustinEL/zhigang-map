import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

import { deduplicateRecords, normalizeDate, qualityGate } from './build-jd-corpus.mjs'


function repairJobonlineCandidate(record) {
  if (record.sourceId !== 'jobonline-current-cn') return record
  const repaired = {
    ...record,
    publishedAt: normalizeDate(record.publishedAtOriginal),
  }
  repaired.qualityGate = qualityGate(repaired)
  return repaired
}

function prepareCandidatePool(baseRecords, recoveredRecords) {
  const repaired = recoveredRecords
    .filter(record => record.sourceId === 'jobonline-current-cn')
    .map(repairJobonlineCandidate)
    .filter(record => record.qualityGate.passed)
  const { unique, exactDuplicates } = deduplicateRecords([...baseRecords, ...repaired])
  return { records: unique, recoveredCount: repaired.length, exactDuplicateCount: exactDuplicates.length }
}

function mergeCandidatePools(baseRecords, supplementalRecords) {
  const qualifiedSupplement = supplementalRecords.filter(record => record.qualityGate?.passed !== false)
  const { unique, exactDuplicates } = deduplicateRecords([...baseRecords, ...qualifiedSupplement])
  return {
    records: unique,
    supplementQualifiedCount: qualifiedSupplement.length,
    exactDuplicateCount: exactDuplicates.length,
  }
}

function readJsonl(path) {
  return readFile(resolve(path), 'utf8').then(text => text
    .split(/\r?\n/)
    .filter(Boolean)
    .map(line => JSON.parse(line)))
}

function countBy(records, field) {
  const counts = {}
  for (const record of records) {
    const key = String(record[field] ?? '')
    counts[key] = (counts[key] ?? 0) + 1
  }
  return counts
}

async function main() {
  const args = process.argv.slice(2)
  const options = {}
  for (let index = 0; index < args.length; index += 2) options[args[index]] = args[index + 1]
  for (const name of ['--base', '--recovered', '--output', '--manifest']) {
    if (!options[name]) throw new Error(`缺少参数 ${name}`)
  }
  const baseRecords = await readJsonl(options['--base'])
  const recoveredRecords = await readJsonl(options['--recovered'])
  const result = prepareCandidatePool(baseRecords, recoveredRecords)
  await writeFile(
    resolve(options['--output']),
    result.records.map(record => `${JSON.stringify(record)}\n`).join(''),
    'utf8',
  )
  const manifest = {
    recordCount: result.records.length,
    baseCount: baseRecords.length,
    recoveredJobonlineCount: result.recoveredCount,
    exactDuplicateCount: result.exactDuplicateCount,
    bySourcePlatform: countBy(result.records, 'sourcePlatform'),
    companyCount: Object.keys(countBy(result.records, 'company')).length,
    cityCount: Object.keys(countBy(result.records, 'city')).length,
  }
  await writeFile(resolve(options['--manifest']), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  process.stdout.write(`${JSON.stringify(manifest)}\n`)
}

export { mergeCandidatePools, prepareCandidatePool, repairJobonlineCandidate }

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (import.meta.url === invokedPath) {
  main().catch(error => {
    process.stderr.write(`${error.stack ?? error.message}\n`)
    process.exitCode = 1
  })
}
