<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { api, errorMessage } from '../api'
import { sampleResumeText } from '../stores/career'
import type { DiagnosisComparison, LearningPlan, MatchReport, ResumeProfile, RoleSkillEvidence } from '../types/models'

const running = ref(false)
const active = ref(0)
const profile = ref<ResumeProfile | null>(null)
const match = ref<MatchReport | null>(null)
const plan = ref<LearningPlan | null>(null)
const evidence = ref<RoleSkillEvidence | null>(null)
const comparison = ref<DiagnosisComparison | null>(null)
const steps = ['真实 JD 证据', '个人能力镜像', '双图对齐', '迁移与成长任务', '提交证据并再诊断']
const completed = computed(() => active.value >= steps.length)

async function run() {
  running.value = true
  active.value = 0
  comparison.value = null
  try {
    const roles = await api.roles()
    const role = roles.find(item => /agent|智能体/i.test(item.name)) ?? roles[0]
    if (!role) throw new Error('当前没有可体验岗位')
    const firstSkill = role.requiredSkills[0]?.skill
    if (!firstSkill) throw new Error('岗位画像尚未发布')
    evidence.value = await api.roleSkillEvidence(role.id, firstSkill); active.value = 1
    profile.value = await api.resume(sampleResumeText, 12, 2027); active.value = 2 // demo: weeklyHours=12, graduationYear=2027
    profile.value = await api.updateResumeFacts(profile.value.id, { name: profile.value.name, education: profile.value.education, major: profile.value.major, graduationYear: profile.value.graduationYear, weeklyHours: profile.value.weeklyHours, skills: profile.value.abilities.map(item => item.skill), projects: profile.value.projects, certificates: profile.value.certificates, confirmed: true })
    match.value = await api.match(profile.value.id, role.id); active.value = 3
    plan.value = await api.learningPlan(match.value.id, 12); active.value = 4 // demo: 12 weeks
    const task = plan.value.items[0]
    if (task) {
      const submission = await api.verification(plan.value.id, task.order, 'TEST_RESULT', `已完成${task.skill}任务，提交项目说明、README 与测试结果。`)
      comparison.value = await api.diagnosisComparison(match.value.id, submission.id)
      match.value = await api.matchResult(comparison.value.afterMatchRunId)
    }
    active.value = 5
    ElMessage.success('证据化成长闭环已完成')
  } catch (error) { ElMessage.error(errorMessage(error)) } finally { running.value = false }
}
</script>

<template>
  <div class="v10-page showcase-v10">
    <section class="showcase-hero-v10"><div><span class="v10-kicker">成果体验</span><h1>真实岗位证据，如何变成学生可验证的成长</h1><p>沿着岗位证据、个人能力、差距对齐、岗位迁移和再次诊断，体验完整的成长路径。</p></div><el-button type="primary" size="large" :icon="running ? Refresh : Check" :loading="running" @click="run">{{ completed ? '重新体验完整流程' : '开始完整体验' }}</el-button></section>
    <section class="showcase-steps"><article v-for="(item,index) in steps" :key="item" :class="{ done: active > index, active: active === index }"><i>{{ index + 1 }}</i><strong>{{ item }}</strong></article></section>
    <section class="showcase-story-grid">
      <article><span>01 · 岗位证据图</span><h2>{{ evidence?.roleName ?? '智能体应用开发工程师' }}</h2><p v-if="evidence">{{ evidence.skill }} 来自 {{ evidence.documentCount }} 条岗位依据、{{ evidence.companyCount }} 家企业。</p><blockquote v-if="evidence?.evidence[0]">{{ evidence.evidence[0].quote }}</blockquote><el-empty v-else description="开始体验后展示岗位原文" /></article>
      <article><span>02 · 个人能力镜像</span><h2>{{ profile?.name ?? '学生能力证据' }}</h2><div v-if="profile" class="showcase-tags"><b v-for="item in profile.abilities.slice(0, 7)" :key="item.skill">{{ item.skill }} · L{{ item.level }}</b></div><el-empty v-else description="运行后展示简历能力事实" /></article>
      <article class="wide"><span>03 · 双图对齐与迁移</span><h2>{{ match ? `${match.roleName} · 准备度 ${match.readiness}%` : '岗位要求与个人证据逐项比较' }}</h2><div v-if="match" class="showcase-alignments"><p v-for="item in match.skillDetails.slice(0, 6)" :key="item.skill"><b>{{ item.skill }} · 岗位 L{{ item.roleEvidenceLevel ?? item.requiredLevel }}</b><i /><strong>个人 L{{ item.personalEvidenceLevel ?? item.evidenceLevel }}</strong><em>{{ item.alignmentState ?? item.matchType }}</em></p></div><el-empty v-else description="运行后展示双图对齐" /></article>
      <article><span>04 · 可验收成长任务</span><h2>{{ plan ? `${plan.estimatedWeeks} 周行动计划` : '从关键缺口生成任务' }}</h2><div v-if="plan"><p v-for="item in plan.items" :key="item.order"><b>第 {{ item.weekStart }}–{{ item.weekEnd }} 周 · {{ item.skill }}</b><small>{{ item.evidenceDeliverable }}</small></p></div><el-empty v-else description="运行后展示行动任务" /></article>
      <article><span>05 · 证据验证与再次诊断</span><h2>{{ comparison ? `${comparison.beforeReadiness}% → ${comparison.afterReadiness}%` : '完成任务后不是结束' }}</h2><p v-if="comparison">已补齐 {{ comparison.upgradedSkills.join('、') }}；能力证据已写回个人镜像，再诊断变化 {{ comparison.improvement >= 0 ? '+' : '' }}{{ comparison.improvement }}。</p><el-empty v-else description="运行后展示成长变化" /></article>
    </section>
  </div>
</template>
