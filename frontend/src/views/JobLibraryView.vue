<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import type { PublicOverview, PublicRoleOverview } from '../types/models'
import { useCareerStore } from '../stores/career'
import TechPageHeader from '../components/TechPageHeader.vue'
import CompactDisclosure from '../components/CompactDisclosure.vue'
import { Collection, DataAnalysis, Search, Suitcase } from '@element-plus/icons-vue'
import { shortRoleDescription } from '../utils/role-copy'

const router = useRouter()
const route = useRoute()
const career = useCareerStore()
const overview = ref<PublicOverview | null>(null)
const search = ref('')
const loading = ref(true)
const loadError = ref('')
const compareIds = ref<number[]>([])
const selectedFamily = ref('全部方向')
const selectedStatus = ref('ALL')
const visibleLimit = ref(6)
const families = computed(() => ['全部方向', ...new Set(overview.value?.roles.map(role => role.family) ?? [])])
const filteredRoles = computed(() => overview.value?.roles.filter(role => {
  const keywordMatches = `${role.name} ${role.definition} ${role.coreSkills.join(' ')}`.toLowerCase().includes(search.value.toLowerCase())
  const familyMatches = selectedFamily.value === '全部方向' || role.family === selectedFamily.value
  const statusMatches = selectedStatus.value === 'ALL'
    || selectedStatus.value === 'EMERGING' && role.emerging
    || role.status === selectedStatus.value
  return keywordMatches && familyMatches && statusMatches
}) ?? [])
const comparedRoles = computed(() => overview.value?.roles.filter(role => compareIds.value.includes(role.id)) ?? [])
const visibleRoles = computed(() => filteredRoles.value.slice(0, visibleLimit.value))

function statusLabel(role: PublicRoleOverview) {
  if (role.emerging) return '新兴岗位'
  return role.status === 'PUBLISHED' ? '已有真实画像' : role.status === 'BASELINE' ? '能力基线可分析' : '资料建设中'
}

function toggleCompare(role: PublicRoleOverview) {
  if (compareIds.value.includes(role.id)) compareIds.value = compareIds.value.filter(id => id !== role.id)
  else if (compareIds.value.length < 3) compareIds.value = [...compareIds.value, role.id]
}
async function choose(role: PublicRoleOverview) {
  if (role.status === 'BUILDING') return
  career.targetRoleId = role.id
  await router.push({ path: '/diagnosis', query: { role: role.id } })
}
async function load() {
  loading.value = true
  try {
    overview.value = await api.publicOverview()
    const requestedRole = overview.value.roles.find(role => role.id === Number(route.query.role))
    if (requestedRole) search.value = requestedRole.name
  } catch (error) { loadError.value = errorMessage(error) } finally { loading.value = false }
}
onMounted(load)
watch([search, selectedFamily, selectedStatus], () => { visibleLimit.value = 6 })
</script>

<template>
  <div v-loading="loading" class="v10-page tech-texture-page job-library-modern">
    <TechPageHeader eyebrow="职业方向" title="选择适合你的岗位入口" description="先筛选方向，再按需展开岗位详情。" :icon="Suitcase">
      <template #actions><el-input v-model="search" clearable :prefix-icon="Search" placeholder="搜索岗位或技能" /></template>
    </TechPageHeader>
    <el-alert v-if="loadError" title="岗位资料暂时加载不稳定，已展示本地示例内容。" type="info" :closable="false" show-icon><el-button link @click="load">重新加载</el-button></el-alert>
    <section v-if="overview" class="compact-data-ribbon"><span><el-icon><DataAnalysis /></el-icon>{{ overview.sampleCount }} 条岗位样本</span><span><el-icon><Collection /></el-icon>{{ overview.companyCount }} 家企业</span><span>{{ overview.roles.length }} 个岗位方向</span><small>选择岗位可查看职责、适合背景、核心能力与证据。</small></section>

    <section class="filter-dock">
      <div class="filter-chip-row"><button v-for="family in families" :key="family" :class="{ active: selectedFamily === family }" @click="selectedFamily = family">{{ family }}</button></div>
      <el-select v-model="selectedStatus" aria-label="岗位资料状态"><el-option label="全部状态" value="ALL" /><el-option label="新兴岗位" value="EMERGING" /><el-option label="已发布画像" value="PUBLISHED" /><el-option label="能力基线" value="BASELINE" /><el-option label="建设中" value="BUILDING" /></el-select>
    </section>

    <section class="job-compact-grid">
      <article v-for="role in visibleRoles" :id="`role-${role.code}`" :key="role.id" :class="['job-compact-card', role.status.toLowerCase(), { emerging: role.emerging }]">
        <header><span class="job-card-icon"><el-icon><Suitcase /></el-icon></span><div><small>{{ role.family }}</small><h2>{{ role.name }}</h2></div><span :class="['role-status', role.status.toLowerCase()]">{{ statusLabel(role) }}</span></header>
        <p>{{ shortRoleDescription(role) }}</p>
        <div v-if="role.status !== 'BUILDING'" class="v10-skill-list"><span v-for="skill in role.coreSkills.slice(0, 4)" :key="skill">{{ skill }}</span><em v-if="role.coreSkills.length > 4">+{{ role.coreSkills.length - 4 }}</em></div>
        <CompactDisclosure title="岗位详情" summary="适合背景、典型工作与数据范围" :icon="DataAnalysis">
          <dl><div><dt>适合背景</dt><dd>{{ role.suitableBackground }}</dd></div><div><dt>典型工作</dt><dd>{{ role.responsibilities.length ? role.responsibilities.join('、') : '资料完成后公布' }}</dd></div></dl>
          <small v-if="role.status === 'PUBLISHED'">{{ role.sampleCount }} 条样本 · {{ role.companyCount }} 家企业 · 更新于 {{ role.updatedAt?.slice(0, 10) }}</small>
          <small v-else>{{ role.status === 'BASELINE' ? '当前用于能力结构分析，不表示市场需求率。' : '资料达到发布门槛后开放分析。' }}</small>
        </CompactDisclosure>
        <footer><el-checkbox v-if="role.status !== 'BUILDING'" :model-value="compareIds.includes(role.id)" :disabled="!compareIds.includes(role.id) && compareIds.length >= 3" @change="toggleCompare(role)">比较</el-checkbox><div v-if="role.status !== 'BUILDING'"><RouterLink v-if="!role.emerging" :to="`/evolution?role=${role.id}`"><el-button>查看岗位演化</el-button></RouterLink><RouterLink :to="`/abilities?role=${role.id}`"><el-button>查看图谱</el-button></RouterLink><el-button type="primary" @click="choose(role)">开始诊断</el-button></div></footer>
      </article>
    </section>
    <el-empty v-if="!filteredRoles.length" description="没有匹配的岗位方向" />
    <button v-else-if="visibleLimit < filteredRoles.length" class="load-more-roles" @click="visibleLimit += 6">查看更多岗位 <span>{{ filteredRoles.length - visibleLimit }} 个</span></button>

    <section v-if="comparedRoles.length" class="role-comparison comparison-sticky">
      <header class="v10-section-head"><div><span class="v10-kicker">岗位比较 · 最多三个</span><h2>并排比较岗位要求</h2></div><el-button link @click="compareIds = []">清空比较</el-button></header>
      <div class="comparison-grid"><article v-for="role in comparedRoles" :key="role.id"><h3>{{ role.name }}</h3><span>核心技能</span><p>{{ role.coreSkills.join('、') }}</p><span>关键任务</span><p>{{ role.responsibilities.join('、') }}</p></article></div>
    </section>
  </div>
</template>
