export function publicSkillRanking(skills: string[]) {
  return [...new Set(skills.filter(Boolean))].map((name, index) => ({ name, rank: index + 1 }))
}

export function fillRoleSlots<T>(roles: T[], size = 4): Array<T | null> {
  return Array.from({ length: size }, (_, index) => roles[index] ?? null)
}

export function evidenceDrawerState(input: { loading: boolean; error: string; hasEvidence: boolean }) {
  if (input.loading) return 'loading'
  if (input.error) return 'error'
  return input.hasEvidence ? 'ready' : 'empty'
}

type WorkspaceInput = {
  profile: { abilities?: unknown[] } | null
  match: { roleName?: string; readiness?: number } | null
  plan: { items?: unknown[] } | null
  verifiedOrders: number[]
}

export function workspaceSnapshot(input: WorkspaceInput) {
  return { diagnosed: Boolean(input.profile && input.match), targetRole: input.match?.roleName || '目标岗位暂定', readiness: input.match?.readiness ?? null, abilityCount: input.profile?.abilities?.length ?? 0, planCount: input.plan?.items?.length ?? 0, verifiedCount: input.verifiedOrders.length }
}
