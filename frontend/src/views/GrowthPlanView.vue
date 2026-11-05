<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus/es/components/message/index'
import { errorMessage, frozenCache } from '../api'
import { useCareerStore } from '../stores/career'
import type { VerificationEvidenceType } from '../types/models'
import { repairPublicText } from '../utils/public-text'
import { planSkillLabel, planTaskCopy } from '../utils/role-copy'

const router = useRouter()
const career = useCareerStore()
const busy = ref(false)
const verificationOrder = ref<number | null>(null)
const evidenceType = ref<VerificationEvidenceType>('PROJECT_LINK')
const evidenceText = ref('')
const evidenceUrl = ref('')
const expandedPlanOrders = ref<number[]>([])
const evidenceTypes: Array<{ value: VerificationEvidenceType; label: string }> = [
  { value: 'PROJECT_LINK', label: '项目链接' }, { value: 'CODE_DESCRIPTION', label: '代码说明' },
  { value: 'TEST_RESULT', label: '测试结果' }, { value: 'DOCUMENT', label: '项目文档' }, { value: 'CERTIFICATE', label: '证书' }
]
const remainingGaps = computed(() => career.match?.skillDetails.filter(item => item.alignmentState !== 'BONUS' && (item.personalEvidenceLevel ?? item.evidenceLevel) < (item.roleEvidenceLevel ?? item.requiredLevel)).slice(0, 3) ?? [])
const stageLabels: Record<string, string> = { FOUNDATION: '建立基础', PRACTICE: '岗位实践', EVIDENCE: '形成证据', APPLY: '投递准备', 'L4 实践': '岗位实践', 'L2 实践': '基础实践' }
function stageLabel(value: string) { return stageLabels[value] ?? value.replace(/implementation/ig, '实践').replace(/practice/ig, '实践') }
function normalizePlanLabels() {
  career.plan?.items.forEach(item => {
    const skill = repairPublicText(item.skill) ?? item.skill
    const label = planSkillLabel(skill)
    const copy = planTaskCopy(skill, career.match?.roleName ?? '目标岗位')
    const reasonFallback = copy.reason
    const taskFallback = copy.task
    const acceptanceFallback = copy.acceptance
    item.skill = label
    item.stage = stageLabel(repairPublicText(item.stage) ?? item.stage)
    item.reason = reasonFallback
    item.resources = item.resources.map(resource => repairPublicText(resource) ?? copy.resources)
    item.resources = [copy.resources]
    item.practicalTask = repairPublicText(item.practicalTask) ?? taskFallback
    item.practicalTask = taskFallback
    item.acceptanceCriteria = acceptanceFallback
    item.evidenceDeliverable = '项目链接、实现说明、测试结果和演示记录'
  })
}

async function build() { busy.value = true; try { await career.buildPlan(); normalizePlanLabels() } catch (error) { ElMessage.error(errorMessage(error)) } finally { busy.value = false } }
function openVerification(order: number, criteria: string) { verificationOrder.value = order; evidenceType.value = 'PROJECT_LINK'; evidenceText.value = `我已完成任务，并按以下标准整理了成果：${criteria}`; evidenceUrl.value = '' }
function togglePlanDetails(order: number) { expandedPlanOrders.value = expandedPlanOrders.value.includes(order) ? expandedPlanOrders.value.filter(item => item !== order) : [...expandedPlanOrders.value, order] }
async function submit() {
  if (verificationOrder.value == null || !evidenceText.value.trim()) return
  busy.value = true
  try {
    const result = await career.verify(verificationOrder.value, evidenceType.value, evidenceText.value, evidenceUrl.value)
    verificationOrder.value = null
    if (result.comparison) {
      ElMessage.success(`证据已验证，能力镜像更新，再诊断变化 ${result.comparison.improvement >= 0 ? '+' : ''}${result.comparison.improvement}`)
    } else if (result.submission.status === 'PENDING_VERIFICATION') {
      ElMessage.warning('证据已进入待验证状态；审核通过前不会提高能力等级')
    } else {
      ElMessage.error(`证据未通过：${result.submission.reviewReason || '请补充可访问且内容匹配的证据'}`)
    }
  } catch (error) { ElMessage.error(errorMessage(error)) } finally { busy.value = false }
}
function verificationLabel(order: number) {
  const status = career.verificationStatuses[order]?.status
  return ({ VERIFIED: '已验证', PENDING_VERIFICATION: '待验证', REJECTED: '未通过' } as Record<string, string>)[status ?? ''] ?? '待完成'
}
onMounted(async () => { await career.initialize(); if (career.match && !career.plan) await build(); else normalizePlanLabels() })
</script>

<template>
  <div class="v10-page plan-v10">
    <header class="v10-page-head"><div><span class="v10-kicker">行动计划</span><h1>把关键缺口，变成 4–6 周可验收任务</h1><p>每周只突出一个核心目标；学习不是终点，最终要提交作品、测试或证书作为能力证据。</p></div></header>
    <section v-if="!career.match" class="v10-empty-action">
      <div class="plan-empty-copy">
        <span>需要先完成职业诊断</span>
        <h2>从真实差距开始安排每一周</h2>
        <p>完成三步后，系统会把关键能力缺口整理为可提交、可验收的行动任务。</p>
        <div class="career-signal-path career-signal-path--horizontal" aria-label="职业决策三步路径">
          <div class="career-signal-track" aria-hidden="true"><i class="career-signal-orb" /></div>
          <ol>
            <li><b>01</b><div><strong>选择岗位</strong><small>明确目标</small></div></li>
            <li><b>02</b><div><strong>建立能力镜像</strong><small>确认事实</small></div></li>
            <li><b>03</b><div><strong>生成行动计划</strong><small>提交成果</small></div></li>
          </ol>
        </div>
      </div>
      <el-button type="primary" size="large" @click="router.push('/diagnosis')">先完成诊断</el-button>
    </section>
    <template v-else>
      <section class="plan-overview"><div><span>目标岗位</span><h2>{{ career.match.roleName }}</h2><p>准备度 {{ career.match.readiness }}% · 每周投入 {{ career.weeklyHours }} 小时</p></div><div v-if="career.plan"><strong>{{ career.plan.estimatedWeeks }}</strong><span>预计周数</span></div><div v-if="career.plan"><strong>{{ career.plan.items.length }}</strong><span>核心任务</span></div><el-button v-if="!career.plan" type="primary" size="large" :loading="busy" @click="build">生成行动计划</el-button></section>
      <section v-if="career.comparison" class="diagnosis-comparison-v10"><header><span>再次诊断完成</span><h2>能力证据已经写回个人镜像</h2></header><div><p><small>提交前</small><strong>{{ career.comparison.beforeReadiness }}%</strong></p><i>→</i><p><small>提交后</small><strong>{{ career.comparison.afterReadiness }}%</strong></p><em>变化 {{ career.comparison.improvement >= 0 ? '+' : '' }}{{ career.comparison.improvement }}</em></div><p>已补齐：{{ career.comparison.upgradedSkills.map(planSkillLabel).join('、') || '等待有效证据' }}</p><p>仍需优先解决：{{ remainingGaps.map(item => planSkillLabel(item.skill)).join('、') || '暂无关键缺口' }}</p></section>
      <template v-if="career.plan">
        <section class="plan-weeks"><article v-for="item in career.plan.items" :key="item.order" :class="{ verified: career.verifiedOrders.includes(item.order), pending: career.verificationStatuses[item.order]?.status === 'PENDING_VERIFICATION', rejected: career.verificationStatuses[item.order]?.status === 'REJECTED', expanded: expandedPlanOrders.includes(item.order) }"><aside><span>WEEK</span><strong>{{ item.weekStart ?? item.order }}<template v-if="item.weekEnd && item.weekEnd !== item.weekStart">–{{ item.weekEnd }}</template></strong></aside><div><header><div><span>{{ item.stage }}</span><h2>{{ item.skill }}</h2><p>{{ item.reason }}</p></div><b>{{ verificationLabel(item.order) }}</b></header><p v-if="career.verificationStatuses[item.order]" class="verification-reason">{{ career.verificationStatuses[item.order]?.reviewReason }}</p><div class="plan-card-actions"><el-button size="small" @click="togglePlanDetails(item.order)">{{ expandedPlanOrders.includes(item.order) ? '收起详细信息' : '查看详细信息' }}</el-button><el-button v-if="!career.verifiedOrders.includes(item.order)" size="small" type="primary" plain @click="openVerification(item.order, item.acceptanceCriteria)">{{ career.verificationStatuses[item.order]?.status === 'REJECTED' ? '修正并重新提交' : '提交能力证据' }}</el-button></div><dl v-if="expandedPlanOrders.includes(item.order)"><div><dt>学习主题</dt><dd>{{ item.resources.join('、') || item.skill }}</dd></div><div><dt>实践任务</dt><dd>{{ item.practicalTask }}</dd></div><div><dt>可提交产物</dt><dd>{{ item.evidenceDeliverable ?? '项目链接、实现说明与测试结果' }}</dd></div><div><dt>验收标准</dt><dd>{{ item.acceptanceCriteria }}</dd></div></dl></div></article></section>
      </template>
    </template>

    <el-dialog class="verification-dialog" :model-value="verificationOrder != null" title="提交能力证据" width="min(620px, 94vw)" @close="verificationOrder = null"><el-form label-position="top"><el-form-item label="证据类型"><el-radio-group v-model="evidenceType"><el-radio-button v-for="item in evidenceTypes" :key="item.value" :label="item.value">{{ item.label }}</el-radio-button></el-radio-group></el-form-item><el-form-item label="作品、代码或证书地址"><el-input v-model="evidenceUrl" placeholder="项目链接和证书必须提供可访问的 https:// 地址" /></el-form-item><el-form-item label="完成说明与验证结果"><el-input v-model="evidenceText" type="textarea" :rows="6" maxlength="5000" show-word-limit /></el-form-item></el-form><el-alert :title="frozenCache ? '提交后将保存在当前浏览器并进入待验证；完整环境中的专业角色审核通过前，能力等级和准备度都不会变化。' : '证据会先检查链接、内容相关性和测试指标；只有审核通过才会更新能力等级并再次诊断。'" type="info" :closable="false" show-icon /><template #footer><el-button @click="verificationOrder = null">取消</el-button><el-button type="primary" :loading="busy" @click="submit">{{ frozenCache ? '提交待验证证据' : '提交验证' }}</el-button></template></el-dialog>
  </div>
</template>
