<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { Search, View } from '@element-plus/icons-vue'
import type { DynamicRecordSummary, ReviewQueueSummary } from '../../types/models'
import { reviewLabel, reviewTone } from '../../utils/review-display'

const props = defineProps<{
  records: DynamicRecordSummary[]
  total: number
  page: number
  size: number
  keyword: string
  status: string
  risk: string
  route: string
  onlyExceptions: boolean
  selectedIds: Set<number>
  selectedCount: number
  queueSummary: ReviewQueueSummary | null
  reviewer: string
  reason: string
  reviewLoading: boolean
  canReview: boolean
  authenticated: boolean
}>()
const emit = defineEmits<{
  'update:keyword': [value: string]
  'update:status': [value: string]
  'update:risk': [value: string]
  'update:route': [value: string]
  'update:onlyExceptions': [value: boolean]
  'update:reviewer': [value: string]
  'update:reason': [value: string]
  applyFilters: []
  selectionChange: [rows: DynamicRecordSummary[]]
  selectAll: []
  clearSelection: []
  bulkReview: [decision: 'APPROVE' | 'QUARANTINE']
  openDetail: [row: DynamicRecordSummary]
  pageChange: [page: number]
  sizeChange: [size: number]
}>()

const table = ref<{ clearSelection: () => void; toggleRowSelection: (row: DynamicRecordSummary, selected: boolean) => void }>()
let restoring = false

function selectable(row: DynamicRecordSummary) {
  return ['SAMPLE_REVIEW', 'EXCEPTION_REVIEW'].includes(row.reviewRoute)
}

function selectionChanged(rows: DynamicRecordSummary[]) {
  if (!restoring) emit('selectionChange', rows)
}

async function restoreSelection() {
  await nextTick()
  restoring = true
  table.value?.clearSelection()
  props.records.forEach(row => {
    if (props.selectedIds.has(row.id)) table.value?.toggleRowSelection(row, true)
  })
  restoring = false
}

watch(() => props.records, restoreSelection, { deep: true })
watch(() => props.selectedIds, restoreSelection)
defineExpose({ restoreSelection })
</script>

<template>
  <section class="metric-grid">
    <article class="metric-tile"><span>动态记录</span><strong>{{ queueSummary?.totalRecords ?? total }}</strong><small>原文与事实分层保存</small></article>
    <article class="metric-tile"><span>系统自动通过</span><strong>{{ queueSummary?.autoApprovedCount ?? 0 }}</strong><small>低风险且未被抽检</small></article>
    <article class="metric-tile"><span>抽检 / 异常</span><strong>{{ (queueSummary?.sampledCount ?? 0) + (queueSummary?.exceptionCount ?? 0) }}</strong><small>需要人工处理</small></article>
    <article class="metric-tile"><span>预计剩余操作</span><strong>{{ queueSummary?.estimatedRemainingActions ?? 0 }}</strong><small>含候选技能审核</small></article>
  </section>
  <section class="panel">
    <header class="panel-head"><div><h2>动态资料池</h2><p>默认显示全部资料；低风险记录自动通过，抽检和异常进入人工队列。</p></div>
      <div class="action-row compact record-filters">
        <el-input :model-value="keyword" clearable placeholder="搜索岗位、企业或城市" :prefix-icon="Search" @update:model-value="emit('update:keyword', $event)" @keyup.enter="emit('applyFilters')" @clear="emit('applyFilters')" />
        <el-select :model-value="status" clearable placeholder="全部状态" style="width: 150px" @update:model-value="emit('update:status', $event)" @change="emit('applyFilters')"><el-option label="质量通过" value="ELIGIBLE" /><el-option label="隔离复核" value="QUARANTINED" /><el-option label="已拒绝" value="REJECTED" /><el-option label="已晋级" value="PROMOTED" /></el-select>
        <el-select :model-value="risk" clearable placeholder="全部风险" style="width: 130px" @update:model-value="emit('update:risk', $event)" @change="emit('applyFilters')"><el-option label="低风险" value="LOW" /><el-option label="中风险" value="MEDIUM" /><el-option label="高风险" value="HIGH" /></el-select>
        <el-select :model-value="route" clearable placeholder="全部路线" style="width: 150px" @update:model-value="emit('update:route', $event)" @change="emit('applyFilters')"><el-option label="系统通过" value="AUTO_APPROVED" /><el-option label="抽检" value="SAMPLE_REVIEW" /><el-option label="异常" value="EXCEPTION_REVIEW" /><el-option label="等待技能" value="WAITING_SKILL" /><el-option label="人工通过" value="HUMAN_APPROVED" /><el-option label="已阻断" value="BLOCKED" /></el-select>
        <el-checkbox :model-value="onlyExceptions" @update:model-value="emit('update:onlyExceptions', $event)" @change="emit('applyFilters')">仅看异常</el-checkbox>
      </div>
    </header>
    <div class="bulk-review-bar"><span>跨页已选 {{ selectedCount }} 条</span><el-input v-if="canReview && !authenticated" :model-value="reviewer" placeholder="审核人" @update:model-value="emit('update:reviewer', $event)" /><el-input v-if="canReview" :model-value="reason" placeholder="批量审核说明" @update:model-value="emit('update:reason', $event)" /><el-button :disabled="!total" @click="emit('selectAll')">选择全部结果</el-button><el-button :disabled="!selectedCount" @click="emit('clearSelection')">清空选择</el-button><el-button v-if="canReview" type="primary" :disabled="!selectedCount" :loading="reviewLoading" @click="emit('bulkReview', 'APPROVE')">批量通过</el-button><el-button v-if="canReview" type="warning" plain :disabled="!selectedCount" :loading="reviewLoading" @click="emit('bulkReview', 'QUARANTINE')">批量隔离</el-button></div>
    <div class="table-scroll"><el-table ref="table" :data="records" row-key="id" stripe @selection-change="selectionChanged"><el-table-column type="selection" width="46" :selectable="selectable" /><el-table-column prop="title" label="岗位" min-width="190" /><el-table-column prop="company" label="企业" min-width="150" /><el-table-column prop="city" label="城市" width="100" /><el-table-column label="质量" width="90"><template #default="scope"><strong>{{ Math.round(scope.row.qualityScore * 100) }}</strong></template></el-table-column><el-table-column label="技能" width="110"><template #default="scope">{{ scope.row.skillCount }} / 候选 {{ scope.row.candidateSkillCount }}</template></el-table-column><el-table-column label="风险" width="90"><template #default="scope"><span class="status-chip" :class="reviewTone(scope.row.riskLevel)">{{ reviewLabel(scope.row.riskLevel) }}</span></template></el-table-column><el-table-column label="审核路线" min-width="170"><template #default="scope"><span class="status-chip" :class="reviewTone(scope.row.reviewRoute)">{{ reviewLabel(scope.row.reviewRoute) }}</span><small v-if="scope.row.reviewReasons?.length" class="table-note">{{ scope.row.reviewReasons.join('；') }}</small></template></el-table-column><el-table-column label="操作" fixed="right" width="92"><template #default="scope"><el-button :icon="View" link type="primary" @click="emit('openDetail', scope.row)">核对</el-button></template></el-table-column></el-table></div>
    <el-empty v-if="!records.length" description="尚无符合筛选条件的真实 JD" :image-size="72" />
    <el-pagination v-if="total" class="pipeline-pagination" background layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :current-page="page" :page-sizes="[10, 20, 50, 100]" @current-change="emit('pageChange', $event)" @size-change="emit('sizeChange', $event)" />
  </section>
</template>
