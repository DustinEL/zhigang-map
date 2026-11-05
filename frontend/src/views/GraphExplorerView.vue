<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useCareerStore } from '../stores/career'
import type { GraphFilterParams, GraphView, PublicOverview, RoleSkillEvidence, SkillRequirement } from '../types/models'
import { evidenceDrawerState } from '../utils/public-ui'
import { shortRoleDescription } from '../utils/role-copy'

const GraphCanvas = defineAsyncComponent(() => import('../components/GraphCanvas.vue'))
const PublicPanoramaGraph = defineAsyncComponent(() => import('../components/PublicPanoramaGraph.vue'))
const route = useRoute()
const router = useRouter()
const career = useCareerStore()
const selectedRole = ref(0)
const activeView = ref<'panorama' | 'alignment' | 'evidence' | 'relations'>('panorama')
const graph = ref<GraphView | null>(null)
const publicGraph = ref<GraphView | null>(null)
const overview = ref<PublicOverview | null>(null)
const loading = ref(true)
const graphLoading = ref(false)
const relationsExpanded = ref(false)
const publicGraphLoading = ref(false)
const pageError = ref('')
const evidenceOpen = ref(false)
const evidenceLoading = ref(false)
const evidenceError = ref('')
const activeEvidenceSkill = ref('')
const skillEvidence = ref<RoleSkillEvidence | null>(null)
const roleDrawerOpen = ref(false)
const selectedRoleData = computed(() => career.roles.find(role => role.id === selectedRole.value))
const selectedPublicRole = computed(() => overview.value?.roles.find(role => role.id === selectedRole.value))
const baselineRole = computed(() => selectedPublicRole.value?.status === 'BASELINE')
const analyzableRole = computed(() => Boolean(selectedRoleData.value?.requiredSkills.length))
// 四维筛选后图中不含任何 JOB_ROLE 节点，视为空结果
const panoramaEmpty = computed(() =>
  !publicGraphLoading.value &&
  publicGraph.value !== null &&
  !publicGraph.value.nodes.some(node => node.type === 'JOB_ROLE')
)
const evidenceState = computed(() => evidenceDrawerState({ loading: evidenceLoading.value, error: evidenceError.value, hasEvidence: Boolean(skillEvidence.value) }))
const requirements = computed(() => {
  const role = selectedRoleData.value
  if (!role) return []
  return [...role.requiredSkills.slice(0, 6), ...role.bonusSkills.slice(0, 2)]
})
const abilityMap = computed(() => new Map(career.profile?.abilities.map(item => [item.skill.toLowerCase(), item]) ?? []))
const matchMap = computed(() => new Map(career.match?.roleId === selectedRole.value ? career.match.skillDetails.map(item => [item.skill.toLowerCase(), item]) : []))

function requiredLevel(item: SkillRequirement) { return item.requirementType === 'BONUS' ? 1 : 2 }
function personalLevel(item: SkillRequirement) { return matchMap.value.get(item.skill.toLowerCase())?.personalEvidenceLevel ?? matchMap.value.get(item.skill.toLowerCase())?.evidenceLevel ?? abilityMap.value.get(item.skill.toLowerCase())?.level ?? 0 }
function alignmentState(item: SkillRequirement) {
  const explicit = matchMap.value.get(item.skill.toLowerCase())?.alignmentState
  if (explicit) return explicit
  if (item.requirementType === 'BONUS') return 'BONUS'
  const level = personalLevel(item)
  return level >= requiredLevel(item) ? 'SATISFIED' : level > 0 ? 'EVIDENCE_INSUFFICIENT' : 'MISSING'
}
function stateLabel(state: string) { return ({ SATISFIED: '已满足', EVIDENCE_INSUFFICIENT: '证据不足', MISSING: '尚未具备', BONUS: '岗位加分项' } as Record<string, string>)[state] ?? state }
function requirementTypeLabel(value: string) { return ({ REQUIRED: '必备能力', BONUS: '加分项', MENTIONED: '岗位提及能力' } as Record<string, string>)[value] ?? '岗位能力' }
function levelLabel(level: number) { return level ? `L${level}` : '暂无' }
function personalEvidence(item: SkillRequirement) { return matchMap.value.get(item.skill.toLowerCase())?.resumeEvidence || abilityMap.value.get(item.skill.toLowerCase())?.quote || '当前能力档案中没有可用证据' }
function nextAction(item: SkillRequirement) { return matchMap.value.get(item.skill.toLowerCase())?.nextAction || matchMap.value.get(item.skill.toLowerCase())?.remedy || `完成一个使用 ${item.skill} 的可运行项目，并保留代码与测试结果` }

async function openSkillEvidence(skill: string) {
  evidenceOpen.value = true
  activeEvidenceSkill.value = skill
  evidenceLoading.value = true
  evidenceError.value = ''
  skillEvidence.value = null
  try { skillEvidence.value = await api.roleSkillEvidence(selectedRole.value, skill) }
  catch (error) { evidenceError.value = errorMessage(error) }
  finally { evidenceLoading.value = false }
}
async function loadGraph() {
  if (graph.value || !selectedRole.value) return
  graphLoading.value = true
  try { graph.value = await api.graph(selectedRole.value, 'CORE_RELATIONS') }
  catch (error) { pageError.value = errorMessage(error) }
  finally { graphLoading.value = false }
}
async function toggleRelations() {
  relationsExpanded.value = !relationsExpanded.value
  graphLoading.value = true
  try { graph.value = await api.graph(selectedRole.value, relationsExpanded.value ? 'FULL_RELATIONS' : 'CORE_RELATIONS') }
  catch (error) { pageError.value = errorMessage(error) }
  finally { graphLoading.value = false }
}
async function changeRole() {
  career.targetRoleId = selectedRole.value
  graph.value = null
  await router.replace({ path: '/abilities', query: { role: selectedRole.value } })
  if (activeView.value === 'relations') await loadGraph()
}
async function selectPanoramaRole(roleId: number) {
  selectedRole.value = roleId
  const role = career.roles.find(item => item.id === roleId)
  if (role?.requiredSkills.length) career.targetRoleId = roleId
  graph.value = null
  roleDrawerOpen.value = true
  await router.replace({ path: '/abilities', query: { role: roleId } })
}

function viewCompleteProfile() {
  roleDrawerOpen.value = false
  activeView.value = 'alignment'
}

async function filterPublicGraph(filters: GraphFilterParams) {
  publicGraphLoading.value = true
  try {
    publicGraph.value = await api.publicGraphOverview(filters)
    const visibleRoleIds = publicGraph.value.nodes.filter(node => node.type === 'JOB_ROLE')
      .map(node => Number(node.id.replace('role-', ''))).filter(Number.isFinite)
    if (visibleRoleIds.length && !visibleRoleIds.includes(selectedRole.value)) await selectPanoramaRole(visibleRoleIds[0])
  } catch (error) { pageError.value = errorMessage(error) }
  finally { publicGraphLoading.value = false }
}

function statusLabel(status?: string) {
  return status === 'PUBLISHED' ? '真实证据画像' : status === 'BASELINE' ? '能力基线' : '资料建设中'
}
watch(activeView, value => { if (value === 'relations') void loadGraph() })
onMounted(async () => {
  try {
    const [, publicData, graphData] = await Promise.all([career.initialize(Number(route.query.role) || undefined), api.publicOverview(), api.publicGraphOverview()])
    overview.value = publicData
    publicGraph.value = graphData
    const requestedRole = publicData.roles.find(role => role.id === Number(route.query.role))
    selectedRole.value = requestedRole?.id ?? career.targetRoleId
  } catch (error) { pageError.value = errorMessage(error) }
  finally { loading.value = false }
})
</script>

<template>
  <div v-loading="loading" class="v10-page abilities-page">
    <header class="v10-page-head"><div><span class="v10-kicker">岗位图谱</span><h1>看见岗位、技能与职业方向的关系</h1><p>先在全景图选择岗位，再查看画像证据和你的能力差距。</p></div><el-select v-model="selectedRole" aria-label="选择岗位" @change="changeRole"><el-option v-for="role in career.roles.filter(item => item.requiredSkills.length)" :key="role.id" :label="role.name" :value="role.id" /></el-select></header>
    <el-alert v-if="pageError" title="岗位图谱暂时加载不稳定，已展示本地示例内容。" type="info" :closable="false" show-icon />
    <el-alert v-if="baselineRole" title="当前为能力基线，可用于了解岗位方向和进行能力自测。" type="info" :closable="false" show-icon />

    <el-tabs v-model="activeView" class="ability-tabs">
      <el-tab-pane label="全景岗位图谱" name="panorama">
        <div v-if="publicGraph && overview" v-loading="publicGraphLoading" class="panorama-layout">
          <PublicPanoramaGraph :graph="publicGraph" :roles="overview.roles" :selected-role-id="selectedRole" @role-click="selectPanoramaRole" @filter-change="filterPublicGraph" />
          <p v-if="panoramaEmpty" class="empty-note">当前筛选条件下没有匹配的岗位，请调整筛选项再试。</p>
          <el-drawer v-model="roleDrawerOpen" title="岗位详情" size="min(700px, 92vw)" class="role-detail-drawer" append-to-body>
            <aside v-if="selectedPublicRole" class="panorama-role-detail">
              <div class="panorama-detail-scroll">
                <header class="panorama-detail-intro">
                  <div class="panorama-detail-kicker"><span :class="['role-status', selectedPublicRole.status.toLowerCase()]">{{ statusLabel(selectedPublicRole.status) }}</span><small>{{ selectedPublicRole.family }}</small></div>
                  <h2>{{ selectedPublicRole.name }}</h2>
                  <p>{{ shortRoleDescription(selectedPublicRole) }}</p>
                </header>
                <section class="panorama-detail-evidence" aria-labelledby="role-evidence-title">
                  <div class="panorama-section-heading"><span>证据概览</span><strong id="role-evidence-title">公开岗位数据</strong></div>
                  <div class="panorama-evidence-metrics">
                    <div><strong>{{ selectedPublicRole.status === 'PUBLISHED' ? selectedPublicRole.sampleCount : '—' }}</strong><span>{{ selectedPublicRole.status === 'PUBLISHED' ? '条真实样本' : '暂无样本' }}</span></div>
                    <div><strong>{{ selectedPublicRole.status === 'PUBLISHED' ? selectedPublicRole.companyCount : '—' }}</strong><span>{{ selectedPublicRole.status === 'PUBLISHED' ? '家企业' : '数据状态' }}</span></div>
                    <div><strong>{{ selectedPublicRole.coreSkills.length }}</strong><span>项核心能力</span></div>
                  </div>
                  <p v-if="selectedPublicRole.status === 'PUBLISHED'" class="panorama-evidence-note">基于公开岗位样本整理，帮助你理解岗位真实要求。</p>
                  <p v-else-if="selectedPublicRole.status === 'BASELINE'" class="panorama-evidence-note baseline">当前为能力基线，可用于了解岗位方向和进行能力自测。</p>
                  <p v-else class="panorama-evidence-note building">资料建设中，暂不开放能力诊断。</p>
                </section>
                <section v-if="selectedPublicRole.coreSkills.length" class="panorama-detail-skills" aria-labelledby="role-skills-title">
                  <div class="panorama-section-heading"><span>能力结构</span><strong id="role-skills-title">核心能力</strong></div>
                  <div class="panorama-skill-list"><button v-for="skill in selectedPublicRole.coreSkills" :key="skill" :disabled="!analyzableRole" @click="openSkillEvidence(skill)">{{ skill }}</button></div>
                </section>
                <section v-if="selectedPublicRole.responsibilities.length" class="panorama-detail-tasks" aria-labelledby="role-tasks-title">
                  <div class="panorama-section-heading"><span>工作内容</span><strong id="role-tasks-title">典型任务</strong></div>
                  <ol><li v-for="(item, index) in selectedPublicRole.responsibilities" :key="item"><span>{{ String(index + 1).padStart(2, '0') }}</span><p>{{ item }}</p></li></ol>
                </section>
              </div>
              <footer class="panorama-detail-actions-bar">
                <RouterLink v-if="analyzableRole && !selectedPublicRole.emerging" v-slot="{ navigate }" :to="`/evolution?role=${selectedRole}`" custom><el-button @click="navigate">查看岗位演化</el-button></RouterLink>
                <el-button v-if="analyzableRole" @click="viewCompleteProfile">查看完整画像</el-button>
                <RouterLink v-if="analyzableRole" v-slot="{ navigate }" :to="`/diagnosis?role=${selectedRole}`" custom><el-button type="primary" @click="navigate">开始能力分析</el-button></RouterLink>
                <RouterLink v-else v-slot="{ navigate }" :to="`/jobs?role=${selectedRole}`" custom><el-button @click="navigate">查看方向说明</el-button></RouterLink>
              </footer>
            </aside>
          </el-drawer>
        </div>
      </el-tab-pane>

      <el-tab-pane label="岗位画像与对齐" name="alignment" :disabled="!analyzableRole">
        <section v-if="!career.profile" class="profile-callout"><div><span>还没有个人能力镜像</span><h2>先查看岗位要求，建立档案后即可逐项对齐</h2><p>当前展示岗位证据等级；完成简历文字识别或手动建档后，这里会出现你的能力与证据。</p></div><RouterLink v-slot="{ navigate }" :to="`/diagnosis?role=${selectedRole}`" custom><el-button type="primary" @click="navigate">建立我的能力镜像</el-button></RouterLink></section>
        <section class="alignment-list">
          <header><span>岗位要求</span><i /><span>{{ career.profile ? '我的能力镜像' : '岗位证据等级' }}</span></header>
          <article v-for="item in requirements" :key="item.skill" :class="`state-${alignmentState(item).toLowerCase()}`">
            <div class="role-track"><small>{{ item.requirementType === 'BONUS' ? '加分项' : '必备能力' }}</small><h3>{{ item.skill }}</h3><p>{{ baselineRole ? `${levelLabel(requiredLevel(item))} · 能力基线` : `${levelLabel(requiredLevel(item))} · ${Math.round(item.demandRate * 100)}% 独立 JD 需求率` }}</p><button @click="openSkillEvidence(item.skill)">{{ baselineRole ? '查看证据状态' : '查看企业原文' }}</button></div>
            <div class="alignment-connector"><i /><span>{{ stateLabel(alignmentState(item)) }}</span></div>
            <div class="personal-track"><small>{{ career.profile ? levelLabel(personalLevel(item)) : `${item.evidenceCount} 条岗位依据` }}</small><h3>{{ career.profile ? (personalLevel(item) ? item.skill : '暂未具备') : item.category }}</h3><p>{{ career.profile ? personalEvidence(item) : `用于${selectedRoleData?.responsibilities[0] ?? '岗位核心工作'}` }}</p><em v-if="career.profile && alignmentState(item) !== 'SATISFIED' && alignmentState(item) !== 'BONUS'">下一步：{{ nextAction(item) }}</em></div>
          </article>
        </section>
      </el-tab-pane>

      <el-tab-pane label="岗位证据" name="evidence" :disabled="!analyzableRole">
        <section class="role-evidence-list"><article v-for="item in requirements" :key="item.skill"><header><div><span>{{ item.requirementType === 'BONUS' ? '岗位加分项' : '岗位必备能力' }}</span><h2>{{ item.skill }}</h2></div><strong v-if="!baselineRole">{{ Math.round(item.demandRate * 100) }}%<small>独立 JD 需求率</small></strong><strong v-else>基线<small>非市场统计</small></strong></header><el-button v-if="!baselineRole" @click="openSkillEvidence(item.skill)">查看证据线索</el-button><span v-else class="no-evidence-state">暂无企业 JD 证据</span></article></section>
      </el-tab-pane>

      <el-tab-pane label="关系视图" name="relations" :disabled="!analyzableRole">
        <section v-loading="graphLoading" class="panel relation-graph-panel"><header><div><h2>岗位、核心技能与职责的关系</h2><p>默认聚焦当前岗位的核心关系，需要时可以查看完整职责网络。</p></div><el-button class="relation-expand-toggle" type="primary" plain @click="toggleRelations">{{ relationsExpanded ? '收起关系' : '展开全部关系' }}</el-button></header><GraphCanvas v-if="graph" :graph="graph" @node-click="node => node.type === 'SKILL' && openSkillEvidence(node.label)" /><span class="visually-hidden">岗位职责节点</span></section>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="evidenceOpen" title="企业 JD 原文证据" size="min(620px, 96vw)" class="evidence-drawer" append-to-body destroy-on-close>
      <div class="v10-evidence-scroll">
      <div v-loading="evidenceLoading" class="v10-evidence-drawer">
        <el-alert v-if="evidenceState === 'error'" :title="evidenceError" type="error" :closable="false" show-icon><el-button link type="danger" @click="openSkillEvidence(activeEvidenceSkill)">重新加载</el-button></el-alert>
        <template v-else-if="evidenceState === 'ready' && skillEvidence"><header><span>{{ requirementTypeLabel(skillEvidence.requirementType) }}</span><h2>{{ skillEvidence.skill }}</h2><p>{{ skillEvidence.dataScope }} · {{ skillEvidence.documentCount }} 条独立 JD · {{ skillEvidence.companyCount }} 家企业</p><small>当前岗位资料</small></header><article v-for="item in skillEvidence.evidence" :key="`${item.documentId}-${item.startOffset}`"><strong>{{ item.company }} · {{ item.title }}</strong><blockquote>{{ item.quote }}</blockquote><footer><span>原文位置 {{ item.startOffset }}–{{ item.endOffset }}</span><a v-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noreferrer">查看来源</a></footer></article><el-empty v-if="!skillEvidence.evidence.length" description="证据不足" /></template>
        <el-empty v-else-if="evidenceState === 'empty'" :description="baselineRole ? '当前为能力基线，暂无企业 JD 原文证据' : '当前没有可展示的岗位原文证据'" />
      </div>
      </div>
    </el-drawer>
  </div>
</template>
