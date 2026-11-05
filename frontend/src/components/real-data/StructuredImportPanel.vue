<script setup lang="ts">
import { ref } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import type { StructuredImportResult } from '../../types/models'
import { reviewLabel, reviewTone } from '../../utils/review-display'

defineProps<{ importing: boolean; canImport: boolean; readOnly: boolean; result: StructuredImportResult | null }>()
const emit = defineEmits<{ importFile: [event: Event] }>()
const fileInput = ref<HTMLInputElement | null>(null)

function chooseFile() {
  fileInput.value?.click()
}
</script>

<template>
  <section class="panel">
    <header class="panel-head pipeline-import-head">
      <div><h2>{{ readOnly ? '已发布岗位资料' : '结构化岗位资料导入' }}</h2><p>{{ readOnly ? '当前仅展示已完成校验和审核的岗位资料。' : '导入后自动检查重复内容、证据位置和质量问题。' }}</p></div>
      <div class="action-row compact">
        <input ref="fileInput" class="visually-hidden" type="file" accept=".json,application/json" @change="emit('importFile', $event)" />
        <el-button v-if="canImport" type="primary" :icon="UploadFilled" :loading="importing" @click="chooseFile">选择岗位资料文件</el-button>
        <span v-else class="table-note">{{ readOnly ? '当前资料仅供查看' : '当前账号只可执行独立批次审核' }}</span>
      </div>
    </header>
    <div v-if="result" class="import-result-strip">
      <div><span>导入编号</span><strong>{{ result.runCode }}</strong></div><div><span>接收</span><strong>{{ result.acceptedCount }}</strong></div><div><span>重复</span><strong>{{ result.duplicateCount }}</strong></div><div><span>自动通过</span><strong>{{ result.autoApprovedCount }}</strong></div><div><span>抽检</span><strong>{{ result.sampledCount }}</strong></div><div><span>异常</span><strong>{{ result.exceptionCount }}</strong></div>
      <span class="status-chip" :class="reviewTone(result.status)">{{ reviewLabel(result.status) }}</span>
    </div>
  </section>
</template>
