<script setup lang="ts">
import type { Component } from 'vue'

withDefaults(defineProps<{
  title: string
  summary?: string
  icon?: Component
  open?: boolean
}>(), { summary: '', open: false })
</script>

<template>
  <details class="compact-disclosure" :open="open">
    <summary>
      <span v-if="icon" class="compact-disclosure-icon"><el-icon><component :is="icon" /></el-icon></span>
      <span><strong>{{ title }}</strong><small v-if="summary">{{ summary }}</small></span>
      <i aria-hidden="true">+</i>
    </summary>
    <div class="compact-disclosure-body"><slot /></div>
  </details>
</template>
