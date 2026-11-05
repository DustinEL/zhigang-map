import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layouts/AppLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
  routes: [
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'home', meta: { title: '职业首页' }, component: () => import('../views/StudentHomeView.vue') },
        { path: 'workspace', name: 'workspace', meta: { title: '我的工作台' }, component: () => import('../views/WorkspaceView.vue') },
        { path: 'showcase', name: 'showcase', meta: { title: '产品导览', showcase: true }, component: () => import('../views/CompetitionDemoView.vue') },
        { path: 'jobs', name: 'jobs', meta: { title: '岗位方向' }, component: () => import('../views/JobLibraryView.vue') },
        { path: 'discovery', name: 'discovery', meta: { title: '发现新岗位' }, component: () => import('../views/PublicEmergingDiscoveryView.vue') },
        { path: 'evolution', name: 'evolution', meta: { title: '岗位演化' }, component: () => import('../views/RoleEvolutionView.vue') },
        { path: 'abilities', name: 'abilities', meta: { title: '岗位图谱' }, component: () => import('../views/GraphExplorerView.vue') },
        { path: 'diagnosis', name: 'diagnosis', meta: { title: '职业诊断' }, component: () => import('../views/DiagnosisView.vue') },
        { path: 'plan', name: 'plan', meta: { title: '行动计划' }, component: () => import('../views/GrowthPlanView.vue') },
        { path: 'professional', redirect: '/professional/verifications' },
        { path: 'professional/verifications', name: 'professional-verifications', meta: { title: '能力证据审核' }, component: () => import('../views/ProfessionalVerificationView.vue') },
        { path: 'professional/panorama', redirect: '/abilities' },
        { path: 'professional/emerging', redirect: '/discovery' },
        { path: 'professional/evolution', redirect: '/evolution' },
        { path: 'professional/roles', redirect: '/jobs' },
        { path: 'professional/login', redirect: '/professional/verifications' },
        { path: 'professional/real-data', redirect: '/discovery' },
        { path: 'professional/market', redirect: '/jobs' },
        { path: 'professional/data', redirect: '/workspace' },
        { path: 'professional/governance', redirect: '/professional/verifications' },
        { path: 'professional/evaluation', redirect: '/workspace' },
        { path: 'market', redirect: '/jobs' },
        { path: 'data', redirect: '/workspace' },
        { path: 'governance', redirect: '/workspace' },
        { path: 'graph', redirect: '/abilities' },
        { path: 'growth', redirect: '/plan' },
        { path: 'demo', redirect: '/showcase' },
        { path: 'evaluation', redirect: '/workspace' },
        { path: 'career', redirect: '/diagnosis' },
        { path: ':pathMatch(.*)*', name: 'not-found', meta: { title: '页面未找到' }, component: () => import('../views/NotFoundView.vue') }
      ]
    }
  ]
})

export default router
