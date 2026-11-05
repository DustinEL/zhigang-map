<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Collection, Connection, MagicStick, Search, Check, Delete } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { ElMessageBox } from 'element-plus/es/components/message-box/index'
import { api, errorMessage, frozenCache } from '../api'
import type { EmergingQueryDiscovery } from '../types/models'
import { shortRoleDescription } from '../utils/role-copy'
import { canPublishEmergingQuery, normalizeEmergingQuery } from '../utils/emerging-query-cache'

const query = ref('')
const result = ref<EmergingQueryDiscovery | null>(null)
const recent = ref<EmergingQueryDiscovery[]>([])
const searching = ref(false)
const pageError = ref('')
const publishing = ref(false)
const removing = ref(false)
const suggestions = ['大模型推理加速工程师', 'AI Agent', 'RAG 应用工程师', 'LLMOps', '提示词工程师', '模型评测工程师', '向量检索工程师']

const discoveryStates = {
  EXISTING_ROLE: { label: '已有岗位', tone: 'existing', description: '公开证据显示这个方向已经足够接近某个岗位。' },
  EMERGING_ROLE: { label: '新兴岗位', tone: 'emerging', description: '已找到较稳定的职责、技能和公开来源。' },
  EVIDENCE_INSUFFICIENT: { label: '资料不足', tone: 'warning', description: '当前公开信息还不够完整，暂不建议直接定岗。' },
  WAITING_GENERATION: { label: '暂时不可查询', tone: 'busy', description: '查询服务繁忙，请稍后再试。' },
  OUT_OF_SCOPE: { label: '暂不支持', tone: 'muted', description: '当前仅支持部分技术方向。' }
} as const

const searchProgress = computed(() => frozenCache
  ? {
      button: '正在查询当前资料...',
      title: '正在查询公开证据快照',
      detail: '正在匹配已收录岗位与公开证据'
    }
  : {
      button: 'AI 正在分析中...',
      title: '大模型正在分析',
      detail: '正在联网搜索证据 → 分析岗位特征 → 推理职责技能'
    })

// 判断是否为 AI 推理结果
function isReasoningResult(item: EmergingQueryDiscovery) {
  return item.errorCode?.startsWith('AI_REASONING_')
}

function getReasoningConfidence(item: EmergingQueryDiscovery) {
  if (!item.errorCode?.startsWith('AI_REASONING_')) return ''
  const confidence = item.errorCode.replace('AI_REASONING_', '')
  return confidence === 'LOW' ? '低置信度' : confidence === 'MEDIUM' ? '中置信度' : '推理分析'
}

const resultState = computed(() => result.value ? discoveryStates[result.value.status] : null)

function stateFor(item: EmergingQueryDiscovery) { return discoveryStates[item.status] }
function summaryFor(item: EmergingQueryDiscovery) { return shortRoleDescription({ name: item.name || item.query, definition: item.definition, responsibilities: item.responsibilities }) }
function completenessFor(item: EmergingQueryDiscovery) {
  if (item.fieldEvidenceComplete) return '信息完整'
  if (item.evidenceCount === 0 || item.sourceCount === 0) return '尚无有效证据'
  return '信息待补充'
}

function percent(value: number) {
  return `${Math.round(value * 100)}%`
}

function fieldEvidenceCount(value: string) {
  return result.value?.fieldEvidence.filter(item => item.fieldValue === value).length ?? 0
}

async function loadRecent() {
  try {
    const records = await api.emergingQueries(100)
    const selected = new Map<string, EmergingQueryDiscovery>()
    records
      .sort((left, right) => Number(Boolean(right.publishedRoleId)) - Number(Boolean(left.publishedRoleId)))
      .forEach(item => {
        const key = normalizeEmergingQuery(item.name || item.query)
        if (!selected.has(key)) selected.set(key, item)
      })
    recent.value = [...selected.values()].slice(0, 12)
  } catch {
    recent.value = []
  }
}

async function discover() {
  const value = query.value.trim()
  if (!value) return ElMessage.warning('请输入要发现的岗位或方向')
  searching.value = true
  pageError.value = ''
  result.value = null
  try {
    // 按当前运行模式查询冻结快照或实时后端。
    result.value = await api.discoverEmergingQuery(value)
    if (!frozenCache) await loadRecent()

    // 根据结果类型显示不同提示
    if (isReasoningResult(result.value)) {
      ElMessage.info('AI 推理模式：基于有限证据生成结果')
    } else if (result.value.fromCache) {
      ElMessage.success('已找到相近岗位结果')
    } else if (result.value.publishedRoleId) {
      ElMessage.success('已生成岗位详情')
    }
  } catch (error) {
    pageError.value = errorMessage(error)
  } finally {
    searching.value = false
  }
}

async function chooseSuggestion(value: string) {
  query.value = value
  await discover()
}

function chooseRecent(item: EmergingQueryDiscovery) {
  query.value = item.query
  result.value = item
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function addToGraph() {
  if (!result.value || !canPublishEmergingQuery(result.value)) return
  publishing.value = true
  try {
    const published = await api.publishEmergingQuery(result.value.id)
    ElMessage.success('已加入岗位图谱')
    // 更新本地状态，使用后端返回的真实 publishedRoleId
    result.value = published
    // 刷新最近查询列表
    await loadRecent()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    publishing.value = false
  }
}

async function removeFromGraph(item: EmergingQueryDiscovery | null = result.value) {
  if (!item?.publishedRoleId) return
  try {
    await ElMessageBox.confirm(
      `确认将“${item.name || item.query}”从当前岗位图谱移除吗？历史版本和发现记录仍会保留。`,
      '从图谱移除',
      { type: 'warning', confirmButtonText: '确认移除', cancelButtonText: '取消' }
    )
    removing.value = true
    const removed = await api.removeEmergingQueryFromGraph(item.id)
    if (result.value?.id === item.id) result.value = removed
    await loadRecent()
    ElMessage.success('已从当前岗位图谱移除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(errorMessage(error))
  } finally {
    removing.value = false
  }
}

onMounted(loadRecent)
</script>

<template>
  <div class="discovery-page">
    <header class="discovery-heading">
      <span class="heading-icon"><el-icon><MagicStick /></el-icon></span>
      <div>
        <span>新兴岗位发现</span>
        <h1>发现新岗位方向</h1>
        <p>输入一个岗位名或方向，查看已收录的定义、职责、技能和公开来源。资料不足时会明确提示。</p>
      </div>
    </header>

    <el-alert v-if="pageError" :title="pageError" type="info" :closable="false" show-icon />

    <section class="discovery-radar" aria-label="岗位雷达搜索台">
      <div class="radar-scan-line" aria-hidden="true"></div>
      <div class="radar-console__intro"><span>岗位雷达</span><p>从公开证据快照中定位岗位定义、来源与信息完整度。</p></div>
      <form class="discovery-search" @submit.prevent="discover">
        <el-input v-model="query" :prefix-icon="Search" clearable placeholder="输入岗位或方向，例如：AI Agent、RAG" />
        <el-button type="primary" native-type="submit" :loading="searching" :disabled="searching">
          <span v-if="!searching">发现新岗位</span>
          <span v-else>{{ searchProgress.button }}</span>
        </el-button>
      </form>
      <div v-if="searching" class="ai-thinking-indicator">
        <div class="thinking-spinner"></div>
        <p class="radar-querying" role="status">
          <strong>{{ searchProgress.title }}</strong>
          <span class="thinking-steps">{{ searchProgress.detail }}</span>
        </p>
      </div>
      <div v-else class="query-suggestions"><span>推荐方向</span><button v-for="item in suggestions" :key="item" type="button" :disabled="searching" @click="chooseSuggestion(item)">{{ item }}</button></div>
    </section>

    <section v-if="result && resultState" class="discovery-result">
      <article class="result-definition">
        <header class="result-topline">
          <div>
            <span :class="['result-state', resultState.tone]">{{ resultState.label }}</span>
            <span class="domain-chip">{{ result.domain }}</span>
            <span v-if="result.fromCache" class="cache-chip">历史结果 {{ percent(result.cacheSimilarity) }}</span>
            <span v-if="isReasoningResult(result)" class="reasoning-chip" title="基于大模型领域知识推理">
              AI推理 · {{ getReasoningConfidence(result) }}
            </span>
          </div>
          <div class="result-actions">
            <RouterLink v-if="result.publishedRoleId" :to="`/abilities?role=${result.publishedRoleId}`">
              <el-button type="primary">
                <el-icon style="margin-right: 4px;"><Connection /></el-icon>
                查看岗位图谱
              </el-button>
            </RouterLink>
            <RouterLink v-else-if="result.status === 'EXISTING_ROLE' && result.nearestRoleId" :to="`/abilities?role=${result.nearestRoleId}`">
              <el-button type="primary">查看已有岗位</el-button>
            </RouterLink>
            <el-button
              v-if="canPublishEmergingQuery(result)"
              type="success"
              :loading="publishing"
              :disabled="publishing"
              @click="addToGraph"
            >
              <el-icon v-if="!publishing" style="margin-right: 4px;"><Collection /></el-icon>
              {{ publishing ? '加入中...' : '加入图谱' }}
            </el-button>
            <el-tooltip v-if="result.publishedRoleId" content="已成功加入岗位图谱" placement="bottom">
              <span class="published-badge">
                <el-icon><Check /></el-icon>
                已入图
              </span>
            </el-tooltip>
            <el-button
              v-if="result.publishedRoleId"
              type="danger"
              plain
              :icon="Delete"
              :loading="removing"
              :disabled="removing"
              @click="removeFromGraph"
            >从图谱移除</el-button>
          </div>
        </header>

        <h2>{{ result.name || result.query }}</h2>
        <p class="result-description">{{ shortRoleDescription({ name: result.name || result.query, definition: result.definition, responsibilities: result.responsibilities }) }}</p>
        <p v-if="isReasoningResult(result)" class="reasoning-notice">
          以下内容由大模型基于有限证据和领域知识推理生成。
        </p>

        <dl class="result-metrics">
          <div class="metric"><dt>最近岗位</dt><dd>{{ result.nearestRoleName || '暂无' }}</dd></div>
          <div class="metric"><dt>相似度</dt><dd>{{ percent(result.nearestRoleSimilarity) }}</dd></div>
          <div class="metric"><dt>新颖度</dt><dd>{{ percent(result.noveltyScore) }}</dd></div>
          <div class="metric"><dt>{{ frozenCache ? '公开证据' : '联网证据' }}</dt><dd>{{ result.evidenceCount }} 条 / {{ result.sourceCount }} 个来源</dd></div>
        </dl>

        <section v-if="result.responsibilities.length" class="definition-block">
          <h3>核心职责</h3>
          <ol>
            <li v-for="item in result.responsibilities" :key="item">{{ item }}<small>{{ fieldEvidenceCount(item) }} 条依据</small></li>
          </ol>
        </section>

        <div v-if="result.requiredSkills.length || result.bonusSkills.length" class="skill-columns">
          <section>
            <h3>必备技能</h3>
            <div><span v-for="item in result.requiredSkills" :key="item">{{ item }}<small>{{ fieldEvidenceCount(item) }}</small></span></div>
          </section>
          <section>
            <h3>加分技能</h3>
            <div><span v-for="item in result.bonusSkills" :key="item">{{ item }}<small>{{ fieldEvidenceCount(item) }}</small></span></div>
          </section>
        </div>

        <section v-if="result.scenarios.length" class="definition-block scenarios">
          <h3>典型场景</h3>
          <div><span v-for="item in result.scenarios" :key="item">{{ item }}</span></div>
        </section>
      </article>

      <aside class="result-evidence">
        <header><div><el-icon><Connection /></el-icon><h3>{{ isReasoningResult(result) ? '证据与推理' : '多源证据' }}</h3></div><span>{{ result.evidenceCount }}</span></header>
        <div v-if="isReasoningResult(result)" class="reasoning-evidence-notice">
          <strong>推理模式</strong>
          <p>当前结果结合了有限证据和大模型领域知识。推理内容会标注特殊来源。</p>
        </div>
        <div class="evidence-gates">
          <span class="gate" :class="{ pass: result.sourceCount >= 2 }">独立来源 {{ result.sourceCount }}</span>
          <span class="gate" :class="{ pass: result.fieldEvidenceComplete }">{{ result.fieldEvidenceComplete ? '岗位信息完整' : '岗位信息待补充' }}</span>
          <span v-if="isReasoningResult(result)" class="gate reasoning-gate">AI 推理补充</span>
        </div>
        <div v-if="result.evidence.length" class="evidence-list">
          <article v-for="item in result.evidence" :key="item.id" :class="{ 'reasoning-evidence': item.domain === 'ai-reasoning' }">
            <header>
              <strong>{{ item.title }}</strong>
              <span :class="{ 'reasoning-tag': item.domain === 'ai-reasoning' }">{{ item.key }}</span>
            </header>
            <p>{{ item.excerpt }}</p>
            <footer>
              <span>{{ item.source || item.domain }}<template v-if="item.publishedAt"> · {{ item.publishedAt }}</template></span>
              <a v-if="item.url && item.domain !== 'ai-reasoning'" :href="item.url" target="_blank" rel="noopener noreferrer">查看来源</a>
              <span v-else-if="item.domain === 'ai-reasoning'" class="reasoning-badge">AI 推理</span>
            </footer>
          </article>
        </div>
        <p v-else class="no-evidence">证据不足</p>

      </aside>
    </section>

    <section v-if="recent.length" class="recent-discoveries">
      <header><div><span>历史记录</span><h2>最近查询</h2></div><small>点击可再次查看定义与来源</small></header>
      <div class="recent-grid">
        <article v-for="item in recent" :key="item.id" class="recent-card">
          <button class="recent-card__open" type="button" @click="chooseRecent(item)">
            <span :class="['recent-status', stateFor(item).tone]">{{ stateFor(item).label }}</span>
            <span v-if="isReasoningResult(item)" class="recent-reasoning-badge">AI推理</span>
            <strong>{{ item.name || item.query }}</strong>
            <span class="recent-card__summary">{{ summaryFor(item) }}</span>
            <span class="recent-card__metrics"><span>证据 {{ item.evidenceCount }} 条</span><span>来源 {{ item.sourceCount }} 个</span><span>{{ completenessFor(item) }}</span></span>
            <span class="recent-card__action">查看岗位画像 <span aria-hidden="true">→</span></span>
          </button>
          <el-button
            v-if="item.publishedRoleId"
            class="recent-card__remove"
            type="danger"
            plain
            :icon="Delete"
            :loading="removing"
            :disabled="removing"
            @click="removeFromGraph(item)"
          >从图谱移除</el-button>
        </article>
      </div>
    </section>

    <section v-else-if="!result" class="discovery-empty">
      <el-icon><Collection /></el-icon>
      <h2>从一个岗位名开始</h2>
      <p>输入一个岗位名，查看岗位职责、技能要求和公开来源；信息不足时会明确提示。</p>
    </section>
  </div>
</template>
