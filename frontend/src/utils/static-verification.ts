import type { VerificationEvidenceType, VerificationSubmission } from '../types/models'

export interface PendingVerificationInput {
  id: number
  learningPlanId: number
  itemOrder: number
  skill: string
  evidenceType: VerificationEvidenceType
  evidenceText: string
  evidenceUrl?: string
  createdAt?: string
}

export function createPendingVerificationSubmission(input: PendingVerificationInput): VerificationSubmission {
  return {
    ...input,
    status: 'PENDING_VERIFICATION',
    createdAt: input.createdAt ?? new Date().toISOString(),
    reviewReason: '证据已进入待验证状态；审核通过前不会改变能力等级或准备度。',
    validationChecks: ['PENDING_PROFESSIONAL_REVIEW']
  }
}
