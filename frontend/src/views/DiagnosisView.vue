<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Delete, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { ElMessageBox } from 'element-plus/es/components/message-box/index'
import { errorMessage } from '../api'
import { sampleResumeText, useCareerStore } from '../stores/career'
import { isActionableRole, planSkillLabel, planTaskCopy, shortRoleDescription } from '../utils/role-copy'

const route = useRoute()
const router = useRouter()
const career = useCareerStore()
const step = ref(1)
const inputMode = ref<'file' | 'text' | 'manual'>('text')
const resumeText = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const selectedFileName = ref('')
const busy = ref(false)
const editing = ref(false)
const resultDetailsOpen = ref(false)
const form = ref({ name: '', education: '本科', major: '软件工程', skills: [] as string[], projectsText: '', certificatesText: '' })
const skillOptions = ['Java', 'Spring Boot', 'MySQL', 'Redis', 'Linux', 'Docker', 'Kubernetes', 'Python', 'RAG', 'Embedding', '向量数据库', '大语言模型', 'Prompt Engineering', 'AI Agent', '工具调用', '模型评测']
const matchableRoles = computed(() => career.roles.filter(role => isActionableRole(role)))
const factTypeLabels: Record<string, string> = { EDUCATION: '学历', MAJOR: '专业', SKILL: '技能', PROJECT: '项目经历', CERTIFICATE: '证书或课程', NAME: '姓名' }
function factTypeLabel(value: string) { return factTypeLabels[value] ?? '个人经历' }
const targetRole = computed(() => career.roles.find(role => role.id === career.targetRoleId))
const confirmed = computed(() => career.profile?.status === 'CONFIRMED')
const strengths = computed(() => career.match?.skillDetails.filter(item => (item.personalEvidenceLevel ?? item.evidenceLevel) >= (item.roleEvidenceLevel ?? item.requiredLevel)).sort((a, b) => b.score - a.score).slice(0, 3) ?? [])
const gaps = computed(() => career.match?.skillDetails.filter(item => item.alignmentState !== 'BONUS' && (item.personalEvidenceLevel ?? item.evidenceLevel) < (item.roleEvidenceLevel ?? item.requiredLevel)).sort((a, b) => (b.roleEvidenceLevel ?? b.requiredLevel) - (b.personalEvidenceLevel ?? b.evidenceLevel)).slice(0, 3) ?? [])
const riskGates = computed(() => career.match?.hardGates.filter(item => item.status !== 'PASSED') ?? [])
const currentStep = computed(() => step.value)

function fillForm() {
  if (!career.profile) return
  form.value = { name: career.profile.name, education: career.profile.education, major: career.profile.major, skills: career.profile.abilities.map(item => item.skill), projectsText: career.profile.projects.join('\n'), certificatesText: career.profile.certificates.join('\n') }
}
function lines(value: string) { return value.split('\n').map(item => item.trim()).filter(Boolean) }
function state(detail: NonNullable<typeof career.match>['skillDetails'][number]) {
  if (detail.alignmentState) return detail.alignmentState
  if (detail.matchType === 'BONUS') return 'BONUS'
  return detail.evidenceLevel >= detail.requiredLevel ? 'SATISFIED' : detail.evidenceLevel > 0 ? 'EVIDENCE_INSUFFICIENT' : 'MISSING'
}
function stateLabel(value: string) { return ({ SATISFIED: '已满足', EVIDENCE_INSUFFICIENT: '证据不足', MISSING: '尚未具备', BONUS: '岗位加分项' } as Record<string, string>)[value] ?? value }
function matchTypeLabel(value: string) { return ({ EXACT: '精确匹配', EXACT_OR_ALIAS: '精确或别名匹配', ALIAS: '别名归一', SEMANTIC_CONFIRMED: '语义相似·已确认', UNMATCHED: '未匹配', BONUS: '加分项' } as Record<string, string>)[value] ?? '已完成能力比对' }
function redactedFieldLabel(value: string) { return factTypeLabel(value) }
function chooseTarget() { step.value = career.profile ? 3 : 2 }
function chooseFile() { fileInput.value?.click() }

async function goToStep(target: number) {
  if (target >= currentStep.value) return
  if (currentStep.value === 4) {
    try {
      await ElMessageBox.confirm('返回修改后，当前诊断结果和行动计划需要重新生成。个人能力档案会保留。', '返回修改', {
        type: 'warning', confirmButtonText: '返回修改', cancelButtonText: '留在结果页'
      })
    } catch { return }
    career.clearResults()
  }
  if (target === 2) resumeText.value = career.resumeDraftText
  if (target === 3) fillForm()
  editing.value = false
  step.value = target
}

async function parseText() {
  if (!resumeText.value.trim()) return ElMessage.warning('请先粘贴简历文字')
  busy.value = true
  try { await career.parseText(resumeText.value); fillForm(); step.value = 3; ElMessage.success('能力事实已识别，请逐项核对') } catch (error) { ElMessage.error(errorMessage(error)) } finally { busy.value = false }
}
async function uploadFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  busy.value = true
  try { await career.parseFile(file); selectedFileName.value = file.name; fillForm(); step.value = 3; ElMessage.success('文件解析完成，请核对能力事实') } catch (error) { ElMessage.error(errorMessage(error)) } finally { busy.value = false; input.value = '' }
}
async function createManual() {
  if (!form.value.skills.length) return ElMessage.warning('至少选择一项已有技能')
  busy.value = true
  try { await career.createManual({ ...form.value, projects: lines(form.value.projectsText), certificates: lines(form.value.certificatesText) }); fillForm(); step.value = 3 } catch (error) { ElMessage.error(errorMessage(error)) } finally { busy.value = false }
}
async function saveAndConfirm() {
  busy.value = true
  try { await career.updateProfile({ ...form.value, projects: lines(form.value.projectsText), certificates: lines(form.value.certificatesText), confirmed: true }); editing.value = false; ElMessage.success('能力事实已确认') } catch (error) { ElMessage.error(errorMessage(error)) } finally { busy.value = false }
}
async function runMatch() {
  busy.value = true
  try { await career.runMatch(); step.value = 4; ElMessage.success('双图对齐完成') } catch (error) { ElMessage.error(errorMessage(error)) } finally { busy.value = false }
}
async function deleteProfile() {
  try {
    await ElMessageBox.confirm('将立即删除当前能力档案、诊断和计划，且无法恢复。', '删除匿名数据', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' })
    await career.deleteProfile(); resumeText.value = ''; step.value = 2; ElMessage.success('匿名数据已删除')
  } catch (error) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

onMounted(async () => {
  const roleId = Number(route.query.role) || undefined
  await career.initialize(roleId)
  if (career.dataMode === 'FROZEN_CACHE') inputMode.value = 'text'
  resumeText.value = career.resumeDraftText
  if (career.match) step.value = 4
  else if (career.profile) { step.value = 3; fillForm() }
  if (route.query.mode === 'demo' && !career.profile) { step.value = 2; inputMode.value = 'text'; resumeText.value = sampleResumeText; await parseText() }
})
</script>

<template>
  <div class="v10-page diagnosis-v10">
    <header class="v10-page-head"><div><span class="v10-kicker">职业诊断</span><h1>我距离目标岗位，还差哪几步</h1><p>岗位要求和个人证据逐项对齐，准备度只衡量能力覆盖，不表示录用概率。</p></div></header>
    <section class="diagnosis-steps"><button v-for="(label, index) in ['选择目标岗位', '建立能力镜像', '确认能力事实', '查看诊断结果']" :key="label" :class="{ active: currentStep === index + 1, done: currentStep > index + 1, backable: currentStep > index + 1 }" :disabled="currentStep <= index + 1" @click="goToStep(index + 1)"><i>{{ index + 1 }}</i><span>{{ label }}</span><small v-if="currentStep > index + 1">返回修改</small></button></section>

    <section v-if="currentStep === 1" class="diagnosis-stage target-stage">
      <header><span>第 1 步</span><h2>选择你的目标岗位</h2><p>先选择想进入的方向，后续诊断只比较该岗位的真实能力要求。</p></header>
      <div class="target-role-grid"><button v-for="role in matchableRoles" :key="role.id" :class="{ selected: career.targetRoleId === role.id }" @click="career.targetRoleId = role.id"><span>{{ role.family }}</span><strong>{{ role.name }}</strong><small>{{ shortRoleDescription(role) }}</small></button></div>
      <el-button type="primary" size="large" @click="chooseTarget">{{ career.profile ? '下一步：确认现有能力' : '下一步：建立能力镜像' }}</el-button>
    </section>

    <section v-else-if="currentStep === 2" class="diagnosis-stage profile-stage">
      <header><span>第 2 步 · {{ targetRole?.name }}</span><h2>{{ career.profile ? '重新填写简历或能力信息' : '建立你的个人能力镜像' }}</h2><p>{{ career.dataMode === 'FROZEN_CACHE' ? '静态版不上传文件，请粘贴简历文字或手动选择能力；简历全文仅保存在当前浏览器会话。' : '可以粘贴文字、上传文本型 PDF/DOCX 或手动填写。重新识别会替换旧事实并清除旧诊断。' }}</p></header>
      <el-tabs v-model="inputMode">
        <el-tab-pane label="粘贴简历文字" name="text"><textarea v-model="resumeText" class="editor" placeholder="粘贴简历文字，建议按“学历：”“专业：”“项目：”分行填写" /><div class="action-row"><el-button type="primary" :loading="busy" @click="parseText">{{ career.profile ? '重新识别并替换' : '识别能力事实' }}</el-button><el-button @click="resumeText = sampleResumeText">填入示例</el-button><el-button @click="goToStep(1)">返回选择岗位</el-button></div></el-tab-pane>
        <el-tab-pane v-if="career.dataMode !== 'FROZEN_CACHE'" label="上传简历" name="file"><div class="upload-zone" @click="chooseFile"><el-icon><UploadFilled /></el-icon><strong>{{ selectedFileName || '选择 PDF 或 DOCX' }}</strong><el-button type="primary">选择文件</el-button></div><input ref="fileInput" class="visually-hidden" type="file" accept=".pdf,.docx" @change="uploadFile"></el-tab-pane>
        <el-tab-pane label="手动选择能力" name="manual"><el-form label-position="top" class="manual-form"><el-form-item label="称呼"><el-input v-model="form.name" placeholder="匿名同学" /></el-form-item><el-form-item label="学历"><el-select v-model="form.education"><el-option v-for="item in ['专科','本科','硕士','博士']" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="专业"><el-input v-model="form.major" /></el-form-item><el-form-item label="已有技能"><el-select v-model="form.skills" multiple filterable allow-create><el-option v-for="item in skillOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="项目经历（每行一项）"><el-input v-model="form.projectsText" type="textarea" :rows="3" /></el-form-item><el-form-item label="证书或课程（每行一项）"><el-input v-model="form.certificatesText" type="textarea" :rows="2" /></el-form-item></el-form><el-button type="primary" :loading="busy" @click="createManual">建立能力镜像</el-button></el-tab-pane>
      </el-tabs>
    </section>

    <section v-else-if="currentStep === 3 && career.profile" class="diagnosis-stage facts-stage">
      <header><span>第 3 步 · 识别结果必须由你确认</span><h2>核对个人能力事实</h2><p>技能名称出现不等于真正掌握，请补充项目和证书，确保判断依据准确。</p></header>
      <div class="fact-summary"><div><span>目标岗位</span><strong>{{ targetRole?.name }}</strong></div><div><span>个人档案</span><strong>{{ career.profile.education }} · {{ career.profile.major }}</strong></div><div><span>已识别能力</span><strong>{{ career.profile.abilities.length }} 项</strong></div></div>
      <el-alert v-if="career.profile.redactedFields?.length" :title="`上传内容已先脱敏：${career.profile.redactedFields.map(redactedFieldLabel).join('、')}`" type="success" :closable="false" show-icon />
      <template v-if="editing"><el-form label-position="top" class="manual-form facts-form"><el-form-item label="称呼"><el-input v-model="form.name" /></el-form-item><el-form-item label="学历"><el-input v-model="form.education" /></el-form-item><el-form-item label="专业"><el-input v-model="form.major" /></el-form-item><el-form-item label="技能"><el-select v-model="form.skills" multiple filterable allow-create><el-option v-for="item in skillOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="项目"><el-input v-model="form.projectsText" type="textarea" :rows="3" /></el-form-item><el-form-item label="证书"><el-input v-model="form.certificatesText" type="textarea" :rows="2" /></el-form-item></el-form></template>
      <div v-else class="ability-facts"><article v-for="ability in career.profile.abilities" :key="ability.skill"><strong>{{ ability.skill }}</strong><span>{{ ability.verificationStatus === 'VERIFIED' ? '已验证' : '待验证' }}</span></article><el-empty v-if="!career.profile.abilities.length" description="暂未识别能力，请修正信息" /></div>
      <div class="fact-actions"><el-button @click="goToStep(1)">返回选择岗位</el-button><el-button @click="goToStep(2)">重新填写简历</el-button><el-button v-if="!editing" @click="editing = true; fillForm()">修正识别事实</el-button><el-button v-if="editing" @click="editing = false">取消修正</el-button><el-button type="primary" :loading="busy" @click="saveAndConfirm">{{ confirmed ? '保存事实' : '确认能力事实' }}</el-button><el-button v-if="confirmed" type="primary" :loading="busy" @click="runMatch">运行双图对齐</el-button><el-button type="danger" plain :icon="Delete" @click="deleteProfile">删除匿名数据</el-button></div>
    </section>

    <template v-else-if="career.match">
      <div class="diagnosis-result-actions"><span>诊断依据需要调整？</span><el-button @click="goToStep(1)">更换目标岗位</el-button><el-button type="primary" plain @click="goToStep(2)">修改简历与能力</el-button></div>
      <section class="diagnosis-result-hero"><div class="readiness-v10"><span>岗位能力准备度</span><strong>{{ career.match.readiness }}%</strong><p>{{ career.match.applicationState }}</p><small>用于衡量岗位能力覆盖，不表示录用概率</small></div><div><span>三项已具备优势</span><strong v-for="item in strengths" :key="item.skill">{{ planSkillLabel(item.skill) }}</strong><p v-if="!strengths.length">暂未形成证据充分的优势</p></div><div><span>三项关键缺口</span><strong v-for="item in gaps" :key="item.skill">{{ planSkillLabel(item.skill) }}</strong><p v-if="!gaps.length">核心能力已经覆盖</p></div><div><span>硬门槛风险</span><strong>{{ riskGates.length }} 项</strong><p>{{ riskGates[0]?.explanation ?? '当前没有明显阻断' }}</p></div></section>
      <el-alert v-if="career.match.scoreLimitReason || riskGates.length" class="diagnosis-gate-notice" :title="career.match.scoreLimitReason || '存在未通过的硬门槛，当前准备度已受到限制。'" type="warning" :closable="false" show-icon />
      <section class="now-task-v10"><div><span>现在就做</span><h2>{{ gaps[0] ? planSkillLabel(gaps[0].skill) : '完善项目证据' }}</h2><p>{{ gaps[0] ? planTaskCopy(gaps[0].skill, career.match.roleName).task : '把现有能力用于可运行项目，并记录测试结果。' }}</p></div><el-button type="primary" size="large" @click="router.push('/plan')">生成 4–6 周行动计划</el-button></section>
      <section class="diagnosis-detail-toggle"><div><strong>想知道每项能力为什么这样判断？</strong><span>展开后查看岗位要求、个人证据、迁移路径和评分口径。</span></div><el-button size="small" @click="resultDetailsOpen = !resultDetailsOpen">{{ resultDetailsOpen ? '收起详细信息' : '查看详细信息' }}</el-button></section>
      <template v-if="resultDetailsOpen">
        <section class="diagnosis-alignment"><header class="v10-section-head"><div><span class="v10-kicker">双图对齐清单</span><h2>岗位要求与个人证据</h2></div></header><article v-for="item in career.match.skillDetails" :key="item.skill"><div><small>岗位要求</small><strong>{{ planSkillLabel(item.skill) }} · L{{ item.roleEvidenceLevel ?? item.requiredLevel }}</strong><small>{{ matchTypeLabel(item.matchType) }}<template v-if="item.matchType === 'SEMANTIC_CONFIRMED'"> · 相似度 {{ Math.round((item.semanticSimilarity ?? 0) * 100) }}%</template></small></div><span :class="`alignment-state ${state(item).toLowerCase()}`">{{ stateLabel(state(item)) }}</span><div><small>个人证据 L{{ item.personalEvidenceLevel ?? item.evidenceLevel }}</small><strong>{{ item.resumeEvidence }}</strong><p>{{ item.gapReason ?? item.gap }}</p><em v-if="state(item) !== 'SATISFIED'">下一步：{{ planTaskCopy(item.skill, career.match.roleName).task }}</em></div></article></section>
      </template>
    </template>
  </div>
</template>
