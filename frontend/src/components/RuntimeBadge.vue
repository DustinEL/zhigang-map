<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { runtime } from '../api'
import type { RuntimeMode } from '../types/models'

const modeLabels: Record<RuntimeMode, string> = {
  REAL: '真实数据',
  REAL_PENDING: '待审核数据',
  DEMO: '演示数据',
  FROZEN_CACHE: '当前资料',
  NO_DATA: '暂无数据',
  ERROR: '加载异常'
}

const mode = computed(() => runtime.mode)
const label = computed(() => modeLabels[runtime.mode])
const showErrorAlert = ref(false)

let dismissTimer: ReturnType<typeof setTimeout> | null = null

function clearDismissTimer() {
  if (dismissTimer !== null) {
    clearTimeout(dismissTimer)
    dismissTimer = null
  }
}

watch(
  () => runtime.mode,
  currentMode => {
    clearDismissTimer()
    if (currentMode === 'ERROR' && runtime.lastError) {
      showErrorAlert.value = true
      dismissTimer = setTimeout(() => {
        showErrorAlert.value = false
      }, 5000)
    } else {
      showErrorAlert.value = false
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  clearDismissTimer()
})
</script>

<template>
  <div class="runtime-badge">
    <span class="mode-badge" :class="mode.toLowerCase()"><i aria-hidden="true" /><span>{{ label }}</span></span>
    <el-alert v-if="showErrorAlert" :title="runtime.lastError" type="warning" closable show-icon @close="showErrorAlert = false" />
  </div>
</template>

<style scoped>
.runtime-badge {
  position: fixed;
  top: 14px;
  right: 16px;
  z-index: 60;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: min(320px, calc(100vw - 32px));
}

.runtime-badge .mode-badge {
  align-self: flex-end;
}
</style>
