import assert from 'node:assert/strict'
import test from 'node:test'
import { parseResumeText } from '../src/utils/resume-parser.ts'

const vocabulary = ['JavaScript', 'Java', 'Python', 'RAG', '工具调用']

test('简历字段只在边界明确且内容合理时写入个人档案', () => {
  const parsed = parseResumeText('姓名：匿名同学\n学历：本科\n专业：的 我精通python\n技能：Python', vocabulary)

  assert.equal(parsed.name, '匿名同学')
  assert.equal(parsed.education, '本科')
  assert.equal(parsed.major, undefined)
  assert.deepEqual(parsed.abilities.map(item => item.skill), ['Python'])
  assert.equal(parsed.abilities[0].level, 0)
})

test('项目和验证结果提高证据等级，否定描述不会生成能力', () => {
  const parsed = parseResumeText([
    '专业：软件工程',
    '项目：使用 JavaScript 开发管理平台，并提交测试结果。',
    '目前不会 Java。',
    '课程中学习 RAG。'
  ].join('\n'), vocabulary)

  assert.equal(parsed.major, '软件工程')
  assert.deepEqual(parsed.abilities.map(item => item.skill), ['JavaScript', 'RAG'])
  assert.equal(parsed.abilities.find(item => item.skill === 'JavaScript')?.level, 3)
  assert.equal(parsed.abilities.find(item => item.skill === 'RAG')?.level, 1)
})
