import assert from 'node:assert/strict'
import test from 'node:test'

import { normalizeJobonlineRecord } from '../build-jd-corpus.mjs'


test('normalizes a public Jobonline list item and detail response', () => {
  const summary = {
    id: 'position-123',
    positionName: 'Java 大模型平台工程师',
    companyName: '示例科技有限公司',
    provinceName: '广东省',
    cityName: '深圳市',
    areaName: '南山区',
    publishTime: 1786838400000,
  }
  const detail = {
    id: 'position-123',
    positionName: 'Java 大模型平台工程师',
    cityName: '深圳市',
    areaName: '南山区',
    jobAgeName: '3-5年',
    category: '社招',
    description: '岗位职责：负责大模型平台后端开发。任职要求：熟悉 Java、Spring Boot 和分布式系统。',
    jobRequirements: '具备良好的工程实践能力。',
    publishTime: 1786838400000,
  }
  const source = {
    sourceId: 'jobonline-current-cn',
    detailBaseUrl: 'https://www.jobonline.cn/positionDetail',
  }

  const record = normalizeJobonlineRecord(summary, detail, source)

  assert.equal(record.sourceRecordKey, 'position-123')
  assert.equal(record.company, '示例科技有限公司')
  assert.equal(record.city, '深圳市-南山区')
  assert.equal(record.jobExperience, '3-5年')
  assert.equal(record.sourceUrl, 'https://www.jobonline.cn/positionDetail?id=position-123')
  assert.match(record.rawText, /岗位职责/)
  assert.match(record.rawText, /工程实践能力/)
})
