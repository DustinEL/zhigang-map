<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, DataAnalysis, Files, TrendCharts } from '@element-plus/icons-vue'
import { api, errorMessage } from '../api'
import type { PublicOverview, PublicRoleOverview } from '../types/models'
import { fillRoleSlots, publicSkillRanking } from '../utils/public-ui'
import { shortRoleDescription } from '../utils/role-copy'

const loading = ref(true)
const loadError = ref('')
const overview = ref<PublicOverview | null>(null)
const liveSampleCount = ref(0)

const publishedRoles = computed(() => overview.value?.roles.filter(role => role.status === 'PUBLISHED').slice(0, 4) ?? [])
const roleSlots = computed(() => fillRoleSlots(publishedRoles.value, 4))
const roleFamilies = computed(() => {
  const groups = new Map<string, PublicRoleOverview[]>()
  overview.value?.roles.forEach(role => groups.set(role.family, [...(groups.get(role.family) ?? []), role]))
  return [...groups.entries()].map(([family, roles]) => ({ family, roles }))
})
const topSkills = computed(() => publicSkillRanking(overview.value?.topSkills ?? []).slice(0, 6))
const metrics = computed(() => [
  { label: '岗位样本', value: overview.value ? liveSampleCount.value : '—', suffix: overview.value ? '条' : '', icon: Files, tone: 'blue' },
  { label: '覆盖企业', value: overview.value?.companyCount ?? '—', suffix: overview.value ? '家' : '', icon: DataAnalysis, tone: 'cyan' },
  { label: '岗位方向', value: overview.value?.roles.length ?? '—', suffix: overview.value ? '类' : '', icon: TrendCharts, tone: 'violet' },
  { label: '高频能力', value: topSkills.value.length || '—', suffix: topSkills.value.length ? '项' : '', icon: DataAnalysis, tone: 'violet' }
])

function roleTarget(role: PublicRoleOverview) {
  return role.status === 'BUILDING' ? { path: '/jobs', query: { role: role.id } } : { path: '/abilities', query: { role: role.id } }
}

function statusLabel(role: PublicRoleOverview) {
  return role.status === 'PUBLISHED' ? '已发布画像' : role.status === 'BASELINE' ? '能力基线' : '建设中'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    overview.value = await api.publicOverview()
    liveSampleCount.value = overview.value.sampleCount
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="v10-page home-v10 home-command-center">
    <el-alert v-if="loadError" title="岗位资料暂时加载不稳定，已展示本地示例内容。" type="info" :closable="false" show-icon>
      <el-button link @click="load">重新加载</el-button>
    </el-alert>

    <section class="command-hero">
      <div class="command-hero-copy">
        <span class="v10-kicker">职业决策工作台</span>
        <h1 aria-label="找到你的职业坐标">把下一步，<br><em>做成可验证的选择</em></h1>
        <p>从真实岗位证据出发，快速定位适合你的方向、能力缺口与下一项值得完成的行动。</p>
        <div class="v10-actions">
          <RouterLink v-slot="{ navigate }" to="/diagnosis" custom><el-button type="primary" size="large" @click="navigate">开始职业诊断 <el-icon><ArrowRight /></el-icon></el-button></RouterLink>
          <RouterLink v-slot="{ navigate }" to="/jobs" custom><el-button size="large" @click="navigate">浏览岗位方向</el-button></RouterLink>
        </div>
      </div>
      <div class="command-hero-visual" aria-label="岗位能力关系图示">
        <div class="hero-visual-glow"></div>
        <span class="hero-visual-label">CAREER<br>ATLAS</span>
        <div class="hero-node node-a">岗位</div><div class="hero-node node-b">能力</div><div class="hero-node node-c">行动</div>
        <div class="hero-orbit orbit-a"></div><div class="hero-orbit orbit-b"></div>
      </div>
    </section>

    <section class="metric-strip" aria-label="平台数据概览">
      <article v-for="metric in metrics" :key="metric.label" :class="`metric-card ${metric.tone}`">
        <div class="metric-icon"><el-icon><component :is="metric.icon" /></el-icon></div>
        <div><span>{{ metric.label }}</span><strong>{{ metric.value }}<small>{{ metric.suffix }}</small></strong></div>
        <i class="metric-sparkline" aria-hidden="true"></i>
      </article>
    </section>

    <section class="command-grid v10-section">
      <article class="command-panel role-panel">
        <header class="panel-head"><div><span class="v10-kicker">01 / 方向入口</span><h2>先找到值得投入的方向</h2></div><RouterLink to="/jobs">全部岗位 <el-icon><ArrowRight /></el-icon></RouterLink></header>
        <div class="role-quick-list hero-role-catalog">
          <div v-for="group in roleFamilies" :key="group.family" class="role-family-row">
            <span>{{ group.family }}</span>
            <div><RouterLink v-for="role in group.roles" :key="role.code" :to="roleTarget(role)" :class="['role-pill', 'hero-role-button', role.status.toLowerCase()]">{{ role.name }}<small>{{ statusLabel(role) }}</small></RouterLink></div>
          </div>
        </div>
        <details class="detail-drawer">
          <summary>查看方向状态说明 <span>+</span></summary>
          <div class="status-explainer"><span class="published">已发布画像：有真实岗位证据</span><span class="baseline">能力基线：可用于初步对齐</span><span class="building">建设中：先看目录与适合背景</span></div>
        </details>
      </article>

      <article class="command-panel skill-panel">
        <header class="panel-head"><div><span class="v10-kicker">02 / 高频能力</span><h2>当前样本反复出现什么</h2></div><RouterLink to="/abilities">看能力图谱 <el-icon><ArrowRight /></el-icon></RouterLink></header>
        <div class="skill-bars">
          <div v-for="skill in topSkills" :key="skill.name" class="skill-bar-row"><span>{{ skill.name }}</span><div><i :style="{ width: `${Math.max(28, 100 - (skill.rank - 1) * 12)}%` }"></i></div><b>第 {{ skill.rank }} 位</b></div>
        </div>
        <p v-if="!topSkills.length" class="empty-note">当前岗位样本尚未形成可排序的技能统计</p>
        <details class="detail-drawer"><summary>查看岗位依据 <span>+</span></summary><p>排序来自当前岗位库中技能的真实证据条数，证据越多的能力越靠前。</p></details>
      </article>
    </section>

    <section class="v10-section featured-section">
      <header class="v10-section-head"><div><span class="v10-kicker">03 / 岗位画像</span><h2>把岗位要求翻译成行动</h2></div><RouterLink to="/jobs">查看全部方向 <el-icon><ArrowRight /></el-icon></RouterLink></header>
      <div class="public-role-grid">
        <template v-for="(role, index) in roleSlots" :key="role?.id ?? `slot-${index}`">
        <article v-if="role" class="public-role-card modern-role-card">
          <header><span>岗位画像</span><small>{{ role.sampleCount ?? 0 }} 条样本 · {{ role.companyCount ?? 0 }} 家企业</small></header>
          <h3>{{ role.name }}</h3><p>{{ shortRoleDescription(role) }}</p>
          <div class="v10-skill-list"><span v-for="skill in role.coreSkills.slice(0, 4)" :key="skill">{{ skill }}</span></div>
          <footer><RouterLink :to="`/abilities?role=${role.id}`">查看证据</RouterLink><RouterLink v-slot="{ navigate }" :to="`/diagnosis?role=${role.id}`" custom><el-button type="primary" @click="navigate">开始对齐</el-button></RouterLink></footer>
        </article>
        <article v-else class="public-role-card modern-role-card role-slot-empty"><span class="v10-kicker">更多岗位方向</span><h3>正在补充岗位画像</h3><p>该方向需要更多真实 JD 样本，达到发布条件后会出现在这里。</p><RouterLink to="/jobs">浏览全部岗位</RouterLink></article>
        </template>
      </div>
    </section>

    <section class="command-loop v10-section">
      <header><span class="v10-kicker">04 / 求职行动</span><h2>从目标岗位走到可投递成果</h2><p>围绕同一个目标岗位，完成要求理解、能力对齐、实践产出和证据提交。</p></header>
      <div class="loop-steps"><article v-for="(step, index) in ['选择目标岗位', '对齐岗位要求', '完成岗位实践', '提交能力证据']" :key="step"><b>0{{ index + 1 }}</b><strong>{{ step }}</strong><span>{{ ['从岗位样本中确定方向', '确认必备能力与当前差距', '按计划产出可运行项目', '用代码、说明与测试证明能力'][index] }}</span></article></div>
    </section>
  </div>
</template>
