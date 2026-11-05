import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'


const API_BASE_URL = 'https://api.jobonline.cn'
const PUBLIC_KEY_PATH = fileURLToPath(new URL('jobonline-sm2-public.pem', import.meta.url))

function readDerLength(buffer, state) {
  let length = buffer[state.index++]
  if (length < 128) return length
  let bytes = length & 0x7f
  length = 0
  while (bytes > 0) {
    length = (length << 8) | buffer[state.index++]
    bytes -= 1
  }
  return length
}

function readDerValue(buffer, state, expectedTag) {
  if (buffer[state.index++] !== expectedTag) throw new Error('OpenSSL 返回了无法识别的 SM2 密文')
  const length = readDerLength(buffer, state)
  const value = buffer.subarray(state.index, state.index + length)
  state.index += length
  return value
}

function padCoordinate(value) {
  const coordinate = value[0] === 0 ? value.subarray(1) : value
  if (coordinate.length > 32) throw new Error('SM2 坐标长度无效')
  return Buffer.concat([Buffer.alloc(32 - coordinate.length), coordinate])
}

function opensslSm2Encrypt(plainText) {
  const completed = spawnSync(
    process.env.OPENSSL_BIN || 'openssl',
    ['pkeyutl', '-encrypt', '-pubin', '-inkey', PUBLIC_KEY_PATH],
    { input: Buffer.from(plainText, 'utf8'), maxBuffer: 1024 * 1024 },
  )
  if (completed.status !== 0) {
    throw new Error(`OpenSSL SM2 加密失败：${completed.stderr.toString('utf8').trim()}`)
  }
  const outerState = { index: 0 }
  const sequence = readDerValue(completed.stdout, outerState, 0x30)
  const state = { index: 0 }
  const x = readDerValue(sequence, state, 0x02)
  const y = readDerValue(sequence, state, 0x02)
  const digest = readDerValue(sequence, state, 0x04)
  const encrypted = readDerValue(sequence, state, 0x04)
  return Buffer.concat([padCoordinate(x), padCoordinate(y), digest, encrypted]).toString('hex')
}

function sm4Encrypt(value, key, iv) {
  const cipher = createCipheriv('sm4-cbc', key, iv)
  return Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]).toString('hex')
}

function sm4Decrypt(value, key, iv) {
  const decipher = createDecipheriv('sm4-cbc', key, iv)
  const plainText = Buffer.concat([
    decipher.update(Buffer.from(value, 'hex')),
    decipher.final(),
  ]).toString('utf8')
  return JSON.parse(plainText)
}

function createJobonlineClient({
  fetchImpl = fetch,
  randomBytesImpl = randomBytes,
  sm2EncryptImpl = opensslSm2Encrypt,
} = {}) {
  let mshaPromise

  async function getMsha() {
    if (!mshaPromise) {
      mshaPromise = fetchImpl(`${API_BASE_URL}/msha/router`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      }).then(async response => {
        if (!response.ok) throw new Error(`中国就业网路由请求失败：HTTP ${response.status}`)
        return String(await response.json())
      })
    }
    return mshaPromise
  }

  async function post(path, payload) {
    const upKey = randomBytesImpl(16)
    const upIv = randomBytesImpl(16)
    const downKey = randomBytesImpl(16)
    const downIv = randomBytesImpl(16)
    const businessData = sm4Encrypt(payload, upKey, upIv)
    const keyEnvelope = [upKey, upIv, downKey, downIv].map(value => value.toString('hex')).join(',')
    const body = JSON.stringify({ businessData })
    const response = await fetchImpl(`${API_BASE_URL}${path}`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json;charset=UTF-8',
        EncryptFlag: '2',
        'E-CONTENT-PATH': `04${sm2EncryptImpl(keyEnvelope)}`,
        'E-SIGN': createHash('sm3').update(`business${businessData}data`).digest('hex'),
        'E-VERSION': 'v2.0.0',
        Origin: 'https://www.jobonline.cn',
        Referer: 'https://www.jobonline.cn/position',
        platform: '3',
        msha: await getMsha(),
      },
      body,
    })
    if (!response.ok) throw new Error(`中国就业网公开接口请求失败：HTTP ${response.status}`)
    const outer = await response.json()
    const encryptedResponse = outer?.result?.Down_Encrypt_Data
    return encryptedResponse ? sm4Decrypt(encryptedResponse, downKey, downIv) : outer
  }

  return { post }
}

export { createJobonlineClient, opensslSm2Encrypt }
