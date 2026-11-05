const labels: Record<string, string> = {
  ELIGIBLE: '质量通过', QUARANTINED: '隔离复核', REJECTED: '已拒绝', PROMOTED: '已晋级',
  PENDING_REVIEW: '待审核', APPROVE: '审核通过', APPROVED: '批次已通过',
  APPROVED_STANDARD: '批准为标准技能', APPROVED_ALIAS: '批准为别名', OBSERVING: '继续观察',
  REJECTED_NOISE: '噪声拒绝', DRAFT: '待批次审核', COMPLETED: '已发布',
  OFFLINE_PREVIEW: '离线预览', AUTO_APPROVED: '系统自动通过', SAMPLE_REVIEW: '低风险抽检',
  EXCEPTION_REVIEW: '异常复核', WAITING_SKILL: '等待技能审核', HUMAN_APPROVED: '人工已通过',
  BLOCKED: '已阻断', LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险'
}

export function reviewLabel(value?: string) {
  return labels[value ?? ''] ?? value ?? '未处理'
}

export function reviewTone(value?: string) {
  if (['ELIGIBLE', 'APPROVE', 'APPROVED', 'APPROVED_STANDARD', 'APPROVED_ALIAS', 'COMPLETED', 'PROMOTED'].includes(value ?? '')) return 'passed'
  if (['REJECTED', 'REJECTED_NOISE'].includes(value ?? '')) return 'failed'
  return 'warning'
}
