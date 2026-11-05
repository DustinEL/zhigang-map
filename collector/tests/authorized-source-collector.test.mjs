import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import {
  collectAuthorizedSources,
  deduplicateDocuments,
  parseCsv,
  parseXlsx,
  simHash,
  validateManifest
} from '../authorized-source-collector.mjs'

function source(overrides = {}) {
  return {
    sourceId: 'authorized-test-source',
    sourceName: '授权测试来源',
    sourceTier: 'TIER_2',
    adapter: 'MANUAL_BATCH',
    timeScope: 'CURRENT',
    entryUrl: './jobs.csv',
    format: 'csv',
    licenseStatus: 'DOCUMENTED_PERMISSION',
    licenseNote: '测试中模拟已经人工留存的公开许可说明',
    enabled: true,
    ...overrides
  }
}

test('rejects a source ledger entry without documented permission', () => {
  assert.throws(() => validateManifest({ schemaVersion: '2.0.0', sources: [source({ licenseNote: 'TODO' })] }), /许可依据/)
})

test('parses quoted CSV fields', () => {
  const rows = parseCsv('jobTitle,company,rawText\r\n"AI Agent 工程师","企业 A","负责工具调用,并完成离线评测和证据引用"')
  assert.equal(rows[0].jobTitle, 'AI Agent 工程师')
  assert.match(rows[0].rawText, /工具调用,并完成/)
})

test('preserves source tier and time scope in the unified raw JD model', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'zhigang-collector-'))
  const manifestPath = join(directory, 'sources.local.json')
  await writeFile(join(directory, 'jobs.csv'), 'jobTitle,company,rawText\nJava 开发工程师,企业 A,负责 Java 服务开发并完成数据库访问接口测试与上线监控\n')
  const manifest = { schemaVersion: '2.0.0', sources: [source({ timeScope: 'HISTORICAL' })] }
  await writeFile(manifestPath, JSON.stringify(manifest))

  const output = await collectAuthorizedSources(manifest, manifestPath, new Date('2026-07-29T08:00:00.000Z'))

  assert.equal(output.documents.length, 1)
  assert.equal(output.documents[0].timeScope, 'HISTORICAL')
  assert.equal(output.documents[0].source.sourceTier, 'TIER_2')
  assert.equal(output.documents[0].source.adapter, 'MANUAL_BATCH')
})

test('filters exact duplicates and only marks near duplicates for review', () => {
  const base = '负责 Java 服务开发数据库访问接口测试代码评审性能优化上线监控和故障排查'
  const documents = [base, base, `${base}！`].map((rawText, index) => ({
    sourceRecordKey: `jd-${index}`,
    rawText,
    contentHash: createHash('sha256').update(rawText).digest('hex'),
    simHash: simHash(rawText)
  }))

  const result = deduplicateDocuments(documents)

  assert.equal(result.documents.length, 2)
  assert.equal(result.exactDuplicates.length, 1)
  assert.equal(result.nearDuplicates.length, 1)
  assert.equal(result.nearDuplicates[0].status, 'PENDING_HUMAN_CONFIRMATION')
})

function storedZip(files) {
  const localParts = []
  const centralParts = []
  let localOffset = 0
  for (const [name, content] of Object.entries(files)) {
    const nameBuffer = Buffer.from(name)
    const data = Buffer.from(content)
    const local = Buffer.alloc(30 + nameBuffer.length + data.length)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0, 8)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBuffer.length, 26)
    nameBuffer.copy(local, 30)
    data.copy(local, 30 + nameBuffer.length)
    localParts.push(local)

    const central = Buffer.alloc(46 + nameBuffer.length)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0, 10)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(nameBuffer.length, 28)
    central.writeUInt32LE(localOffset, 42)
    nameBuffer.copy(central, 46)
    centralParts.push(central)
    localOffset += local.length
  }
  const central = Buffer.concat(centralParts)
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(centralParts.length, 8)
  eocd.writeUInt16LE(centralParts.length, 10)
  eocd.writeUInt32LE(central.length, 12)
  eocd.writeUInt32LE(localOffset, 16)
  return Buffer.concat([...localParts, central, eocd])
}

test('reads the first worksheet from a local XLSX batch', () => {
  const sharedStrings = '<sst><si><t>jobTitle</t></si><si><t>rawText</t></si><si><t>Java 开发工程师</t></si><si><t>负责 Java 服务开发和完整测试交付</t></si></sst>'
  const sheet = '<worksheet><sheetData><row><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row><c r="A2" t="s"><v>2</v></c><c r="B2" t="s"><v>3</v></c></row></sheetData></worksheet>'
  const rows = parseXlsx(storedZip({ 'xl/sharedStrings.xml': sharedStrings, 'xl/worksheets/sheet1.xml': sheet }))
  assert.equal(rows[0].jobTitle, 'Java 开发工程师')
  assert.match(rows[0].rawText, /完整测试交付/)
})
