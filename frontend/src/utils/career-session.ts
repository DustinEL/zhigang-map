import type { DiagnosisComparison, LearningPlan, MatchReport, ResumeProfile, VerificationSubmission } from '../types/models'

export const careerSessionKey = 'zhigang-career-session-v1'
const careerSessionVersion = 1 as const

export interface CareerSessionData {
  targetRoleId: number
  weeklyHours: number
  graduationYear: number
  profile: ResumeProfile | null
  match: MatchReport | null
  plan: LearningPlan | null
  comparison: DiagnosisComparison | null
  verifiedOrders: number[]
  verificationStatuses: Record<number, VerificationSubmission>
  resumeDraftText: string
}

interface CareerSessionEnvelope {
  version: typeof careerSessionVersion
  savedAt: string
  data: CareerSessionData
}

function storageOrNull(storage?: Storage | null) {
  if (storage !== undefined) return storage
  return typeof window === 'undefined' ? null : window.sessionStorage
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function isResumeProfile(value: unknown): value is ResumeProfile {
  return isRecord(value)
    && typeof value.id === 'number'
    && typeof value.name === 'string'
    && Array.isArray(value.abilities)
    && Array.isArray(value.projects)
    && Array.isArray(value.certificates)
}

function isMatchReport(value: unknown): value is MatchReport {
  return isRecord(value)
    && typeof value.id === 'number'
    && typeof value.resumeId === 'number'
    && typeof value.readiness === 'number'
    && Array.isArray(value.skillDetails)
}

function isLearningPlan(value: unknown): value is LearningPlan {
  return isRecord(value)
    && typeof value.id === 'number'
    && typeof value.matchRunId === 'number'
    && Array.isArray(value.items)
}

function isComparison(value: unknown): value is DiagnosisComparison {
  return isRecord(value)
    && typeof value.beforeMatchRunId === 'number'
    && typeof value.afterMatchRunId === 'number'
    && typeof value.improvement === 'number'
    && Array.isArray(value.upgradedSkills)
}

function isVerificationSubmission(value: unknown): value is VerificationSubmission {
  return isRecord(value)
    && typeof value.id === 'number'
    && typeof value.itemOrder === 'number'
    && typeof value.evidenceText === 'string'
    && ['PENDING_VERIFICATION', 'VERIFIED', 'REJECTED'].includes(String(value.status))
}

function isNullable<T>(value: unknown, guard: (candidate: unknown) => candidate is T): value is T | null {
  return value === null || guard(value)
}

function isCareerSessionData(value: unknown): value is CareerSessionData {
  if (!isRecord(value)
      || typeof value.targetRoleId !== 'number'
      || typeof value.weeklyHours !== 'number'
      || typeof value.graduationYear !== 'number'
      || typeof value.resumeDraftText !== 'string'
      || !isNullable(value.profile, isResumeProfile)
      || !isNullable(value.match, isMatchReport)
      || !isNullable(value.plan, isLearningPlan)
      || !isNullable(value.comparison, isComparison)
      || !Array.isArray(value.verifiedOrders)
      || !value.verifiedOrders.every(item => typeof item === 'number')
      || !isRecord(value.verificationStatuses)) return false
  return Object.values(value.verificationStatuses).every(isVerificationSubmission)
}

function isCareerSessionEnvelope(value: unknown): value is CareerSessionEnvelope {
  return isRecord(value)
    && value.version === careerSessionVersion
    && typeof value.savedAt === 'string'
    && isCareerSessionData(value.data)
}

export function redactPrivateText(value: string) {
  return value
    .replace(/[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/g, '[邮箱已隐藏]')
    .replace(/(?<!\d)1[3-9]\d{9}(?!\d)/g, '[手机号已隐藏]')
    .replace(/(?<!\d)\d{17}[\dXx](?!\d)/g, '[证件号已隐藏]')
    .replace(/(^|\n)\s*(?:姓名|地址|住址)\s*[：:]\s*[^\n]+/g, '$1匿名信息：已隐藏')
}

function anonymousProfile(profile: ResumeProfile | null): ResumeProfile | null {
  if (!profile) return null
  return {
    ...profile,
    name: '匿名同学',
    education: redactPrivateText(profile.education),
    major: redactPrivateText(profile.major),
    abilities: profile.abilities.map(item => ({ ...item, quote: redactPrivateText(item.quote) })),
    projects: profile.projects.map(redactPrivateText),
    certificates: profile.certificates.map(redactPrivateText),
    facts: profile.facts?.map(item => ({ ...item, value: redactPrivateText(item.value), normalizedValue: redactPrivateText(item.normalizedValue), evidenceQuote: redactPrivateText(item.evidenceQuote) }))
  }
}

export function prepareCareerSession(data: CareerSessionData): CareerSessionData {
  return {
    ...data,
    profile: anonymousProfile(data.profile),
    resumeDraftText: redactPrivateText(data.resumeDraftText),
    verificationStatuses: Object.fromEntries(Object.entries(data.verificationStatuses).map(([key, item]) => [key, {
      ...item,
      evidenceText: redactPrivateText(item.evidenceText),
      evidenceUrl: item.evidenceUrl?.startsWith('https://') ? item.evidenceUrl : ''
    }]))
  }
}

export function saveCareerSession(data: CareerSessionData, storage?: Storage | null) {
  const target = storageOrNull(storage)
  if (!target) return
  const envelope: CareerSessionEnvelope = {
    version: careerSessionVersion,
    savedAt: new Date().toISOString(),
    data: prepareCareerSession(data)
  }
  target.setItem(careerSessionKey, JSON.stringify(envelope))
}

export function loadCareerSession(storage?: Storage | null): CareerSessionData | null {
  const target = storageOrNull(storage)
  if (!target) return null
  const serialized = target.getItem(careerSessionKey)
  if (!serialized) return null
  try {
    const value: unknown = JSON.parse(serialized)
    if (!isCareerSessionEnvelope(value)) throw new Error('会话结构不受支持')
    return value.data
  } catch {
    target.removeItem(careerSessionKey)
    return null
  }
}

export function clearCareerSession(storage?: Storage | null) {
  storageOrNull(storage)?.removeItem(careerSessionKey)
}
