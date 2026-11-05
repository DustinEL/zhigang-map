import type { DiagnosisComparison, LearningPlan, MatchReport, ResumeProfile, VerificationEvidenceType, VerificationSubmission } from '../types/models'
import { parseResumeText } from '../utils/resume-parser'
import { createPendingVerificationSubmission } from '../utils/static-verification'
import { frozenSnapshot } from './frozen-snapshot'

let profile: ResumeProfile | null = null
let matchReport: MatchReport | null = null
let learningPlan: LearningPlan | null = null
const verificationSubmissions = new Map<number, VerificationSubmission>()
let sequence = 1000

export interface FrozenPersonalSession {
  profile: ResumeProfile | null
  matchReport: MatchReport | null
  learningPlan: LearningPlan | null
  verificationSubmissions?: VerificationSubmission[]
}

function metadata(graphRelease?: string) {
  return { dataMode: 'FROZEN_CACHE' as const, graphRelease, evidenceAvailable: true }
}

function profileFromInput(input: { name?: string; education?: string; major?: string; graduationYear: number; weeklyHours: number; skills: string[]; projects: string[]; certificates: string[] }, quotes: Record<string, string> = {}) {
  profile = {
    id: ++sequence, name: input.name?.trim() || '匿名同学', education: input.education?.trim() || '未填写',
    major: input.major?.trim() || '未填写', graduationYear: input.graduationYear, weeklyHours: input.weeklyHours,
    abilities: input.skills.map(skill => ({ skill, level: 0, levelName: 'L0 自述', quote: quotes[skill] ?? `用户填写：${skill}`, sourceType: 'SELF_REPORT', confidence: 1 })),
    projects: input.projects, certificates: input.certificates, status: 'NEEDS_CONFIRMATION', metadata: metadata()
  }
  matchReport = null
  learningPlan = null
  return profile
}

async function parseText(text: string, weeklyHours: number, graduationYear: number) {
  const snapshot = await frozenSnapshot.load()
  const vocabulary = [...new Set(snapshot.roles.flatMap(role => [...role.requiredSkills, ...role.bonusSkills].map(item => item.skill)))]
  const parsed = parseResumeText(text, vocabulary)
  const result = profileFromInput({
    name: parsed.name,
    education: parsed.education,
    major: parsed.major,
    graduationYear,
    weeklyHours,
    skills: parsed.abilities.map(item => item.skill),
    projects: parsed.projects,
    certificates: parsed.certificates
  })
  profile = { ...result, abilities: parsed.abilities }
  return profile
}

async function reparseText(id: number, text: string, weeklyHours: number, graduationYear: number) {
  if (!profile || profile.id !== id) throw new Error('当前页面没有这份能力档案')
  const existingId = profile.id
  const result = await parseText(text, weeklyHours, graduationYear)
  profile = { ...result, id: existingId }
  return profile
}

async function updateProfile(input: { name: string; education: string; major: string; graduationYear: number; weeklyHours: number; skills: string[]; projects: string[]; certificates: string[]; confirmed: boolean }) {
  if (!profile) throw new Error('请先建立能力档案')
  const id = profile.id
  profile = {
    ...profileFromInput(input), id, status: input.confirmed ? 'CONFIRMED' : 'NEEDS_CONFIRMATION',
    metadata: profile.metadata
  }
  return profile
}

async function confirmProfile(id: number, confirmed: boolean) {
  if (!profile || profile.id !== id) throw new Error('当前页面没有这份能力档案')
  profile = { ...profile, status: confirmed ? 'CONFIRMED' : 'NEEDS_CONFIRMATION' }
  if (!confirmed) matchReport = null
  return profile
}

function applicationState(readiness: number) {
  if (readiness >= 75) return '可尝试申请'
  if (readiness >= 50) return '接近可申请'
  return '建议先补足关键能力'
}

async function match(resumeId: number, jobRoleId: number) {
  if (!profile || profile.id !== resumeId) throw new Error('请先在当前页面建立能力档案')
  const snapshot = await frozenSnapshot.load()
  const target = snapshot.roles.find(role => role.id === jobRoleId) ?? snapshot.roles[0]
  const abilityMap = new Map(profile.abilities.map(item => [item.skill.toLowerCase(), item]))
  const skillDetails = target.requiredSkills.map(requirement => {
    const ability = abilityMap.get(requirement.skill.toLowerCase())
    const evidenceLevel = ability?.level ?? 0
    return {
      skill: requirement.skill, matchType: ability ? 'EXACT_OR_ALIAS' : 'UNMATCHED', evidenceLevel, requiredLevel: 1,
      score: ability ? 100 : 0, resumeEvidence: ability?.quote ?? '未在当前能力档案中找到证据',
      gap: ability ? '已出现，需要用项目成果继续增强' : '缺少岗位要求技能',
      remedy: `完成一个使用 ${requirement.skill} 的可运行任务，并提交代码、说明和测试结果`,
      roleEvidenceLevel: 1, personalEvidenceLevel: evidenceLevel,
      alignmentState: evidenceLevel >= 1 ? 'SATISFIED' as const : 'MISSING' as const,
      roleEvidence: [], personalEvidence: ability ? [{ sourceType: ability.sourceType, quote: ability.quote, level: ability.level }] : [],
      gapReason: ability ? '当前已有可识别能力证据' : '个人能力镜像中没有该技能证据',
      nextAction: `完成一个使用 ${requirement.skill} 的可运行任务，并保留代码与测试结果`
    }
  })
  const covered = skillDetails.filter(item => item.evidenceLevel >= item.requiredLevel).length
  const readiness = Number((100 * covered / Math.max(1, skillDetails.length)).toFixed(1))
  const dimensions = [
    { name: '必备技能覆盖', weight: 0.7, rawScore: readiness, weightedScore: readiness * 0.7, explanation: '按当前岗位资料的必备技能与你的能力档案逐项比较' },
    { name: '项目证据', weight: 0.2, rawScore: profile.projects.length ? 70 : 0, weightedScore: (profile.projects.length ? 70 : 0) * 0.2, explanation: '只判断是否提供项目文字，不代表项目质量' },
    { name: '补充经历', weight: 0.1, rawScore: profile.certificates.length ? 60 : 0, weightedScore: (profile.certificates.length ? 60 : 0) * 0.1, explanation: '证书只作补充项' }
  ]
  const adjacentRoles = snapshot.roles.filter(role => role.id !== target.id).map(role => {
    const required = role.requiredSkills.map(item => item.skill.toLowerCase())
    const adjacentCovered = required.filter(skill => abilityMap.has(skill)).length
    const adjacentReadiness = Number((100 * adjacentCovered / Math.max(1, required.length)).toFixed(1))
    const reusableSkills = role.requiredSkills.map(item => item.skill).filter(skill => abilityMap.has(skill.toLowerCase()))
    const missingSkills = role.requiredSkills.map(item => item.skill).filter(skill => !abilityMap.has(skill.toLowerCase()))
    return { roleId: role.id, roleName: role.name, readiness: adjacentReadiness, keyGaps: Math.max(0, required.length - adjacentCovered), transferDistance: Number((1 - adjacentReadiness / 100).toFixed(3)), confidence: 0.5, reusableSkills, missingSkills, recommendedTransitionRole: /agent/i.test(target.name) ? 'AI 应用后端工程师' : '' }
  })
  matchReport = {
    id: ++sequence, resumeId, roleId: target.id, roleName: target.name, graphRelease: snapshot.snapshot.code,
    hardGates: [{ name: '学历与年限', expected: '当前岗位资料未形成可靠统一门槛', actual: profile.education, status: 'UNKNOWN', explanation: '不把小样本中的学历或年限直接当作淘汰条件' }],
    readiness, growthPotential: Math.min(100, readiness + 15), applicationState: applicationState(readiness), dimensions, skillDetails, adjacentRoles,
    createdAt: new Date().toISOString(), metadata: metadata(snapshot.snapshot.code)
  }
  return matchReport
}

async function plan(matchRunId: number, weeklyHours: number) {
  if (!matchReport || matchReport.id !== matchRunId) throw new Error('请先在当前页面完成岗位诊断')
  const gaps = matchReport.skillDetails.filter(item => item.evidenceLevel < item.requiredLevel)
  const itemHours = Math.max(6, weeklyHours)
  const totalHours = gaps.length * itemHours
  const estimatedWeeks = Math.max(4, Math.min(6, Math.ceil(totalHours / Math.max(1, weeklyHours))))
  learningPlan = {
    id: ++sequence, matchRunId, targetRole: matchReport.roleName, weeklyHours, estimatedWeeks, graphRelease: matchReport.graphRelease,
    items: gaps.map((gap, index) => {
      const weekStart = Math.max(1, Math.floor(index * estimatedWeeks / Math.max(1, gaps.length)) + 1)
      const weekEnd = Math.max(weekStart, Math.ceil((index + 1) * estimatedWeeks / Math.max(1, gaps.length)))
      return { order: index + 1, stage: index < 2 ? '建立基础' : '形成证据', skill: gap.skill, reason: gap.gap, prerequisites: index ? [gaps[index - 1].skill] : [], resources: ['自行选择具有公开许可的学习资料'], practicalTask: gap.remedy, acceptanceCriteria: '提交可运行代码、README 和至少一项测试结果', estimatedHours: itemHours, weekStart, weekEnd, evidenceDeliverable: '项目链接、实现说明、README 与测试结果' }
    }),
    metadata: metadata(matchReport.graphRelease)
  }
  return learningPlan
}

function restoreSession(state: FrozenPersonalSession) {
  profile = state.profile
  matchReport = state.matchReport
  learningPlan = state.learningPlan
  verificationSubmissions.clear()
  state.verificationSubmissions?.forEach(item => verificationSubmissions.set(item.id, item))
  sequence = Math.max(1000, profile?.id ?? 0, matchReport?.id ?? 0, learningPlan?.id ?? 0,
    ...[...verificationSubmissions.keys()])
}

function resetForTest() {
  restoreSession({ profile: null, matchReport: null, learningPlan: null })
}

export const frozenPersonal = {
  parseText,
  reparseText,
  unsupportedFile: () => { throw new Error('当前版本暂不支持上传文件，请选择“粘贴文字”或“手动填写”。') },
  createManual: (input: { name: string; education: string; major: string; graduationYear: number; weeklyHours: number; skills: string[]; projects: string[]; certificates: string[] }) => profileFromInput(input),
  updateProfile,
  confirmProfile,
  profile: async (id: number) => { if (!profile || profile.id !== id) throw new Error('当前页面没有这份能力档案'); return profile },
  deleteProfile: async (id: number) => {
    if (profile?.id === id) {
      profile = null
      matchReport = null
      learningPlan = null
      verificationSubmissions.clear()
    }
  },
  match,
  matchResult: async (id: number) => { if (!matchReport || matchReport.id !== id) throw new Error('当前页面没有这份诊断'); return matchReport },
  plan,
  verification: async (learningPlanId: number, itemOrder: number, evidenceType: VerificationEvidenceType, evidenceText: string, evidenceUrl: string): Promise<VerificationSubmission> => {
    if (!learningPlan || learningPlan.id !== learningPlanId) throw new Error('当前页面没有这份成长计划')
    if (!profile || !matchReport) throw new Error('当前页面没有可验证的能力镜像')
    const item = learningPlan.items.find(value => value.order === itemOrder)
    if (!item) throw new Error('当前成长计划中没有这项能力任务')
    const submission = createPendingVerificationSubmission({
      id: ++sequence,
      learningPlanId,
      itemOrder,
      skill: item.skill,
      evidenceType,
      evidenceUrl,
      evidenceText,
      createdAt: new Date().toISOString(),
    })
    submission.reviewReason = '已保存在当前浏览器，完整环境中由专业角色复核；审核通过前不会改变能力等级或准备度。'
    submission.validationChecks = ['STATIC_PENDING_REVIEW']
    verificationSubmissions.set(submission.id, submission)
    return submission
  },
  comparison: async (beforeMatchRunId: number, verificationSubmissionId: number): Promise<DiagnosisComparison> => {
    if (!matchReport || matchReport.id !== beforeMatchRunId) throw new Error('当前页面没有这份诊断')
    const submission = verificationSubmissions.get(verificationSubmissionId)
    if (!submission) throw new Error('当前浏览器没有这份能力证据提交')
    if (submission.status !== 'VERIFIED') throw new Error('只有审核通过的能力证据才能触发再次诊断')
    throw new Error('公开证据快照不提供专业审核，请在完整环境中完成审核与再次诊断')
  },
  restoreSession,
  resetForTest
}
