<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  eyebrow: string
  title: string
  description?: string
  icon?: Component
  dark?: boolean
}>()
</script>

<template>
  <header :class="['tech-page-header', { dark }]">
    <div class="tech-page-heading">
      <span v-if="icon" class="tech-page-icon"><el-icon><component :is="icon" /></el-icon></span>
      <div><small>{{ eyebrow }}</small><h1>{{ title }}</h1><p v-if="description">{{ description }}</p></div>
    </div>
    <div v-if="$slots.actions" class="tech-page-actions"><slot name="actions" /></div>
  </header>
</template>
