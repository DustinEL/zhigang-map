<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GraphView } from '../types/models'
import { compactResponsibility } from '../utils/role-copy'

const props = defineProps<{ graph: GraphView | null }>()
const emit = defineEmits<{ nodeClick: [node: GraphView['nodes'][number]] }>()
const width = 980
const expanded = ref(false)
watch(() => props.graph?.viewType, value => { expanded.value = value === 'FULL_RELATIONS' })

type NodeType = 'JOB_ROLE' | 'SKILL' | 'RESPONSIBILITY' | 'LEARNING_RESOURCE'
type Position = { x: number; y: number; width: number; height: number }

const dimensions: Record<NodeType, { width: number; height: number }> = {
  JOB_ROLE: { width: 200, height: 72 },
  SKILL: { width: 166, height: 54 },
  RESPONSIBILITY: { width: 220, height: 64 },
  LEARNING_RESOURCE: { width: 200, height: 56 }
}

const colors: Record<NodeType, { fill: string; accent: string }> = {
  JOB_ROLE: { fill: '#1e3a5f', accent: '#10233f' },
  SKILL: { fill: '#e6f6fb', accent: '#0891b2' },
  RESPONSIBILITY: { fill: '#fff4df', accent: '#d97706' },
  LEARNING_RESOURCE: { fill: '#eef2ff', accent: '#6366f1' }
}

const typeLabels: Record<NodeType, string> = {
  JOB_ROLE: '目标岗位',
  SKILL: '核心技能',
  RESPONSIBILITY: '岗位职责',
  LEARNING_RESOURCE: '学习资源'
}

const groupedNodes = computed(() => {
  const groups: Record<string, GraphView['nodes']> = {}
  props.graph?.nodes.forEach(node => {
    ;(groups[node.type] ??= []).push(node)
  })
  return groups
})

const visibleSkills = computed(() => {
  const skills = groupedNodes.value.SKILL ?? []
  return expanded.value ? skills : skills.slice(0, 6)
})

const visibleResponsibilities = computed(() => {
  const responsibilities = groupedNodes.value.RESPONSIBILITY ?? []
  const meaningful = responsibilities.filter(node => compactResponsibility(node.label))
  return expanded.value ? meaningful : meaningful.slice(0, 3)
})

const hasHiddenNodes = computed(() =>
  (groupedNodes.value.SKILL?.length ?? 0) > 6 ||
  (groupedNodes.value.RESPONSIBILITY?.filter(node => compactResponsibility(node.label)).length ?? 0) > 3
)

const canvasHeight = computed(() => {
  const rowCount = Math.max(visibleSkills.value.length, visibleResponsibilities.value.length)
  return Math.max(330, 58 + rowCount * 66)
})

const learningResources = computed(() => groupedNodes.value.LEARNING_RESOURCE ?? [])
const roleNode = computed(() => groupedNodes.value.JOB_ROLE?.[0])
const visibleNodes = computed(() => [
  ...(roleNode.value ? [roleNode.value] : []),
  ...visibleSkills.value,
  ...visibleResponsibilities.value
])
const mobileGroups = computed(() => [
  { type: 'SKILL', label: '核心技能', nodes: visibleSkills.value },
  { type: 'RESPONSIBILITY', label: '岗位职责', nodes: visibleResponsibilities.value }
])

const visibleEdges = computed(() => {
  if (!props.graph) return []
  const visibleNodeIds = new Set(visibleNodes.value.map(node => node.id))
  const nodeTypes = new Map(visibleNodes.value.map(node => [node.id, node.type]))
  return props.graph.edges.filter(edge => {
    if (!visibleNodeIds.has(edge.source) || !visibleNodeIds.has(edge.target)) return false
    const sourceType = nodeTypes.get(edge.source)
    const targetType = nodeTypes.get(edge.target)
    return sourceType === 'JOB_ROLE' && (targetType === 'SKILL' || targetType === 'RESPONSIBILITY')
  })
})

const positions = computed(() => {
  const result = new Map<string, Position>()
  if (!props.graph) return result
  const role = roleNode.value
  if (role) result.set(role.id, { x: 390, y: (canvasHeight.value - dimensions.JOB_ROLE.height) / 2, ...dimensions.JOB_ROLE })

  visibleSkills.value.forEach((node, index) => {
    result.set(node.id, { x: 34, y: 44 + index * 66, ...dimensions.SKILL })
  })

  const responsibilities = visibleResponsibilities.value
  const responsibilityOffset = Math.max(44, (canvasHeight.value - responsibilities.length * 78) / 2)
  responsibilities.forEach((node, index) => {
    result.set(node.id, { x: 720, y: responsibilityOffset + index * 78, ...dimensions.RESPONSIBILITY })
  })
  return result
})

function edgePath(sourceId: string, targetId: string) {
  const source = positions.value.get(sourceId)
  const target = positions.value.get(targetId)
  if (!source || !target) return ''
  const sourceCenter = { x: source.x + source.width / 2, y: source.y + source.height / 2 }
  const targetCenter = { x: target.x + target.width / 2, y: target.y + target.height / 2 }
  if (targetCenter.x < sourceCenter.x) {
    const junction = source.x - 80
    return `M ${source.x} ${sourceCenter.y} H ${junction} V ${targetCenter.y} H ${target.x + target.width}`
  }
  if (targetCenter.x > sourceCenter.x) {
    const junction = source.x + source.width + 73
    return `M ${source.x + source.width} ${sourceCenter.y} H ${junction} V ${targetCenter.y} H ${target.x}`
  }
  return ''
}

function labelLines(label: string, type: string) {
  const maxLength = type === 'RESPONSIBILITY' ? 11 : 12
  const source = type === 'RESPONSIBILITY' ? compactResponsibility(label) : label
  const compact = source.split(/[，。；、,:：]/)[0].trim() || source
  if (compact.length <= maxLength) return [compact]
  return [`${compact.slice(0, maxLength - 1)}…`]
}

function compactNodeLabel(label: string, type: string) {
  return labelLines(label, type).join('')
}
</script>

<template>
  <div class="graph-wrap">
    <header v-if="graph" class="graph-summary-bar">
      <span>默认展示 6 项核心技能和 3 项职责摘要</span>
      <el-button v-if="hasHiddenNodes" link type="primary" @click="expanded = !expanded">{{ expanded ? '收起' : '展开全部' }}</el-button>
    </header>
    <svg v-if="graph" class="graph-desktop" :viewBox="`0 0 ${width} ${canvasHeight}`" role="group" aria-label="岗位能力关系">
      <defs>
        <marker id="graph-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto" markerUnits="strokeWidth">
        <path d="M0,0 L0,6 L8,3 z" fill="#9bb3c7" />
        </marker>
      </defs>
      <text x="54" y="32" class="graph-lane-title">核心技能</text>
      <text x="720" y="32" class="graph-lane-title">岗位职责</text>
      <g v-for="edge in visibleEdges" :key="edge.id">
        <path v-if="edgePath(edge.source, edge.target)" :d="edgePath(edge.source, edge.target)" class="graph-edge" marker-end="url(#graph-arrow)" />
      </g>
      <g
        v-for="node in visibleNodes"
        :key="node.id"
        class="graph-node"
        :class="{ 'graph-node--interactive': node.type === 'SKILL' }"
        :transform="`translate(${positions.get(node.id)?.x ?? 0} ${positions.get(node.id)?.y ?? 0})`"
        :role="node.type === 'SKILL' ? 'button' : undefined"
        :tabindex="node.type === 'SKILL' ? 0 : undefined"
        @click="node.type === 'SKILL' && emit('nodeClick', node)"
        @keyup.enter="node.type === 'SKILL' && emit('nodeClick', node)"
        @keydown.space.prevent="node.type === 'SKILL'"
        @keyup.space.prevent="node.type === 'SKILL' && emit('nodeClick', node)"
      >
        <rect :width="positions.get(node.id)?.width ?? 180" :height="positions.get(node.id)?.height ?? 64" rx="7" :fill="colors[node.type as NodeType]?.fill ?? '#eef2ef'" />
        <rect :width="5" :height="positions.get(node.id)?.height ?? 64" rx="2.5" :fill="colors[node.type as NodeType]?.accent ?? '#65756d'" />
        <text x="18" y="21" class="graph-node-type" :class="{ 'graph-node-type--inverse': node.type === 'JOB_ROLE' }">{{ typeLabels[node.type as NodeType] ?? node.type }}</text>
        <text x="18" y="43" class="graph-node-label" :class="{ 'graph-node-label--inverse': node.type === 'JOB_ROLE' }">
          <tspan v-for="(line, index) in labelLines(node.label, node.type)" :key="line" x="18" :dy="index === 0 ? 0 : 18">{{ line }}</tspan>
        </text>
        <text v-if="node.type !== 'JOB_ROLE' && node.evidenceCount > 0" :x="(positions.get(node.id)?.width ?? 180) - 14" y="21" class="graph-evidence">{{ node.evidenceCount }} 条证据</text>
        <title>{{ node.type === 'RESPONSIBILITY' ? compactResponsibility(node.label) : node.label }} · 证据 {{ node.evidenceCount }}</title>
      </g>
    </svg>
    <section v-if="graph" class="graph-mobile" aria-label="岗位能力纵向关系">
      <header><span>目标岗位</span><strong>{{ roleNode?.label }}</strong></header>
      <div v-for="group in mobileGroups" :key="group.type" class="graph-mobile-group">
        <h3>{{ group.label }}</h3>
        <button v-for="node in group.nodes" :key="node.id" type="button" class="graph-mobile-node" :class="{ 'graph-node--interactive': node.type === 'SKILL' }" :disabled="node.type !== 'SKILL'" @click="emit('nodeClick', node)">
          <strong>{{ compactNodeLabel(node.label, node.type) }}</strong>
          <span v-if="node.evidenceCount > 0">{{ node.evidenceCount }} 条证据</span>
        </button>
      </div>
    </section>
    <el-empty v-else description="暂无已发布图谱" />
    <section v-if="learningResources.length" class="graph-resources">
      <header><h3>推荐学习资源</h3><span>用于补齐上方岗位所需能力</span></header>
      <div class="graph-resource-grid">
        <article v-for="resource in learningResources" :key="resource.id">
          <span>{{ resource.group }}</span>
          <strong>{{ resource.label }}</strong>
        </article>
      </div>
    </section>
  </div>
</template>
