<script setup lang="ts">
import { ref } from 'vue'
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus/es/components/message/index'
import { api, errorMessage, runtime } from '../api'

const emit = defineEmits<{ authenticated: [] }>()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function login() {
  if (!username.value.trim() || !password.value) return ElMessage.warning('请输入专业端账号和密码')
  loading.value = true
  try {
    runtime.professional = await api.professionalLogin(username.value.trim(), password.value)
    emit('authenticated')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="professional-login">
    <header><span>JOB INTELLIGENCE</span><h2>登录岗位智能端</h2><p>真实数据导入、岗位提案、审核与发布需要专业账号。</p></header>
    <el-input v-model="username" :prefix-icon="User" autocomplete="username" placeholder="专业账号" @keyup.enter="login" />
    <el-input v-model="password" :prefix-icon="Lock" autocomplete="current-password" placeholder="密码" show-password @keyup.enter="login" />
    <el-button type="primary" :loading="loading" @click="login">登录</el-button>
  </section>
</template>
