<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { api, errorMessage, frozenCache, runtime } from '../api'
import type { VerificationReviewDecision, VerificationStatus, VerificationSubmission } from '../types/models'
import ProfessionalLoginView from './ProfessionalLoginView.vue'

const status = ref<VerificationStatus | ''>('PENDING_VERIFICATION')
const page = ref(0)
const size = ref(20)
const total = ref(0)
const submissions = ref<VerificationSubmission[]>([])
const selected = ref<VerificationSubmission | null>(null)
const loading = ref(false)
const pageError = ref('')
const reviewDecision = ref<VerificationReviewDecision | null>(null)
const reviewReason = ref('')
const reviewing = ref(false)

const authenticated = computed(() => runtime.professional.authenticated)
const statusOptions: Array<{ value: VerificationStatus | ''; label: string }> = [
  { value: 'PENDING_VERIFICATION', label: '待审核' },
  { value: 'VERIFIED', label: '已通过' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: '', label: '全部状态' }
]

function statusLabel(value: VerificationStatus) {
  return ({ PENDING_VERIFICATION: '待审核', VERIFIED: '已通过', REJECTED: '已拒绝' } as const)[value]
}

function evidenceTypeLabel(value: VerificationSubmission['evidenceType']) {
  return ({ PROJECT_LINK: '项目链接', CODE_DESCRIPTION: '代码说明', TEST_RESULT: '测试结果', DOCUMENT: '项目文档', CERTIFICATE: '证书' } as const)[value]
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

async function loadSubmissions() {
  if (frozenCache || !authenticated.value) return
  loading.value = true
  pageError.value = ''
  try {
    const result = await api.verificationSubmissions({ status: status.value || undefined, page: page.value, size: size.value })
    submissions.value = result.items
    page.value = result.page
    size.value = result.size
    total.value = result.total
    if (selected.value) selected.value = result.items.find(item => item.id === selected.value?.id) ?? selected.value
  } catch (error) {
    pageError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function changeStatus() {
  page.value = 0
  void loadSubmissions()
}

function changePage(value: number) {
  page.value = value - 1
  void loadSubmissions()
}

function openReview(decision: VerificationReviewDecision) {
  reviewDecision.value = decision
  reviewReason.value = ''
}

async function submitReview() {
  if (!selected.value || !reviewDecision.value) return
  if (!reviewReason.value.trim()) {
    ElMessage.warning('请填写审核理由')
    return
  }
  reviewing.value = true
  try {
    selected.value = await api.reviewVerificationSubmission(selected.value.id, reviewDecision.value, reviewReason.value.trim())
    ElMessage.success(reviewDecision.value === 'VERIFIED' ? '证据已审核通过' : '证据已拒绝')
    reviewDecision.value = null
    await loadSubmissions()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    reviewing.value = false
  }
}

async function loadSession() {
  if (frozenCache) return
  loading.value = true
  try {
    runtime.professional = await api.professionalSession()
    if (runtime.professional.authenticated) await loadSubmissions()
  } catch {
    runtime.professional = { authenticated: false }
  } finally {
    loading.value = false
  }
}

onMounted(loadSession)
</script>

<template>
  <div class="verification-review-page">
    <header class="review-page-heading">
      <span>专业证据复核</span>
      <h1>能力证据审核</h1>
      <p>逐条核对作品、实现说明和测试结果。只有审核通过的证据才能更新个人能力并触发再次诊断。</p>
    </header>

    <el-alert v-if="frozenCache" title="公开证据快照不提供审核操作；请在完整环境中使用受控专业账号复核。" type="info" :closable="false" show-icon />
    <ProfessionalLoginView v-else-if="!authenticated" @authenticated="loadSubmissions" />

    <template v-else>
      <el-alert v-if="pageError" :title="pageError" type="error" :closable="false" show-icon />
      <section class="review-toolbar">
        <div><strong>{{ total }}</strong><span>条符合当前状态的证据</span></div>
        <el-select v-model="status" aria-label="筛选审核状态" @change="changeStatus">
          <el-option v-for="item in statusOptions" :key="item.value || 'ALL'" :label="item.label" :value="item.value" />
        </el-select>
      </section>

      <section v-loading="loading" class="review-list">
        <article v-for="item in submissions" :key="item.id">
          <header>
            <div><span>{{ evidenceTypeLabel(item.evidenceType) }}</span><h2>{{ item.skill }}</h2></div>
            <b :class="item.status.toLowerCase()">{{ statusLabel(item.status) }}</b>
          </header>
          <p>{{ item.evidenceText }}</p>
          <footer><span>提交于 {{ formatTime(item.createdAt) }}</span><el-button type="primary" plain @click="selected = item">查看并复核</el-button></footer>
        </article>
        <el-empty v-if="!loading && !submissions.length" description="当前没有符合条件的能力证据" />
      </section>

      <el-pagination v-if="total > size" background layout="prev, pager, next" :current-page="page + 1" :page-size="size" :total="total" @current-change="changePage" />
    </template>

    <el-dialog :model-value="selected != null" title="能力证据详情" width="min(720px, 94vw)" @close="selected = null">
      <template v-if="selected">
        <dl class="review-detail">
          <div><dt>能力项</dt><dd>{{ selected.skill }}</dd></div>
          <div><dt>证据类型</dt><dd>{{ evidenceTypeLabel(selected.evidenceType) }}</dd></div>
          <div><dt>当前状态</dt><dd>{{ statusLabel(selected.status) }}</dd></div>
          <div><dt>证据地址</dt><dd><a v-if="selected.evidenceUrl" :href="selected.evidenceUrl" target="_blank" rel="noopener noreferrer">打开证据地址 ↗</a><span v-else>未提供</span></dd></div>
          <div class="wide"><dt>完成说明与结果</dt><dd>{{ selected.evidenceText }}</dd></div>
          <div v-if="selected.validationChecks?.length" class="wide"><dt>自动检查</dt><dd>{{ selected.validationChecks.join('、') }}</dd></div>
          <div v-if="selected.reviewReason" class="wide"><dt>审核记录</dt><dd>{{ selected.reviewReason }}</dd></div>
        </dl>
        <div v-if="selected.status === 'PENDING_VERIFICATION'" class="review-actions"><el-button type="danger" plain @click="openReview('REJECTED')">拒绝</el-button><el-button type="success" @click="openReview('VERIFIED')">审核通过</el-button></div>
      </template>
    </el-dialog>

    <el-dialog :model-value="reviewDecision != null" :title="reviewDecision === 'VERIFIED' ? '确认审核通过' : '确认拒绝证据'" width="min(520px, 94vw)" @close="reviewDecision = null">
      <el-input v-model="reviewReason" type="textarea" :rows="5" maxlength="1000" show-word-limit placeholder="填写可审计的审核理由" />
      <template #footer><el-button @click="reviewDecision = null">取消</el-button><el-button :type="reviewDecision === 'VERIFIED' ? 'success' : 'danger'" :loading="reviewing" @click="submitReview">确认提交</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.verification-review-page { width: min(1120px, calc(100% - 64px)); margin: 0 auto; padding: 44px 0 90px; color: var(--ink); }
.review-page-heading { margin-bottom: 22px; }
.review-page-heading > span { color: var(--forest); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.review-page-heading h1 { margin: 6px 0 10px; font-size: clamp(32px, 5vw, 50px); letter-spacing: -.04em; }
.review-page-heading p { max-width: 780px; margin: 0; color: var(--muted); line-height: 1.75; }
.verification-review-page > .el-alert { margin-bottom: 16px; }
.review-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin: 18px 0; padding: 18px 20px; border: 1px solid var(--line); border-radius: 18px; background: var(--paper); }
.review-toolbar > div { display: flex; align-items: baseline; gap: 8px; }
.review-toolbar strong { font-size: 30px; }
.review-toolbar span { color: var(--muted); }
.review-toolbar .el-select { width: 180px; }
.review-list { display: grid; gap: 14px; min-height: 180px; }
.review-list article { padding: 20px; border: 1px solid var(--line); border-radius: 18px; background: var(--paper); box-shadow: 0 12px 30px rgba(39, 83, 141, .06); }
.review-list article > header, .review-list article > footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.review-list h2 { margin: 4px 0 0; font-size: 22px; }
.review-list header span, .review-list footer span { color: var(--muted); font-size: 12px; }
.review-list header b { padding: 6px 10px; border-radius: 999px; color: #8b661e; background: #fff3cf; font-size: 12px; }
.review-list header b.verified { color: #167a5b; background: #e7f8ef; }
.review-list header b.rejected { color: #b24b4b; background: #fdecec; }
.review-list article > p { margin: 16px 0; color: var(--muted); line-height: 1.75; white-space: pre-wrap; }
.verification-review-page > .el-pagination { justify-content: center; margin-top: 22px; }
.review-detail { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 0; }
.review-detail > div { padding: 14px; border-radius: 12px; background: var(--paper-soft); }
.review-detail > div.wide { grid-column: 1 / -1; }
.review-detail dt { color: var(--muted); font-size: 12px; }
.review-detail dd { margin: 7px 0 0; color: var(--ink); line-height: 1.7; white-space: pre-wrap; }
.review-detail a { color: var(--forest); text-decoration: none; }
.review-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
@media (max-width: 720px) {
  .verification-review-page { width: calc(100% - 28px); padding: 28px 0 110px; }
  .review-toolbar, .review-list article > header, .review-list article > footer { align-items: stretch; flex-direction: column; }
  .review-toolbar .el-select { width: 100%; }
  .review-detail { grid-template-columns: 1fr; }
  .review-detail > div.wide { grid-column: auto; }
  .review-actions { display: grid; grid-template-columns: 1fr 1fr; }
}
</style>
