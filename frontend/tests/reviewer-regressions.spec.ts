import { createPinia, setActivePinia } from 'pinia'
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LearningPlan, MatchReport, ResumeProfile } from '../src/types/models'
import { resolvePublicDataFile } from '../scripts/snapshot-quality.mjs'

const temporaryDirectories: string[] = []

async function createTemporaryDataRoot() {
  const temporaryRoot = await mkdtemp(join(tmpdir(), 'zhigang-snapshot-path-'))
  temporaryDirectories.push(temporaryRoot)
  const dataRoot = join(temporaryRoot, 'data')
  const outsideRoot = join(temporaryRoot, 'outside')
  await Promise.all([
    mkdir(join(dataRoot, 'snapshots'), { recursive: true }),
    mkdir(outsideRoot, { recursive: true })
  ])
  return { dataRoot, outsideRoot }
}

async function createSymbolicLink(target: string, path: string, type: 'dir' | 'file') {
  try {
    await symlink(target, path, process.platform === 'win32' && type === 'dir' ? 'junction' : type)
    return true
  } catch (error) {
    const code = error && typeof error === 'object' && 'code' in error ? String(error.code) : ''
    if (['EPERM', 'EACCES', 'UNKNOWN'].includes(code)) return false
    throw error
  }
}

const profile: ResumeProfile = {
  id: 1,
  name: '匿名同学',
  education: '本科',
  major: '软件工程',
  graduationYear: 2027,
  weeklyHours: 12,
  abilities: [],
  projects: [],
  certificates: [],
  status: 'CONFIRMED'
}
const match: MatchReport = {
  id: 2,
  resumeId: 1,
  roleId: 1,
  roleName: 'Java 开发工程师',
  graphRelease: 'snapshot-v1',
  hardGates: [],
  readiness: 40,
  growthPotential: 55,
  applicationState: '建议先补足关键能力',
  dimensions: [],
  skillDetails: [],
  adjacentRoles: [],
  createdAt: '2026-08-06T00:00:00.000Z'
}
const plan: LearningPlan = {
  id: 3,
  matchRunId: 2,
  targetRole: match.roleName,
  weeklyHours: 12,
  estimatedWeeks: 4,
  graphRelease: match.graphRelease,
  items: [{ order: 1, stage: '形成证据', skill: 'Spring Boot', reason: '缺少证据', prerequisites: [], resources: [], practicalTask: '完成接口', acceptanceCriteria: '提交测试', estimatedHours: 12 }]
}

afterEach(async () => {
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
  vi.resetModules()
  await Promise.all(temporaryDirectories.splice(0).map(path => rm(path, { recursive: true, force: true })))
})

describe('静态演示证据回退', () => {
  it('frozen cache 未启用时也只能返回待验证', async () => {
    vi.stubEnv('VITE_STATIC_DEMO', 'true')
    vi.stubEnv('VITE_FROZEN_SNAPSHOT', 'false')
    const { api } = await import('../src/api/index')
    const submission = await api.verification(plan.id, 1, 'TEST_RESULT', '测试已完成', '')
    expect(submission.status).toBe('PENDING_VERIFICATION')
    expect(submission.reviewReason).toContain('审核通过前不会改变能力等级或准备度')
  })

  it('career store 收到待验证结果后不触发再次诊断或升级', async () => {
    vi.stubEnv('VITE_STATIC_DEMO', 'true')
    vi.stubEnv('VITE_FROZEN_SNAPSHOT', 'false')
    const [{ api }, { useCareerStore }] = await Promise.all([
      import('../src/api/index'),
      import('../src/stores/career')
    ])
    setActivePinia(createPinia())
    const store = useCareerStore()
    store.profile = structuredClone(profile)
    store.match = structuredClone(match)
    store.plan = structuredClone(plan)
    const comparison = vi.spyOn(api, 'diagnosisComparison')

    const result = await store.verify(1, 'CODE_DESCRIPTION', '普通完成说明', '')

    expect(result.submission.status).toBe('PENDING_VERIFICATION')
    expect(result.comparison).toBeNull()
    expect(comparison).not.toHaveBeenCalled()
    expect(store.profile).toEqual(profile)
    expect(store.match).toEqual(match)
    expect(store.verifiedOrders).toEqual([])
  })

  it('静态模式直接调用再次诊断也必须拒绝', async () => {
    vi.stubEnv('VITE_STATIC_DEMO', 'true')
    vi.stubEnv('VITE_FROZEN_SNAPSHOT', 'false')
    const { api } = await import('../src/api/index')

    await expect(api.diagnosisComparison(match.id, 4001)).rejects.toThrow(/专业审核及再次诊断仅在完整动态环境提供/)
  })
})

describe('快照数据文件路径白名单', () => {
  it('只解析公开数据根目录下的 /data/ 文件', async () => {
    const { dataRoot } = await createTemporaryDataRoot()
    const resolvedPath = resolvePublicDataFile(dataRoot, '/data/snapshots/current.json')
    expect(resolvedPath).toBe(resolve(dataRoot, 'snapshots/current.json'))
  })

  it.each([
    '../secret.json',
    'C:/secret.json',
    '/other/secret.json',
    '/data/../secret.json',
    '/data/snapshots/../../secret.json',
    '/data/C:/secret.json',
    '/data//server/share.json',
    '/data/snapshots\\secret.json'
  ])('拒绝越权或非白名单路径：%s', async value => {
    const { dataRoot } = await createTemporaryDataRoot()
    expect(() => resolvePublicDataFile(dataRoot, value)).toThrow(/白名单|不合法|反斜杠|越出/)
  })

  it('拒绝已存在文件通过符号链接逃出数据根目录', async ({ skip }) => {
    const { dataRoot, outsideRoot } = await createTemporaryDataRoot()
    await writeFile(join(outsideRoot, 'secret.json'), '{}\n', 'utf8')
    const linkedDirectory = join(dataRoot, 'linked-existing')
    if (!await createSymbolicLink(outsideRoot, linkedDirectory, 'dir')) {
      skip()
      return
    }

    expect(() => resolvePublicDataFile(dataRoot, '/data/linked-existing/secret.json')).toThrow(/真实路径越出/)
  })

  it('拒绝新文件通过符号链接父目录逃出数据根目录', async ({ skip }) => {
    const { dataRoot, outsideRoot } = await createTemporaryDataRoot()
    const linkedDirectory = join(dataRoot, 'linked-new')
    if (!await createSymbolicLink(outsideRoot, linkedDirectory, 'dir')) {
      skip()
      return
    }

    expect(() => resolvePublicDataFile(dataRoot, '/data/linked-new/new.json')).toThrow(/真实路径越出/)
  })

  it('拒绝目标文件自身为指向根目录外新文件的悬空符号链接', async ({ skip }) => {
    const { dataRoot, outsideRoot } = await createTemporaryDataRoot()
    const linkedFile = join(dataRoot, 'snapshots/dangling.json')
    if (!await createSymbolicLink(join(outsideRoot, 'not-created.json'), linkedFile, 'file')) {
      skip()
      return
    }

    expect(() => resolvePublicDataFile(dataRoot, '/data/snapshots/dangling.json')).toThrow(/不能是符号链接/)
  })
})
