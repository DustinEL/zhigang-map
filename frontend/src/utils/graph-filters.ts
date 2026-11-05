import type { GraphFilterParams, GraphView, PublicRoleOverview } from '../types/models'

function active(value?: string) {
  const normalized = value?.trim()
  return normalized && normalized.toUpperCase() !== 'ALL' ? normalized : undefined
}

function includes(actual: string | undefined, expected: string | undefined) {
  const filter = active(expected)
  return !filter || Boolean(actual?.toLocaleLowerCase().includes(filter.toLocaleLowerCase()))
}

function equals(actual: string | undefined, expected: string | undefined) {
  const filter = active(expected)
  return !filter || actual?.trim().toLocaleLowerCase() === filter.toLocaleLowerCase()
}

export function normalizeGraphFilters(filters: GraphFilterParams): GraphFilterParams {
  return Object.fromEntries(Object.entries(filters).map(([key, value]) => [key, active(value)]).filter(([, value]) => value))
}

export function roleMatchesGraphFilters(role: PublicRoleOverview, filters: GraphFilterParams) {
  const techStack = active(filters.techStack)
  const roleTechStack = role.techStack?.length ? role.techStack : role.coreSkills
  return (!techStack || roleTechStack.some(value => equals(value, techStack)))
    && includes(role.jobLevel, filters.jobLevel)
    && includes(role.roleVersion, filters.roleVersion)
    && includes(role.evidenceLayer ?? (role.status === 'PUBLISHED' ? 'REAL_EVIDENCE' : role.status), filters.evidenceLayer)
}

export function filterGraphByRoles(graph: GraphView, roles: PublicRoleOverview[], filters: GraphFilterParams): GraphView {
  const normalized = normalizeGraphFilters(filters)
  if (!Object.keys(normalized).length) return graph

  const roleNodeIds = new Set(roles.filter(role => roleMatchesGraphFilters(role, normalized)).map(role => `role-${role.id}`))
  const nodeById = new Map(graph.nodes.map(node => [node.id, node]))
  const techStack = active(normalized.techStack)
  const edges = graph.edges.filter(edge => {
    const source = nodeById.get(edge.source)
    const target = nodeById.get(edge.target)
    if (!source || !target) return false
    if (source.type === 'JOB_FAMILY' && target.type === 'JOB_ROLE') return roleNodeIds.has(target.id)
    if (source.type === 'JOB_ROLE') {
      if (!roleNodeIds.has(source.id)) return false
      return !techStack || target.type !== 'SKILL' || equals(target.label, techStack) || equals(target.group, techStack)
    }
    if (target.type === 'JOB_ROLE') return roleNodeIds.has(target.id)
    return false
  })
  const connectedNodeIds = new Set(edges.flatMap(edge => [edge.source, edge.target]))
  roleNodeIds.forEach(id => connectedNodeIds.add(id))
  return { ...graph, nodes: graph.nodes.filter(node => connectedNodeIds.has(node.id)), edges }
}
