<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Histogram, RefreshRight, Search } from '@element-plus/icons-vue'
import { api, errorMessage } from '../api'
import { useCareerStore } from '../stores/career'
import type { JobRole, RoleEvolutionChange, RoleEvolutionEvidence, RoleEvolutionRun } from '../types/models'
import TechPageHeader from '../components/TechPageHeader.vue'
import { isActionableRole, shortRoleDescription } from '../utils/role-copy'

const route = useRoute()
const router = useRouter()
const career = useCareerStore()
const loading = ref(true)
const pageError = ref('')
const roles = ref<JobRole[]>([])
const selectedRole = ref(0)
const run = ref<RoleEvolutionRun | null>(null)
const timelineTab = ref<'overview' | 'changes'>('overview')
const evidenceOpen = ref(false)
const activeEvidenceChange = ref<RoleEvolutionChange | null>(null)
// 公开页面保留岗位选择；变化明细和证据线索使用独立阅读面板。

const selectedRoleData = computed(() => roles.value.find(role => role.id === selectedRole.value) ?? null)
const roleOptions = computed(() => roles.value.filter(role => isActionableRole(role)))
const visibleSkills = computed(() => run.value?.changes.slice(0, 6) ?? [])
const visibleWindows = computed(() => run.value?.windows ?? [])
const hasEvolutionChanges = computed(() => (run.value?.changes.length ?? 0) > 0)
const hasStableConclusion = computed(() => run.value?.conclusionLevel === 'STABLE')
const evolutionStateCopy = computed(() => run.value?.status === 'INSUFFICIENT_DATA'
  ? { title: '样本暂不足以形成变化结论', detail: '已展示现有时间窗口和样本规模，待更多同岗位 JD 后继续观察。' }
  : hasStableConclusion.value
    ? { title: '岗位要求整体保持稳定', detail: '两个时间窗口内没有达到发布阈值的显著变化。' }
    : { title: '暂无可发布变化', detail: '当前演化运行没有可展示的变化项，请结合窗口样本继续观察。' })
const baselineWindow = computed(() => visibleWindows.value.find(window => window.type === 'BASELINE'))
const currentWindow = computed(() => visibleWindows.value.find(window => window.type === 'CURRENT'))
const evidenceItems = computed<RoleEvolutionEvidence[]>(() => activeEvidenceChange.value?.evidence ?? [])

function changeLabel(value: string) {
  return ({ NEW: '新增关注', INCREASED: '需求增强', DECREASED: '需求回落', REMOVAL_CANDIDATE: '持续减少', STABLE: '保持稳定' } as Record<string, string>)[value] ?? '变化观察'
}

function confidenceLabel(value: string) {
  return ({ FORMAL_CHANGE: '趋势明确', OBSERVATION_SIGNAL: '持续观察', STABLE: '保持稳定' } as Record<string, string>)[value] ?? '变化观察'
}

function roleEvolution(roleId: number) {
  return api.roleEvolution(roleId)
}

function loadSelectedRoleEvolution(selectedRole: number) {
  return roleEvolution(selectedRole)
}

function findFirstPublicEvolutionRole(items: JobRole[]) {
  return items.find(role => role.code === 'JAVA_ENGINEER' && isActionableRole(role))?.id
    ?? items.find(role => isActionableRole(role))?.id ?? 0
}

function selectRole(roleId: number) {
  selectedRole.value = roleId
  career.targetRoleId = roleId
  void loadRoleEvolution(roleId)
  void router.replace({ path: '/evolution', query: { ...route.query, role: selectedRole.value } })
}

async function loadRoleEvolution(roleId: number) {
  if (!roleId) return
  loading.value = true
  pageError.value = ''
  try {
    run.value = await roleEvolution(roleId)
  } catch (error) {
    pageError.value = errorMessage(error)
    run.value = null
  } finally {
    loading.value = false
  }
}

async function load() {
  loading.value = true
  pageError.value = ''
  try {
    roles.value = await api.roles()
    const requestedRoleId = [Number(route.query.role), career.targetRoleId, findFirstPublicEvolutionRole(roles.value)].find(id => roleOptions.value.some(role => role.id === id)) ?? 0
    selectedRole.value = requestedRoleId
    career.targetRoleId = requestedRoleId
    await loadRoleEvolution(selectedRole.value)
  } catch (error) {
    pageError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function goToDiagnosis() {
  if (!selectedRole.value) return
  void router.push({ path: '/diagnosis', query: { role: selectedRole.value } })
}

function openEvidence(change: RoleEvolutionChange) {
  activeEvidenceChange.value = change
  evidenceOpen.value = true
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="v10-page evolution-page">
    <TechPageHeader
      eyebrow="岗位演化"
      title="岗位演化"
      description="仅展示已有双时间窗口证据的岗位演化结果。"
      :icon="Histogram"
    >
      <template #actions>
        <label class="evolution-role-picker"><el-select v-model="selectedRole" aria-label="选择岗位" filterable placeholder="选择岗位" style="min-width: 280px" @change="selectRole">
          <el-option v-for="role in roleOptions" :key="role.id" :label="role.name" :value="role.id" />
        </el-select></label>
      </template>
    </TechPageHeader>

    <el-alert v-if="pageError" :title="pageError" type="info" :closable="false" show-icon />

    <section v-if="run" class="evolution-window-track" aria-label="基线到当前的演化对照">
      <article><span>基线</span><strong>{{ baselineWindow?.label || '暂无' }}</strong><small>{{ baselineWindow?.startDate }} ～ {{ baselineWindow?.endDate }}</small></article>
      <div aria-hidden="true">→</div>
      <article class="current"><span>当前</span><strong>{{ currentWindow?.label || '暂无' }}</strong><small>{{ currentWindow?.startDate }} ～ {{ currentWindow?.endDate }}</small></article>
    </section>

    <section class="evolution-hero">
      <div class="evolution-hero-copy">
        <span class="v10-kicker">目标岗位</span>
        <h2>{{ selectedRoleData?.name || '请选择一个岗位' }}</h2>
        <p>{{ selectedRoleData ? shortRoleDescription(selectedRoleData) : '这里会显示该岗位的演化轨迹、版本变化和证据线索。' }}</p>
      </div>
      <div class="evolution-hero-actions">
        <el-button type="primary" :icon="Search" @click="goToDiagnosis">继续看能力对齐</el-button>
        <el-button :icon="RefreshRight" @click="load">重新加载</el-button>
      </div>
    </section>

    <section v-if="run" class="evolution-summary-grid">
      <article>
        <span>基线窗口</span>
        <strong>{{ baselineWindow?.label || '暂无' }}</strong>
      </article>
      <article>
        <span>当前窗口</span>
        <strong>{{ currentWindow?.label || '暂无' }}</strong>
      </article>
      <article>
        <span>分析口径</span>
        <strong>Wilson 95% + Newcombe</strong>
      </article>
      <article>
        <span>变化节点</span>
        <strong>{{ run.changes.length }} 项</strong>
      </article>
    </section>

    <section v-if="run" class="evolution-layout">
      <aside class="evolution-side">
        <div class="evolution-window-list">
          <header>
            <span class="v10-kicker">演化窗口</span>
            <h3>版本切片</h3>
          </header>
          <article v-for="window in visibleWindows" :key="window.id">
            <strong>{{ window.label }}</strong>
            <p>{{ window.startDate }} ～ {{ window.endDate }}</p>
            <small>{{ window.documentCount }} 条文档 · {{ window.companyCount }} 家企业 · {{ window.sourceCount }} 个来源</small>
          </article>
        </div>
      </aside>

      <main class="evolution-main">
        <section v-if="!hasEvolutionChanges" class="evolution-state-panel">
          <span class="v10-kicker">结论状态</span>
          <h3>{{ evolutionStateCopy.title }}</h3>
          <p>{{ evolutionStateCopy.detail }}</p>
        </section>
        <el-tabs v-model="timelineTab">
          <el-tab-pane label="概览" name="overview">
            <section class="evolution-timeline">
              <header>
                <span class="v10-kicker">主要变化</span>
                <h3>岗位要点怎么变了</h3>
              </header>
              <article v-for="change in visibleSkills" :key="change.id" class="evolution-change-card">
                <div>
                  <span>{{ changeLabel(change.changeType) }}</span>
                  <h4>{{ change.skill }}</h4>
                </div>
                <strong>{{ Math.round(((change.absoluteChange ?? change.points.at(-1)?.demandRate ?? 0)) * 100) / 100 }}</strong>
                <p>{{ confidenceLabel(change.confidenceLevel) }}</p>
                <small>{{ run?.status === 'PUBLISHED' ? '结合两个时间窗口的岗位资料' : '基于当前岗位资料的观察信号' }}</small>
                <button type="button" class="evolution-inline-evidence" @click="openEvidence(change)">查看 {{ change.evidence.length }} 条原文证据</button>
              </article>
            </section>
          </el-tab-pane>

          <el-tab-pane v-if="hasEvolutionChanges" label="变化明细" name="changes">
            <section class="evolution-change-board">
              <article v-for="change in run.changes" :key="change.id" class="evolution-change-detail">
                <header><div><span class="change-type-label">{{ changeLabel(change.changeType) }}</span><h3>{{ change.skill }}</h3></div><strong>{{ `${change.absoluteChange >= 0 ? '+' : ''}${((change.absoluteChange ?? change.points.at(-1)?.demandRate ?? 0) * 100).toFixed(1)}%` }}</strong></header>
                <dl><div><dt>{{ run.status === 'PUBLISHED' ? '变化区间' : '当前需求率' }}</dt><dd>{{ run.status === 'PUBLISHED' ? `${change.differenceLowerBound.toFixed(3)} ～ ${change.differenceUpperBound.toFixed(3)}` : `${((change.points[0]?.demandRate ?? 0) * 100).toFixed(1)}%` }}</dd></div><div><dt>变化方向</dt><dd>{{ change.sensitivityDirection === 'UP' ? '增强' : change.sensitivityDirection === 'DOWN' ? '回落' : '稳定' }}</dd></div><div><dt>可信度</dt><dd>{{ confidenceLabel(change.confidenceLevel) }}</dd></div><div><dt>影响说明</dt><dd>{{ run.status === 'PUBLISHED' ? `${changeLabel(change.changeType)}，建议核对岗位原文。` : '当前岗位技能要求，尚未形成双窗口趋势。' }}</dd></div></dl>
                <footer><span>{{ change.evidence.length }} 条证据 · {{ new Set(change.evidence.map(item => item.company)).size }} 家企业</span><el-button class="evolution-evidence-button" type="primary" link @click="openEvidence(change)">查看原文证据</el-button></footer>
              </article>
            </section>
          </el-tab-pane>

        </el-tabs>
      </main>
    </section>

    <section v-else-if="!loading" class="v10-empty-action">
      <div>
        <span>暂无公开岗位演化数据</span>
        <h2>请选择其他岗位</h2>
        <p>该岗位目前只有岗位画像或单批次资料，暂无足够的正式 JD 时间序列，不生成趋势结论。</p>
      </div>
      <el-button @click="load">重新加载</el-button>
    </section>

    <el-drawer v-model="evidenceOpen" title="岗位演化证据" size="min(620px, 96vw)" class="evidence-drawer" append-to-body destroy-on-close>
      <div class="v10-evidence-scroll">
        <div class="evolution-evidence-board v10-evidence-drawer">
          <header>
            <span>{{ activeEvidenceChange ? changeLabel(activeEvidenceChange.changeType) : '证据线索' }}</span>
            <h2>{{ activeEvidenceChange?.skill || '岗位证据' }}</h2>
            <p>基线与当前窗口的原文证据会分组展示，方便判断变化是否可靠。</p>
          </header>
          <article v-for="item in evidenceItems" :key="item.id">
            <strong>{{ item.windowType === 'BASELINE' ? '基线' : '当前' }} · {{ item.company }}</strong>
            <blockquote>{{ item.quote }}</blockquote>
            <footer><span>{{ item.source }} · {{ item.publishedAt }}</span><a v-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noreferrer">查看原文</a></footer>
          </article>
          <el-empty v-if="!evidenceItems.length" description="当前没有可展示的原文证据" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>
