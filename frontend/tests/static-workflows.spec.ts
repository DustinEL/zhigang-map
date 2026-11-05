import { beforeEach, describe, expect, it } from 'vitest'
import { frozenPersonal } from '../src/api/frozen-personal'
import type { EmergingQueryDiscovery, LearningPlan, MatchReport, ResumeProfile, VerificationEvidenceType } from '../src/types/models'
import { careerSessionKey, loadCareerSession, prepareCareerSession, saveCareerSession } from '../src/utils/career-session'
import { findPublicEmergingQuery, publicEmergingQueries } from '../src/utils/emerging-query-cache'

function emerging(overrides: Partial<EmergingQueryDiscovery>): EmergingQueryDiscovery {
  const now = '2026-08-06T00:00:00.000Z'
  return {
    id: 1,
    query: '大模型推理加速',
    normalizedQuery: '大模型推理加速',
    status: 'EMERGING_ROLE',
    domain: '人工智能',
    name: '大模型推理加速工程师',
    definition: '优化大模型推理吞吐与时延。',
    responsibilities: ['优化推理服务'],
    requiredSkills: ['Python'],
    bonusSkills: [],
    scenarios: ['在线推理'],
    nearestRoleSimilarity: 0.6,
    noveltyScore: 0.4,
    evidenceCount: 4,
    sourceCount: 4,
    fieldEvidenceComplete: true,
    webSearchUsed: true,
    publishable: true,
    modelName: 'frozen',
    evidence: [],
    fieldEvidence: [],
    fromCache: true,
    cacheSimilarity: 1,
    createdAt: now,
    updatedAt: now,
    ...overrides
  }
}

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>()
  get length() { return this.values.size }
  clear() { this.values.clear() }
  getItem(key: string) { return this.values.get(key) ?? null }
  key(index: number) { return [...this.values.keys()][index] ?? null }
  removeItem(key: string) { this.values.delete(key) }
  setItem(key: string, value: string) { this.values.set(key, value) }
}

const profile: ResumeProfile = {
  id: 1001,
  name: '陈同学',
  education: '本科',
  major: '软件工程',
  graduationYear: 2027,
  weeklyHours: 12,
  abilities: [{ skill: 'Java', level: 1, levelName: 'L1 自述', quote: '掌握 Java', sourceType: 'SELF_REPORT', confidence: 1 }],
  projects: [],
  certificates: [],
  status: 'CONFIRMED',
  metadata: { dataMode: 'FROZEN_CACHE' }
}
const match: MatchReport = {
  id: 1002,
  resumeId: profile.id,
  roleId: 1,
  roleName: 'Java 开发工程师',
  graphRelease: 'snapshot-v1',
  hardGates: [],
  readiness: 50,
  growthPotential: 65,
  applicationState: '接近可申请',
  dimensions: [],
  skillDetails: [],
  adjacentRoles: [],
  createdAt: '2026-08-06T00:00:00.000Z',
  metadata: { dataMode: 'FROZEN_CACHE' }
}
const plan: LearningPlan = {
  id: 1003,
  matchRunId: match.id,
  targetRole: match.roleName,
  weeklyHours: 12,
  estimatedWeeks: 4,
  graphRelease: match.graphRelease,
  items: [{ order: 1, stage: '形成证据', skill: 'Spring Boot', reason: '缺少证据', prerequisites: [], resources: [], practicalTask: '完成接口', acceptanceCriteria: '代码、说明和测试', estimatedHours: 12 }],
  metadata: { dataMode: 'FROZEN_CACHE' }
}

describe('公开证据快照查询', () => {
  const records = [
    emerging({ id: 8 }),
    emerging({ id: 6, query: '大模型推理加速工程师', normalizedQuery: '大模型推理加速工程师', status: 'EVIDENCE_INSUFFICIENT', evidenceCount: 1, sourceCount: 1, fieldEvidenceComplete: false }),
    emerging({ id: 5, query: '??????????', normalizedQuery: '', name: '??????????', status: 'EVIDENCE_INSUFFICIENT', evidenceCount: 0, sourceCount: 0, fieldEvidenceComplete: false }),
    emerging({ id: 2, query: 'Java 开发工程师', normalizedQuery: 'java开发工程师', name: 'Java 开发工程师', status: 'EXISTING_ROLE', evidenceCount: 0, sourceCount: 0, fieldEvidenceComplete: false })
  ]

  it('过滤空查询与乱码，并优先返回证据更完整的规范化精确结果', () => {
    expect(publicEmergingQueries(records).map(item => item.id)).toEqual([8, 6, 2])
    expect(findPublicEmergingQuery(' 大模型推理加速工程师 ', records)?.item.id).toBe(8)
  })

  it('只允许受控别名与高相似模糊命中，未知方向拒答', () => {
    expect(findPublicEmergingQuery('LLM 推理优化', records)?.matchType).toBe('ALIAS')
    expect(findPublicEmergingQuery('大模型推理加速方向', records)?.matchType).toBe('FUZZY')
    expect(findPublicEmergingQuery('量子芯片验证工程师', records)).toBeNull()
    expect(findPublicEmergingQuery('', records)).toBeNull()
  })
})

describe('静态能力证据可信度', () => {
  beforeEach(() => {
    frozenPersonal.resetForTest()
    frozenPersonal.restoreSession({ profile: structuredClone(profile), matchReport: structuredClone(match), learningPlan: structuredClone(plan) })
  })

  it.each<Array<[VerificationEvidenceType, string, string]>>([
    ['PROJECT_LINK', '普通说明', ''],
    ['CODE_DESCRIPTION', '这段文字与目标技能无关', ''],
    ['DOCUMENT', '我阅读了相关资料', 'https://example.com/readme'],
    ['TEST_RESULT', '测试已完成，但没有指标结果', 'https://example.com/test']
  ])('证据 %s 始终进入待验证且不改变档案与准备度', async (evidenceType, evidenceText, evidenceUrl) => {
    const beforeProfile = structuredClone(await frozenPersonal.profile(profile.id))
    const beforeMatch = structuredClone(await frozenPersonal.matchResult(match.id))
    const submission = await frozenPersonal.verification(plan.id, 1, evidenceType, evidenceText, evidenceUrl)
    expect(submission.status).toBe('PENDING_VERIFICATION')
    expect(await frozenPersonal.profile(profile.id)).toEqual(beforeProfile)
    expect(await frozenPersonal.matchResult(match.id)).toEqual(beforeMatch)
    await expect(frozenPersonal.comparison(match.id, submission.id)).rejects.toThrow('只有审核通过')
  })

  it('未知计划项不会生成待验证记录或改变能力', async () => {
    const beforeProfile = structuredClone(await frozenPersonal.profile(profile.id))
    await expect(frozenPersonal.verification(plan.id, 99, 'CODE_DESCRIPTION', '普通说明', '')).rejects.toThrow('没有这项能力任务')
    expect(await frozenPersonal.profile(profile.id)).toEqual(beforeProfile)
  })
})

describe('版本化职业会话', () => {
  it('保存匿名化会话并恢复目标岗位、档案、诊断和计划', () => {
    const storage = new MemoryStorage()
    saveCareerSession({
      targetRoleId: 1,
      weeklyHours: 12,
      graduationYear: 2027,
      profile: { ...profile, name: '陈同学', projects: ['联系邮箱 student@example.com'] },
      match,
      plan,
      comparison: null,
      verifiedOrders: [],
      verificationStatuses: {},
      resumeDraftText: '姓名：陈同学\n手机号：13812345678\n技能：Java'
    }, storage)
    const restored = loadCareerSession(storage)
    expect(restored?.profile?.name).toBe('匿名同学')
    expect(restored?.profile?.projects[0]).toBe('联系邮箱 [邮箱已隐藏]')
    expect(restored?.resumeDraftText).not.toContain('13812345678')
    expect(restored?.match?.readiness).toBe(50)
    expect(restored?.plan?.items).toHaveLength(1)
  })

  it('遇到未知版本或损坏结构时安全清空', () => {
    const storage = new MemoryStorage()
    storage.setItem(careerSessionKey, JSON.stringify({ version: 99, data: {} }))
    expect(loadCareerSession(storage)).toBeNull()
    expect(storage.getItem(careerSessionKey)).toBeNull()
    expect(prepareCareerSession({ targetRoleId: 0, weeklyHours: 12, graduationYear: 2027, profile: null, match: null, plan: null, comparison: null, verifiedOrders: [], verificationStatuses: {}, resumeDraftText: '' }).profile).toBeNull()
  })
})
