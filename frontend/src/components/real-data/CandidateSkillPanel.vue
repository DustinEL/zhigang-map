<script setup lang="ts">
import { Download, Search } from '@element-plus/icons-vue'
import type { CandidateSkillSummary } from '../../types/models'
import { reviewLabel, reviewTone } from '../../utils/review-display'

const props = defineProps<{
  candidates: CandidateSkillSummary[]
  selectedCount: number
  completedCount: number
  pendingCount: number
  reviewer: string
  note: string
  loading: boolean
  canReview: boolean
  readOnly: boolean
  authenticated: boolean
  username?: string
}>()
const emit = defineEmits<{
  selectionChange: [rows: CandidateSkillSummary[]]
  'update:reviewer': [value: string]
  'update:note': [value: string]
  confirmRecommendations: []
  exportAdvice: []
  bulkReview: [decision: 'APPROVED_STANDARD' | 'OBSERVING' | 'REJECTED_NOISE']
  openReview: [row: CandidateSkillSummary]
}>()

function selectable(row: CandidateSkillSummary) {
  return !props.readOnly && (!row.reviewDecision || ['PENDING_REVIEW', 'MIXED'].includes(row.reviewDecision))
}
</script>

<template>
  <section class="metric-grid">
    <article class="metric-tile"><span>候选技能</span><strong>{{ candidates.length }}</strong><small>来自岗位原文</small></article>
    <article class="metric-tile"><span>已完成</span><strong>{{ completedCount }}</strong><small>保留审核决定</small></article>
    <article class="metric-tile"><span>待处理</span><strong>{{ pendingCount }}</strong><small>阻断晋级发布</small></article>
    <article class="metric-tile"><span>覆盖企业</span><strong>{{ Math.max(0, ...candidates.map(item => item.companyCount)) }}</strong><small>单技能最大覆盖数</small></article>
  </section>
  <section class="panel">
    <header class="panel-head"><div><h2>候选技能审核</h2><p>{{ readOnly ? '当前可查看系统建议和原文证据；如需调整，请进入资料管理流程。' : '同名候选已聚合，历史决定会在后续导入中自动复用。' }}</p></div><span class="status-chip" :class="pendingCount ? 'warning' : 'passed'">{{ pendingCount ? `剩余 ${pendingCount} 项` : '全部完成' }}</span></header>
    <div class="bulk-review-bar">
      <span>已选 {{ selectedCount }} 种</span>
      <span v-if="canReview && authenticated" class="table-note">当前账号：{{ username }}</span>
      <el-input v-else-if="canReview" :model-value="reviewer" placeholder="审核人" @update:model-value="emit('update:reviewer', $event)" />
      <el-input v-if="canReview" :model-value="note" placeholder="判断依据（可选）" @update:model-value="emit('update:note', $event)" />
      <el-button v-if="canReview" type="primary" :disabled="!selectedCount" :loading="loading" @click="emit('confirmRecommendations')">按建议确认</el-button>
      <el-button :icon="Download" @click="emit('exportAdvice')">导出建议 CSV</el-button>
      <el-button v-if="canReview" :disabled="!selectedCount" :loading="loading" @click="emit('bulkReview', 'APPROVED_STANDARD')">统一批准</el-button>
      <el-button v-if="canReview" :disabled="!selectedCount" :loading="loading" @click="emit('bulkReview', 'OBSERVING')">批量观察</el-button>
      <el-button v-if="canReview" type="danger" plain :disabled="!selectedCount" :loading="loading" @click="emit('bulkReview', 'REJECTED_NOISE')">批量拒绝</el-button>
    </div>
    <div class="table-scroll"><el-table :data="candidates" row-key="skill" stripe @selection-change="emit('selectionChange', $event)"><el-table-column type="selection" width="46" :selectable="selectable" /><el-table-column prop="skill" label="候选技能" min-width="170" /><el-table-column prop="suggestedCategory" label="建议分类" min-width="150" /><el-table-column prop="factCount" label="事实数" width="90" /><el-table-column prop="companyCount" label="企业数" width="90" /><el-table-column label="要求类型" min-width="150"><template #default="scope"><el-tag v-for="item in scope.row.requirementTypes" :key="item" effect="plain" size="small">{{ item }}</el-tag></template></el-table-column><el-table-column label="系统建议" min-width="210"><template #default="scope"><span class="status-chip" :class="reviewTone(scope.row.recommendedDecision)">{{ reviewLabel(scope.row.recommendedDecision) }}</span><small v-if="scope.row.recommendedTarget" class="table-note">→ {{ scope.row.recommendedTarget }}</small><small class="table-note">{{ scope.row.recommendationReasons.join('；') }}</small></template></el-table-column><el-table-column label="审核结果" min-width="170"><template #default="scope"><span class="status-chip" :class="reviewTone(scope.row.reviewDecision)">{{ reviewLabel(scope.row.reviewDecision) }}</span><small v-if="scope.row.correctedValue" class="table-note">→ {{ scope.row.correctedValue }}</small></template></el-table-column><el-table-column label="决定来源" width="110"><template #default="scope">{{ scope.row.decisionSource === 'REUSED' ? '历史复用' : '本批新增' }}</template></el-table-column><el-table-column label="操作" fixed="right" width="92"><template #default="scope"><el-button v-if="canReview" :icon="Search" link type="primary" @click="emit('openReview', scope.row)">审核</el-button><span v-else>只读</span></template></el-table-column></el-table></div>
    <el-empty v-if="!candidates.length" description="导入结构化 JD 后显示候选技能" :image-size="72" />
  </section>
</template>
