import { defineStore } from 'pinia'
import { api, frozenCache, restoreFrozenPersonalSession } from '../api'
import type { DiagnosisComparison, JobRole, LearningPlan, MatchReport, ResumeProfile, VerificationEvidenceType, VerificationSubmission } from '../types/models'
import { clearCareerSession, loadCareerSession, saveCareerSession } from '../utils/career-session'
import { isActionableRole } from '../utils/role-copy'

export const sampleResumeText = '姓名：陈同学\n学历：本科 软件工程，预计 2027 年毕业。\n技能：Java、Spring Boot、MySQL、Redis、Linux、Docker。\n项目：校园二手交易平台，负责后端接口、数据库设计和 Redis 缓存；完成企业知识库课程项目，学习 RAG 和大语言模型。\n证书：软考软件设计师。'

export const useCareerStore = defineStore('career', {
  state: () => ({
    initialized: false,
    sessionRestored: false,
    sessionCleared: false,
    roles: [] as JobRole[],
    targetRoleId: 0,
    weeklyHours: 12,
    graduationYear: 2027,
    profile: null as ResumeProfile | null,
    match: null as MatchReport | null,
    plan: null as LearningPlan | null,
    comparison: null as DiagnosisComparison | null,
    verifiedOrders: [] as number[],
    verificationStatuses: {} as Record<number, VerificationSubmission>,
    resumeDraftText: '',
    dataMode: (frozenCache ? 'FROZEN_CACHE' : 'DEMO') as import('../types/models').RuntimeMode
  }),
  actions: {
    restoreSession() {
      if (this.sessionRestored) return
      this.sessionRestored = true
      if (!frozenCache) return
      const session = loadCareerSession()
      if (!session) return
      this.targetRoleId = session.targetRoleId
      this.weeklyHours = session.weeklyHours
      this.graduationYear = session.graduationYear
      this.profile = session.profile
      this.match = session.match
      this.plan = session.plan
      this.comparison = session.comparison
      this.verifiedOrders = session.verifiedOrders
      this.verificationStatuses = session.verificationStatuses
      this.resumeDraftText = session.resumeDraftText
      this.dataMode = 'FROZEN_CACHE'
      restoreFrozenPersonalSession({
        profile: this.profile,
        matchReport: this.match,
        learningPlan: this.plan,
        verificationSubmissions: Object.values(this.verificationStatuses)
      })
    },
    persistSession() {
      if (!frozenCache) return
      if (this.sessionCleared) {
        clearCareerSession()
        return
      }
      saveCareerSession({
        targetRoleId: this.targetRoleId,
        weeklyHours: this.weeklyHours,
        graduationYear: this.graduationYear,
        profile: this.profile,
        match: this.match,
        plan: this.plan,
        comparison: this.comparison,
        verifiedOrders: this.verifiedOrders,
        verificationStatuses: this.verificationStatuses,
        resumeDraftText: this.resumeDraftText
      })
    },
    async initialize(targetRoleId?: number) {
      this.restoreSession()
      if (!this.initialized) {
        this.roles = await api.roles()
        this.initialized = true
      }
      if (targetRoleId && this.roles.some(role => role.id === targetRoleId && isActionableRole(role))) this.targetRoleId = targetRoleId
      if (!isActionableRole(this.roles.find(role => role.id === this.targetRoleId) ?? { emerging: true, requiredSkills: [] })) {
        this.targetRoleId = this.roles.find(role => isActionableRole(role))?.id ?? 0
      }
    },
    clearResults() {
      this.match = null
      this.plan = null
      this.comparison = null
      this.verifiedOrders = []
      this.verificationStatuses = {}
    },
    async parseText(text: string) {
      this.sessionCleared = false
      this.resumeDraftText = text
      this.profile = this.profile
        ? await api.reparseResume(this.profile.id, text, this.weeklyHours, this.graduationYear)
        : await api.resume(text, this.weeklyHours, this.graduationYear)
      this.dataMode = this.profile.metadata?.dataMode ?? (frozenCache ? 'FROZEN_CACHE' : 'DEMO')
      this.clearResults()
      return this.profile
    },
    async parseFile(file: File) {
      this.sessionCleared = false
      this.profile = this.profile
        ? await api.reparseResumeFile(this.profile.id, file, this.weeklyHours, this.graduationYear)
        : await api.resumeFile(file, this.weeklyHours, this.graduationYear)
      this.dataMode = this.profile.metadata?.dataMode ?? (frozenCache ? 'FROZEN_CACHE' : 'DEMO')
      this.clearResults()
      return this.profile
    },
    async createManual(input: { name: string; education: string; major: string; skills: string[]; projects: string[]; certificates: string[] }) {
      this.sessionCleared = false
      this.profile = this.profile
        ? await api.updateResumeFacts(this.profile.id, { ...input, weeklyHours: this.weeklyHours, graduationYear: this.graduationYear, confirmed: false })
        : await api.abilityProfile({ ...input, weeklyHours: this.weeklyHours, graduationYear: this.graduationYear })
      this.dataMode = this.profile.metadata?.dataMode ?? (frozenCache ? 'FROZEN_CACHE' : 'DEMO')
      this.clearResults()
      return this.profile
    },
    async updateProfile(input: { name: string; education: string; major: string; skills: string[]; projects: string[]; certificates: string[]; confirmed: boolean }) {
      this.sessionCleared = false
      if (!this.profile) throw new Error('请先建立能力档案')
      this.profile = await api.updateResumeFacts(this.profile.id, { ...input, weeklyHours: this.weeklyHours, graduationYear: this.graduationYear })
      this.clearResults()
      return this.profile
    },
    async confirmProfile(confirmed: boolean) {
      this.sessionCleared = false
      if (!this.profile) throw new Error('请先建立能力档案')
      this.profile = await api.confirmResume(this.profile.id, confirmed)
      if (!confirmed) this.clearResults()
      return this.profile
    },
    async runMatch() {
      this.sessionCleared = false
      if (!this.profile) throw new Error('请先建立能力档案')
      const role = this.roles.find(item => item.id === this.targetRoleId)
      if (!role || !isActionableRole(role)) throw new Error('ROLE_NOT_ACTIONABLE: 新型岗位暂不能用于能力诊断或行动计划')
      this.match = await api.match(this.profile.id, this.targetRoleId)
      this.dataMode = this.match.metadata?.dataMode ?? this.dataMode
      this.plan = null
      this.comparison = null
      return this.match
    },
    async buildPlan() {
      this.sessionCleared = false
      if (!this.match) throw new Error('请先完成岗位诊断')
      const role = this.roles.find(item => item.id === this.match?.roleId)
      if (!role || !isActionableRole(role)) throw new Error('ROLE_NOT_ACTIONABLE: 新型岗位暂不能用于能力诊断或行动计划')
      this.plan = await api.learningPlan(this.match.id, this.weeklyHours)
      return this.plan
    },
    async verify(itemOrder: number, evidenceType: VerificationEvidenceType, evidenceText: string, evidenceUrl = '') {
      this.sessionCleared = false
      if (!this.plan || !this.match) throw new Error('请先生成成长计划')
      const submission = await api.verification(this.plan.id, itemOrder, evidenceType, evidenceText, evidenceUrl)
      this.verificationStatuses[itemOrder] = submission
      if (submission.status !== 'VERIFIED') return { submission, comparison: null }
      this.comparison = await api.diagnosisComparison(this.match.id, submission.id)
      this.match = await api.matchResult(this.comparison.afterMatchRunId)
      this.profile = await api.resumeFacts(this.match.resumeId)
      if (!this.verifiedOrders.includes(itemOrder)) this.verifiedOrders.push(itemOrder)
      return { submission, comparison: this.comparison }
    },
    async deleteProfile() {
      if (this.profile) await api.deleteResume(this.profile.id)
      this.targetRoleId = this.roles[0]?.id ?? 0
      this.weeklyHours = 12
      this.graduationYear = 2027
      this.profile = null
      this.resumeDraftText = ''
      this.clearResults()
      this.sessionCleared = true
      clearCareerSession()
    }
  }
})
