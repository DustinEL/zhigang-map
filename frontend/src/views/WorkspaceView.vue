<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, Calendar, Collection, DataAnalysis, Share, TrendCharts } from '@element-plus/icons-vue'
import { useCareerStore } from '../stores/career'
import { workspaceSnapshot } from '../utils/public-ui'

const career = useCareerStore()
const snapshot = computed(() => workspaceSnapshot(career))
const diagnosed = computed(() => snapshot.value.diagnosed)
const targetRole = computed(() => snapshot.value.targetRole)
const readiness = computed(() => snapshot.value.readiness)
const planProgress = computed(() => snapshot.value.planCount ? Math.round(snapshot.value.verifiedCount / snapshot.value.planCount * 100) : 0)
const tasks = computed(() => career.plan?.items.slice(0, 3).map(item => ({
  day: `第 ${item.weekStart ?? item.order}–${item.weekEnd ?? item.order} 周`,
  title: item.skill,
  meta: item.evidenceDeliverable || item.acceptanceCriteria,
  tone: career.verifiedOrders.includes(item.order) ? 'success' : 'primary',
  progress: career.verifiedOrders.includes(item.order) ? 100 : 0
})) ?? [])

const shortcuts = [
  { to: '/diagnosis', title: '职业诊断', value: computed(() => readiness.value === null ? '—' : `${readiness.value}%`), icon: DataAnalysis, action: '开始' },
  { to: '/plan', title: '行动计划', value: computed(() => snapshot.value.planCount ? `${snapshot.value.planCount} 项` : '—'), icon: TrendCharts, action: '查看' },
  { to: '/jobs', title: '岗位方向', value: computed(() => diagnosed.value ? targetRole.value : '待选择'), icon: Collection, action: '探索' },
  { to: '/abilities', title: '岗位图谱', value: computed(() => snapshot.value.abilityCount ? `${snapshot.value.abilityCount} 项` : '—'), icon: Share, action: '打开' }
]
</script>

<template>
  <div class="workspace-page">
    <section :class="['workspace-hero', { 'workspace-hero--first-run': !diagnosed }]">
      <div class="workspace-hero-copy">
        <span>MY CAREER WORKSPACE</span>
        <h1>我的求职工作台</h1>
        <p>把岗位探索、能力诊断与行动计划收进同一个连续工作流，优先处理真正影响求职结果的任务。</p>
        <div class="workspace-hero-actions">
          <RouterLink to="/diagnosis" class="workspace-primary-action">{{ diagnosed ? '查看职业诊断' : '开始职业诊断' }} <el-icon><ArrowRight /></el-icon></RouterLink>
          <RouterLink to="/jobs" class="workspace-secondary-action">浏览岗位方向</RouterLink>
        </div>
      </div>
      <div v-if="readiness !== null" class="workspace-score">
        <small>当前求职准备度</small>
        <strong>{{ readiness }}<em>%</em></strong>
        <span>依据当前目标岗位与能力证据计算</span>
      </div>
      <div v-else class="career-signal-path" aria-label="职业决策三步路径">
        <div class="career-signal-track" aria-hidden="true"><i class="career-signal-orb" /></div>
        <ol>
          <li><b>01</b><div><strong>选择岗位</strong><small>明确目标方向</small></div></li>
          <li><b>02</b><div><strong>建立能力镜像</strong><small>确认真实证据</small></div></li>
          <li><b>03</b><div><strong>生成行动计划</strong><small>形成可验收任务</small></div></li>
        </ol>
      </div>
    </section>

    <section class="workspace-summary" aria-label="求职进度摘要">
      <article class="summary-role"><span>目标岗位</span><strong>{{ targetRole }}</strong><p>{{ diagnosed ? '当前诊断岗位' : '先浏览岗位方向，再选择目标岗位' }}</p></article>
      <article><div class="mini-ring" :style="`--value:${readiness ?? 0}`"><b>{{ career.profile?.abilities.length ?? 0 }}</b></div><div><span>能力证据</span><small>{{ diagnosed ? '已纳入当前诊断' : '完成诊断后统计' }}</small></div></article>
      <article><div class="mini-ring gold" :style="`--value:${planProgress}`"><b>{{ snapshot.planCount || '—' }}</b></div><div><span>行动任务</span><small>{{ career.plan ? '按计划推进' : '完成诊断后生成' }}</small></div></article>
      <article><div class="mini-ring signal" :style="`--value:${career.verifiedOrders.length ? 100 : 0}`"><b>{{ career.verifiedOrders.length }}</b></div><div><span>已提交证据</span><small>{{ career.verifiedOrders.length ? '可用于再次诊断' : '暂无提交' }}</small></div></article>
    </section>

    <section class="workspace-grid">
      <div class="workspace-panel workspace-focus">
        <header><div><span>当前重点</span><h2>下一步任务</h2></div><el-icon><Calendar /></el-icon></header>
        <div v-if="tasks.length" class="workspace-task-list">
          <article v-for="task in tasks" :key="task.title">
            <i :class="task.tone">{{ task.day }}</i>
            <div><strong>{{ task.title }}</strong><div class="task-progress"><span :style="{ width: `${task.progress}%` }" /></div><small>{{ task.meta }}</small></div>
            <el-icon><ArrowRight /></el-icon>
          </article>
        </div>
        <el-empty v-else description="完成诊断后，这里会生成与你的目标岗位对应的行动任务" />
      </div>

      <div v-if="diagnosed" class="workspace-panel workspace-insight">
        <span>行动提示</span>
        <h2>{{ career.match?.skillDetails.find(item => item.evidenceLevel < item.requiredLevel)?.skill ? '优先补齐关键岗位能力' : '继续完善岗位证据' }}</h2>
        <div class="insight-chart"><div><i v-for="height in [35, 50, 65, 80]" :key="height" :style="{ height: `${height}%` }" /></div><strong>{{ readiness }}%</strong></div>
        <p>{{ career.match?.skillDetails.find(item => item.evidenceLevel < item.requiredLevel)?.nextAction ?? '把现有能力用于可运行项目，并保留代码、说明和测试结果。' }}</p>
        <RouterLink to="/diagnosis">查看诊断依据 <el-icon><ArrowRight /></el-icon></RouterLink>
      </div>
      <div v-else class="workspace-panel workspace-insight workspace-insight-empty"><span>下一步</span><h2>先确定岗位，再生成行动建议</h2><p>证据不足</p><RouterLink to="/jobs">浏览岗位方向 <el-icon><ArrowRight /></el-icon></RouterLink></div>
    </section>

    <section class="workspace-shortcuts">
      <header><span>核心功能</span><h2>从这里继续</h2></header>
      <div>
        <RouterLink v-for="item in shortcuts" :key="item.to" :to="item.to">
          <el-icon><component :is="item.icon" /></el-icon>
          <strong class="shortcut-value">{{ item.value }}</strong><h3>{{ item.title }}</h3>
          <span>{{ item.action }} <el-icon><ArrowRight /></el-icon></span>
        </RouterLink>
      </div>
    </section>
  </div>
</template>
