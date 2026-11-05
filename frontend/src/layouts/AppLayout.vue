<script setup lang="ts">
import { ref } from 'vue'
import { ArrowLeft, Collection, DataAnalysis, Expand, Fold, HomeFilled, MagicStick, Menu, Share, TrendCharts } from '@element-plus/icons-vue'
import RuntimeBadge from '../components/RuntimeBadge.vue'

const sidebarCollapsed = ref(window.localStorage.getItem('zhigang-sidebar-collapsed') === 'true')

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  window.localStorage.setItem('zhigang-sidebar-collapsed', String(sidebarCollapsed.value))
}

const studentNavigation = [
  { to: '/workspace', label: '我的工作台', icon: Menu },
  { to: '/jobs', label: '岗位方向', icon: Collection },
  { to: '/discovery', label: '发现新岗位', icon: MagicStick },
  { to: '/evolution', label: '岗位演化', icon: TrendCharts },
  { to: '/abilities', label: '岗位图谱', icon: Share },
  { to: '/diagnosis', label: '职业诊断', icon: DataAnalysis },
  { to: '/plan', label: '行动计划', icon: TrendCharts }
]
</script>

<template>
  <div :class="['public-shell', { 'sidebar-collapsed': sidebarCollapsed }]">
    <aside class="public-sidebar">
      <RouterLink to="/" class="public-brand">
        <img :src="'/logo-mark.svg'" alt="智岗图谱">
        <span><strong>智岗图谱</strong><small>证据化 AI 求职决策</small></span>
      </RouterLink>
      <button class="sidebar-toggle" type="button" :aria-label="sidebarCollapsed ? '展开导航' : '收起导航'" :title="sidebarCollapsed ? '展开导航' : '收起导航'" @click="toggleSidebar"><el-icon><component :is="sidebarCollapsed ? Expand : Fold" /></el-icon><span>{{ sidebarCollapsed ? '展开' : '收起导航' }}</span></button>
      <nav class="public-task-nav" aria-label="学生职业导航">
        <p>求职任务</p>
        <RouterLink v-for="item in studentNavigation" :key="item.to" :to="item.to" active-class="active"><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></RouterLink>
      </nav>
      <RouterLink to="/" class="public-home-link"><el-icon><HomeFilled /></el-icon><span>返回职业首页</span></RouterLink>
    </aside>
    <header class="public-mobile-header"><RouterLink to="/" class="public-brand"><img :src="'/logo-mark.svg'" alt="智岗图谱"><span><strong>智岗图谱</strong><small>证据化 AI 求职决策</small></span><span class="brand-home-hint"><el-icon><ArrowLeft /></el-icon>返回首页</span></RouterLink></header>
    <main class="public-main"><RouterView /></main>
    <nav class="mobile-task-nav" aria-label="移动端学生职业导航">
      <RouterLink v-for="(item, index) in studentNavigation" :key="item.to" :to="item.to" active-class="active"><i>{{ index + 1 }}</i><span>{{ item.label }}</span></RouterLink>
    </nav>
  </div>
</template>
