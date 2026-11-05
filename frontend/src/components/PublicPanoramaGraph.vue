<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GraphFilterParams, GraphView, PublicRoleOverview } from '../types/models'
import { normalizeGraphFilters } from '../utils/graph-filters'

const props = defineProps<{ graph: GraphView; roles: PublicRoleOverview[]; selectedRoleId: number }>()
const emit = defineEmits<{ roleClick: [roleId: number]; filterChange: [filters: GraphFilterParams] }>()
const search = ref('')
const family = ref('ALL')
const techStack = ref('ALL')
const jobLevel = ref('ALL')
const roleVersion = ref('ALL')
const evidenceLayer = ref('ALL')
const showSkills = ref(false)

const families = computed(() => ['ALL', ...new Set(props.roles.map(role => role.family))])
const techStacks = computed(() => [...new Set(props.roles.flatMap(role => role.techStack ?? role.coreSkills))].sort((left, right) => left.localeCompare(right, 'zh-CN')))
const jobLevels = computed(() => [...new Set(props.roles.map(role => role.jobLevel).filter(Boolean) as string[])])
const roleVersions = computed(() => [...new Set(props.roles.map(role => role.roleVersion).filter(Boolean) as string[])])
const graphFilters = computed(() => normalizeGraphFilters({ techStack: techStack.value, jobLevel: jobLevel.value, roleVersion: roleVersion.value, evidenceLayer: evidenceLayer.value }))
const graphRoleIds = computed(() => new Set(props.graph.nodes.filter(node => node.type === 'JOB_ROLE').map(node => Number(node.id.replace('role-', ''))).filter(Number.isFinite)))
const matchedRoles = computed(() => props.roles.filter(role => {
  const matchesSearch = !search.value.trim() || `${role.name} ${role.family} ${role.coreSkills.join(' ')}`.toLowerCase().includes(search.value.trim().toLowerCase())
  return graphRoleIds.value.has(role.id) && matchesSearch && (family.value === 'ALL' || role.family === family.value)
}))
const roleGroups = computed(() => families.value.slice(1).map(name => ({ name, roles: matchedRoles.value.filter(role => role.family === name) })).filter(group => group.roles.length))
const visibleSkills = computed(() => new Set(matchedRoles.value.flatMap(role => role.coreSkills)))
const matchedRoleNodeIds = computed(() => new Set(matchedRoles.value.map(role => `role-${role.id}`)))
const visibleRelationCount = computed(() => props.graph.edges.filter(edge => matchedRoleNodeIds.value.has(edge.source) || matchedRoleNodeIds.value.has(edge.target)).length)

function notifyFilterChange() {
  emit('filterChange', graphFilters.value)
}

function resetView() {
  search.value = ''
  family.value = 'ALL'
  techStack.value = 'ALL'
  jobLevel.value = 'ALL'
  roleVersion.value = 'ALL'
  evidenceLayer.value = 'ALL'
  showSkills.value = false
  notifyFilterChange()
}
</script>

<template>
  <section class="public-panorama">
    <header class="panorama-toolbar">
      <el-input v-model="search" clearable placeholder="搜索岗位或技能" aria-label="搜索岗位或技能" />
      <el-select v-model="family" aria-label="筛选岗位族">
        <el-option v-for="item in families" :key="item" :label="item === 'ALL' ? '全部岗位族' : item" :value="item" />
      </el-select>
      <el-select v-model="techStack" filterable aria-label="筛选技术栈" @change="notifyFilterChange">
        <el-option label="全部技术栈" value="ALL" />
        <el-option v-for="item in techStacks" :key="item" :label="item" :value="item" />
      </el-select>
      <el-select v-model="jobLevel" aria-label="筛选岗位级别" @change="notifyFilterChange">
        <el-option label="全部级别" value="ALL" />
        <el-option v-for="item in jobLevels" :key="item" :label="item" :value="item" />
      </el-select>
      <el-select v-model="roleVersion" aria-label="筛选当前版本" @change="notifyFilterChange">
        <el-option label="全部版本" value="ALL" />
        <el-option v-for="item in roleVersions" :key="item" :label="item" :value="item" />
      </el-select>
      <el-select v-model="evidenceLayer" aria-label="筛选证据层" @change="notifyFilterChange">
        <el-option label="全部证据层" value="ALL" />
        <el-option label="真实证据画像" value="REAL_EVIDENCE" />
        <el-option label="能力基线" value="BASELINE" />
        <el-option label="资料建设中" value="BUILDING" />
      </el-select>
      <el-button @click="resetView">重置</el-button>
    </header>

    <div class="panorama-stats">
      <span>岗位 <b>{{ matchedRoles.length }}</b></span>
      <span>技能 <b>{{ visibleSkills.size }}</b></span>
      <span>关系 <b>{{ visibleRelationCount }}</b></span>
      <small>点击岗位查看详情，默认只展示当前筛选结果。</small>
      <button class="atlas-skill-toggle" type="button" @click="showSkills = !showSkills">{{ showSkills ? '隐藏核心技能' : '显示核心技能' }}</button>
    </div>

    <div class="career-atlas" role="region" :aria-label="`岗位关系图，共 ${matchedRoles.length} 个岗位`">
      <section v-for="(group, groupIndex) in roleGroups" :key="group.name" class="atlas-family-lane">
        <header>
          <i>{{ String(groupIndex + 1).padStart(2, '0') }}</i>
          <div><strong>{{ group.name }}</strong><small>{{ group.roles.length }} 个岗位</small></div>
        </header>
        <div class="atlas-role-grid">
          <article v-for="role in group.roles" :key="role.id" :class="['atlas-role-branch', role.status.toLowerCase(), { selected: role.id === selectedRoleId }]">
            <button class="atlas-role-node" :aria-pressed="role.id === selectedRoleId" @click="emit('roleClick', role.id)">
              <span />
              <strong>{{ role.name }}</strong>
              <small>{{ role.status === 'PUBLISHED' ? '真实证据画像' : role.status === 'BASELINE' ? '能力基线' : '资料建设中' }}</small>
            </button>
            <div v-if="showSkills && role.coreSkills.length" class="atlas-skill-branches">
              <span v-for="skill in role.coreSkills.slice(0, 3)" :key="skill">{{ skill }}</span>
            </div>
          </article>
        </div>
      </section>
      <el-empty v-if="!roleGroups.length" description="没有符合筛选条件的岗位" />
    </div>
  </section>
</template>
