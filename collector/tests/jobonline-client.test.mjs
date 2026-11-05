import assert from 'node:assert/strict'
import { createCipheriv, createHash } from 'node:crypto'
import test from 'node:test'

import { createJobonlineClient } from '../jobonline-client.mjs'


test('encrypts public API requests and decrypts Jobonline responses', async () => {
  const randomValues = [1, 2, 3, 4].map(value => Buffer.alloc(16, value))
  const plainResponse = { code: '200', object: { rows: [{ id: 'position-1' }] } }
  const responseCipher = createCipheriv('sm4-cbc', randomValues[2], randomValues[3])
  const encryptedResponse = Buffer.concat([
    responseCipher.update(JSON.stringify(plainResponse), 'utf8'),
    responseCipher.final(),
  ]).toString('hex')
  let capturedRequest
  let capturedSm2Input
  const fetchImpl = async (url, options) => {
    if (url.endsWith('/msha/router')) {
      return { ok: true, json: async () => 9 }
    }
    capturedRequest = { url, options }
    return {
      ok: true,
      json: async () => ({ result: { Down_Encrypt_Data: encryptedResponse } }),
    }
  }
  const client = createJobonlineClient({
    fetchImpl,
    randomBytesImpl: () => randomValues.shift(),
    sm2EncryptImpl: input => {
      capturedSm2Input = input
      return 'aabb'
    },
  })

  const payload = { target: 'INDEX_PAGE', param: { page: 1, pagesize: 20 } }
  const response = await client.post('/positions', payload)

  assert.deepEqual(response, plainResponse)
  assert.equal(capturedRequest.options.headers.platform, '3')
  assert.equal(capturedRequest.options.headers.msha, '9')
  assert.equal(capturedRequest.options.headers['E-CONTENT-PATH'], '04aabb')
  assert.equal(
    capturedSm2Input,
    `${Buffer.alloc(16, 1).toString('hex')},${Buffer.alloc(16, 2).toString('hex')},${Buffer.alloc(16, 3).toString('hex')},${Buffer.alloc(16, 4).toString('hex')}`,
  )
  const { businessData } = JSON.parse(capturedRequest.options.body)
  assert.equal(
    capturedRequest.options.headers['E-SIGN'],
    createHash('sm3').update(`business${businessData}data`).digest('hex'),
  )
})
