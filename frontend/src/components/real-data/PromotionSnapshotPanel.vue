<script setup lang="ts">
import { Check, Close, DocumentAdd, Search, UploadFilled } from '@element-plus/icons-vue'
import type { PromotionBatch, RealEvidence, RealSkillMetric, RealSnapshot } from '../../types/models'
import { reviewLabel, reviewTone } from '../../utils/review-display'

defineProps<{
  mode: 'promotion' | 'snapshot'
  batch: PromotionBatch | null
  snapshot: RealSnapshot | null
  lookupId: string
  requestedBy: string
  reviewer: string
  note: string
  publisher: string
  authenticated: boolean
  username?: string
  loading: boolean
  canCreate: boolean
  canReview: boolean
  canPublish: boolean
  readOnly: boolean
}>()
const emit = defineEmits<{
  'update:lookupId': [value: string]
  'update:requestedBy': [value: string]
  'update:reviewer': [value: string]
  'update:note': [value: string]
  'update:publisher': [value: string]
  loadBatch: []
  createBatch: []
  reviewBatch: [decision: 'APPROVE' | 'REJECT']
  publishBatch: []
  showMetricEvidence: [metric: RealSkillMetric, roleName: string]
  showOpposingEvidence: [items: RealEvidence[], roleName: string]
}>()
</script>

<template>
  <template v-if="mode === 'promotion'">
    <el-alert v-if="readOnly" title="当前资料仅供查看；如需更新，请进入资料管理流程。" type="info" :closable="false" show-icon />
    <section v-if="!readOnly" class="panel batch-restore"><header class="panel-head"><div><h2>恢复待审核批次</h2><p>创建人退出后，另一位专业账号可继续独立审核。</p></div></header><div class="action-row compact"><el-input :model-value="lookupId" placeholder="批次编号" @update:model-value="emit('update:lookupId', $event)" @keyup.enter="emit('loadBatch')" /><el-button :icon="Search" :loading="loading" @click="emit('loadBatch')">加载批次</el-button></div></section>
    <section class="promotion-stage-grid">
      <article :class="{ current: !batch }"><span>步骤 1</span><h3>创建晋级批次</h3><p>{{ readOnly ? '资料管理员确认岗位资料和候选技能' : `当前账号：${username || requestedBy || '手动填写'}` }}</p><el-input v-if="!authenticated && !readOnly" :model-value="requestedBy" placeholder="批次创建人" @update:model-value="emit('update:requestedBy', $event)" /><el-button v-if="canCreate" type="primary" :icon="DocumentAdd" :loading="loading" @click="emit('createBatch')">创建全部可晋级批次</el-button><span v-else class="table-note">{{ readOnly ? '需在管理流程中完成' : '仅编辑者可以创建批次' }}</span></article>
      <article :class="{ current: batch?.status === 'DRAFT' }"><span>步骤 2</span><h3>独立审核</h3><p>{{ readOnly ? '另一位审核者核对候选资料并给出决定' : `使用另一账号登录；当前账号：${username || reviewer || '手动填写'}` }}</p><el-input v-if="!authenticated && !readOnly" :model-value="reviewer" placeholder="另一位审核人" @update:model-value="emit('update:reviewer', $event)" /><el-input v-if="canReview" :model-value="note" type="textarea" :rows="2" placeholder="审核说明" @update:model-value="emit('update:note', $event)" /><div v-if="canReview" class="action-row compact"><el-button type="primary" :icon="Check" :disabled="batch?.status !== 'DRAFT'" :loading="loading" @click="emit('reviewBatch', 'APPROVE')">通过</el-button><el-button :icon="Close" :disabled="batch?.status !== 'DRAFT'" :loading="loading" @click="emit('reviewBatch', 'REJECT')">拒绝</el-button></div><span v-else class="table-note">{{ readOnly ? '需在管理流程中完成' : '仅独立审核者可以审批次' }}</span></article>
      <article :class="{ current: batch?.status === 'APPROVED' }"><span>步骤 3</span><h3>安全发布</h3><p>{{ readOnly ? '校验通过后发布新版本，失败时保留当前资料' : `当前账号：${username || publisher || '手动填写'}` }}</p><el-input v-if="!authenticated && !readOnly" :model-value="publisher" placeholder="发布人" @update:model-value="emit('update:publisher', $event)" /><el-button v-if="canPublish" type="primary" :icon="UploadFilled" :disabled="batch?.status !== 'APPROVED'" :loading="loading" @click="emit('publishBatch')">发布岗位资料</el-button><span v-else class="table-note">{{ readOnly ? '需在管理流程中完成' : '仅编辑者可以发布资料' }}</span></article>
    </section>
    <section v-if="batch" class="panel"><header class="panel-head"><div><h2>{{ batch.batchCode }}</h2><p>资料版本 {{ batch.snapshotCode }} · 质量规则 {{ batch.policyVersion }}</p></div><span class="status-chip" :class="reviewTone(batch.status)">{{ reviewLabel(batch.status) }}</span></header><div class="import-result-strip batch-summary"><div><span>候选</span><strong>{{ batch.candidateCount }}</strong></div><div><span>已晋级</span><strong>{{ batch.promotedCount }}</strong></div><div><span>已拒绝</span><strong>{{ batch.rejectedCount }}</strong></div><div><span>创建人</span><strong>{{ batch.requestedBy }}</strong></div><div><span>审核人</span><strong>{{ batch.approvedBy ?? '待审核' }}</strong></div></div><div class="table-scroll"><el-table :data="batch.items" stripe><el-table-column prop="title" label="岗位" min-width="190" /><el-table-column prop="company" label="企业" min-width="150" /><el-table-column prop="decision" label="结果" width="130" /><el-table-column prop="targetDocumentId" label="正式资料编号" width="130" /><el-table-column prop="reason" label="说明" min-width="180" /></el-table></div></section>
    <el-empty v-else description="请先在动态资料页选择记录并创建批次" :image-size="84" />
  </template>
  <template v-else>
    <section class="metric-grid"><article class="metric-tile"><span>资料状态</span><strong class="metric-word">{{ snapshot?.dataMode === 'REAL' ? '已发布' : '当前版本' }}</strong><small>仅展示当前资料</small></article><article class="metric-tile"><span>岗位资料</span><strong>{{ snapshot?.documentCount ?? 0 }}</strong><small>只统计已发布岗位</small></article><article class="metric-tile"><span>独立企业</span><strong>{{ snapshot?.independentCompanyCount ?? 0 }}</strong><small>按企业去重</small></article><article class="metric-tile"><span>发布时间</span><strong class="metric-word">{{ snapshot?.publishedTimeCoverage ? '完整' : '不完整' }}</strong><small>不完整时不展示趋势</small></article></section>
    <el-alert v-if="snapshot?.documentCount" :title="`当前资料可信度：${snapshot.roles[0]?.confidenceLabel ?? '待评估'}；适用范围：${snapshot.usageScope}`" type="warning" :closable="false" show-icon />
    <el-empty v-if="!snapshot?.roles.length" description="暂无已发布的岗位资料" :image-size="96" />
    <section v-for="role in snapshot?.roles" :key="role.roleCode" class="panel snapshot-role"><header class="panel-head"><div><h2>{{ role.roleName }}</h2><p>{{ role.sampleSize }} 条 JD · {{ role.independentCompanies }} 家独立企业 · {{ role.confidenceLabel }}</p></div><el-button v-if="role.opposingEvidence.length" type="warning" plain @click="emit('showOpposingEvidence', role.opposingEvidence, role.roleName)">反对证据 {{ role.opposingEvidence.length }}</el-button></header><div class="snapshot-skill-grid"><div v-for="group in [{ title: '必须技能', items: role.requiredSkills }, { title: '加分技能', items: role.bonusSkills }, { title: '正文提及', items: role.mentionedSkills }]" :key="group.title"><h3>{{ group.title }}</h3><el-table :data="group.items" size="small"><el-table-column prop="skill" label="技能" min-width="120" /><el-table-column label="企业率" width="82"><template #default="scope">{{ Math.round(scope.row.rate * 100) }}%</template></el-table-column><el-table-column label="证据" width="72"><template #default="scope"><el-button link type="primary" @click="emit('showMetricEvidence', scope.row, role.roleName)">{{ scope.row.evidence.length }} 条</el-button></template></el-table-column></el-table></div></div></section>
  </template>
</template>
