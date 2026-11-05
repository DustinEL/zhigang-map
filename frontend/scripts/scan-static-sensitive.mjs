import { readdir, readFile } from 'node:fs/promises'
import { basename, extname, resolve } from 'node:path'
import { collectSnapshotQualityIssues, serializedTextIssues } from './snapshot-quality.mjs'

const root = resolve(process.argv[2] ?? 'dist-static')
const forbiddenNames = [/^\.env(?:\.|$)/i, /^application(?:-[^.]+)?\.ya?ml$/i, /^pom\.xml$/i]
const forbiddenExtensions = new Set(['.sql', '.log', '.pem', '.key', '.p12', '.jks', '.map'])
const sensitivePatterns = [
  ['private key', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/],
  ['database password', /(?:spring\.datasource\.password|MYSQL_PASSWORD)\s*[:=]/i],
  ['professional password', /(?:PROFESSIONAL_PASSWORD|REVIEWER_PASSWORD)\s*[:=]/i],
  ['API secret', /(?:OPENAI_API_KEY|SECRET_ACCESS_KEY)\s*[:=]/i]
]
const personalInformationPatterns = [
  ['email address', /[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/],
  ['mobile number', /(?<!\d)1[3-9]\d{9}(?!\d)/],
  ['identity number', /(?<!\d)\d{17}[\dXx](?!\d)/]
]
const contentQualityPatterns = [
  ['Unicode escape', /\\u[0-9a-f]{4}/i],
  ['replacement or mojibake text', /(?:�|ï¿½|(?:Ã|Â|â€|æ|ç|å)[\x80-\xff])/i],
  ['question-mark placeholder', /\?{3,}/]
]

async function files(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map(entry => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? files(path) : [path]
  }))
  return nested.flat()
}

const issues = []
for (const file of await files(root)) {
  const name = basename(file)
  if (forbiddenNames.some(pattern => pattern.test(name)) || forbiddenExtensions.has(extname(name).toLowerCase())) {
    issues.push(`${file}: forbidden deployment file`)
    continue
  }
  const buffer = await readFile(file)
  if (buffer.includes(0)) continue
  const content = buffer.toString('utf8')
  const normalizedPath = file.replaceAll('\\', '/')
  const dataJson = normalizedPath.includes('/data/') && extname(file).toLowerCase() === '.json'
  if (dataJson) {
    for (const issue of [...serializedTextIssues(content, file), ...collectSnapshotQualityIssues(JSON.parse(content), file)]) issues.push(issue)
    continue
  }
  for (const [label, pattern] of sensitivePatterns) {
    if (pattern.test(content)) issues.push(`${file}: ${label}`)
  }
  if (['.html', '.txt', '.json'].includes(extname(file).toLowerCase())) {
    for (const [label, pattern] of personalInformationPatterns) {
      if (pattern.test(content)) issues.push(`${file}: ${label}`)
    }
  }
  for (const [label, pattern] of contentQualityPatterns) {
    if (label !== 'Unicode escape' || extname(file).toLowerCase() !== '.js') {
      if (pattern.test(content)) issues.push(`${file}: ${label}`)
    }
  }
}

if (issues.length) {
  issues.forEach(issue => console.error(`- ${issue}`))
  process.exitCode = 1
} else {
  console.log(`Static package sensitive-information scan passed: ${root}`)
}
