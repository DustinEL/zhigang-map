export interface ApiResponse<T> {
  traceId: string
  code: string
  message: string
  data: T
  timestamp: string
}

export type RuntimeMode = 'REAL' | 'REAL_PENDING' | 'DEMO' | 'FROZEN_CACHE' | 'NO_DATA' | 'ERROR'

export type PublicRoleStatus = 'PUBLISHED' | 'BASELINE' | 'BUILDING'
export type VerificationEvidenceType = 'PROJECT_LINK' | 'CODE_DESCRIPTION' | 'TEST_RESULT' | 'DOCUMENT' | 'CERTIFICATE'
export type VerificationStatus = 'PENDING_VERIFICATION' | 'VERIFIED' | 'REJECTED'
export type VerificationReviewDecision = Exclude<VerificationStatus, 'PENDING_VERIFICATION'>

export interface PublicRoleOverview {
  id: number
  code: string
  name: string
  family: string
  emerging: boolean
  definition: string
  suitableBackground: string
  status: PublicRoleStatus
  coreSkills: string[]
  responsibilities: string[]
  jobLevel?: string
  techStack?: string[]
  roleVersion?: string
  evidenceLayer?: 'REAL_EVIDENCE' | 'BASELINE' | 'BUILDING'
  sampleCount?: number
  companyCount?: number
  updatedAt?: string
}

export interface GraphFilterParams {
  techStack?: string
  jobLevel?: string
  roleVersion?: string
  evidenceLayer?: string
}

export interface PublicOverview {
  updatedAt: string
  dataScope: string
  sampleCount: number
  companyCount: number
  roles: PublicRoleOverview[]
  topSkills: string[]
  disclaimer: string
}

export interface RuntimeStatus {
  dataMode: RuntimeMode
  operatingMode: string
  dynamicRecordCount: number
  publishedRealDocumentCount: number
  pendingCandidateSkillCount: number
  snapshotCode?: string
}

export interface ResultMetadata {
  dataMode: RuntimeMode
  graphRelease?: string
  timeWindow?: string
  sampleSize?: number
  independentCompanies?: number
  confidence?: number
  evidenceAvailable?: boolean
}

export interface SkillRequirement {
  skill: string
  category: string
  requirementType: string
  demandRate: number
  evidenceCount: number
  trend: string
}

export interface JobRole {
  id: number
  code: string
  name: string
  family: string
  level: string
  emerging: boolean
  definition: string
  salaryRange: string
  responsibilities: string[]
  requiredSkills: SkillRequirement[]
  bonusSkills: SkillRequirement[]
  scenarios: string[]
  releaseVersion: string
  updatedAt?: string
}

export interface DashboardSummary {
  sourceCount: number
  rawDocumentCount: number
  independentCompanyCount: number
  skillCount: number
  publishedRoleCount: number
  pendingReviewCount: number
  currentRelease: string
  operatingMode: string
  pipeline: Array<{ name: string; status: string; count: number; detail: string }>
  trends: Array<{ label: string; values: number[]; periods: string[] }>
}

export interface Evidence {
  documentId: number
  source: string
  company: string
  publishedAt: string
  quote: string
  startOffset: number
  endOffset: number
  timeWeight: number
  gateStatus: string
  citation_id?: string
}

export interface MarketCard {
  role: JobRole
  sampleSize: number
  independentCompanies: number
  demandHeat: number
  salaryQuartiles: number[]
  cityDistribution: Record<string, number>
  levelDistribution: Record<string, number>
  commonSkillCombinations: string[]
  evidence: Evidence[]
  confidence: number
  sampleScope: string
  metadata?: ResultMetadata
}

export interface RoleMarketTimeline {
  roleId: number
  periods: Array<{
    period: string
    postingCount: number
    newPostingCount: number
    salaryP25: number
    salaryMedian: number
    salaryP75: number
    demandIndex: number
    evidenceCount: number
    confidence: number
  }>
}

export interface RepositoryOverview {
  baseLibrary: { name: string; recordCount: number; status: string; detail: string }
  dynamicLibrary: { name: string; recordCount: number; status: string; detail: string }
  pipeline: Array<{ name: string; count: number; tone: string }>
  crawlerTasks: Array<{ name: string; schedule: string; parserVersion: string; recordCount: number; status: string }>
  promotion: { batchCode: string; candidates: number; promoted: number; quarantined: number; rejected: number; policyVersion: string }
  demoData: boolean
}

export interface EvaluationSummary {
  metrics: Array<{ code: string; name: string; value?: number | null; target: number; sampleCount: number; targetSamples: number; precision?: number | null; recall?: number | null; f1?: number | null; status: string; color: string; note: string }>
  datasets: Array<{ name: string; type: string; sampleCount: number; targetSamples: number; version: string; status: string }>
  errors: Array<{ type: string; count: number; share: string; example: string }>
  algorithmVersion: string
  promptVersion: string
  demoData: boolean
}

export interface EmergingCandidate {
  id: number
  name: string
  jobLevel?: string
  technologyStack?: string[]
  nearestRole: string
  emergenceScore: number
  uncertainty: string
  documentCount: number
  companyCount: number
  sourceCount: number
  clusterStability: number
  status: string
  responsibilities: string[]
  requiredSkills: string[]
  bonusSkills: string[]
  scenarios: string[]
  supportingEvidence: Evidence[]
  opposingEvidence: string[]
  metadata?: ResultMetadata
}

export interface EmergingDiscoveryRun {
  id: number
  runCode: string
  status: string
  policyVersion: string
  policyHash: string
  inputHash: string
  embeddingModel: string
  embeddingDimension?: number
  clusterModel?: string
  documentCount: number
  clusterCount: number
  candidateCount: number
  noiseCount: number
  errorCode?: string
  errorMessage?: string
  requestedBy: string
  startedAt?: string
  completedAt?: string
  createdAt: string
}

export interface EmergingDiscoveryCandidate extends EmergingCandidate {
  runId: number
  version: number
  nearestRoleSimilarity: number
  generationMode: string
  metrics: Record<string, number>
  creator?: string
  reviewer?: string
  updatedAt: string
}

export interface EmergingCandidateEvidence {
  id: number
  fieldName: string
  fieldValue: string
  documentId: number
  startOffset: number
  endOffset: number
  quote: string
  direction: string
  anchorValid: boolean
  source: string
  company: string
}

export interface EmergingCandidateRevision {
  id: number
  fromVersion: number
  toVersion: number
  before: Record<string, unknown>
  after: Record<string, unknown>
  reason: string
  evidenceIds: number[]
  editor: string
  createdAt: string
}

export interface EmergingCandidateDetail {
  candidate: EmergingDiscoveryCandidate
  evidence: EmergingCandidateEvidence[]
  revisions: EmergingCandidateRevision[]
}

export interface EmergingQueryEvidence {
  id: number
  key: string
  title: string
  source: string
  domain: string
  url: string
  publishedAt?: string
  excerpt: string
}

export interface EmergingQueryFieldEvidence {
  fieldName: string
  fieldValue: string
  evidenceId: number
  evidenceKey: string
}

export interface EmergingQueryDiscovery {
  id: number
  query: string
  normalizedQuery: string
  status: 'EXISTING_ROLE' | 'EMERGING_ROLE' | 'EVIDENCE_INSUFFICIENT' | 'WAITING_GENERATION' | 'OUT_OF_SCOPE'
  domain: string
  name: string
  definition: string
  responsibilities: string[]
  requiredSkills: string[]
  bonusSkills: string[]
  scenarios: string[]
  nearestRoleId?: number
  nearestRoleName?: string
  nearestRoleSimilarity: number
  noveltyScore: number
  evidenceCount: number
  sourceCount: number
  fieldEvidenceComplete: boolean
  webSearchUsed: boolean
  publishable: boolean
  modelName: string
  errorCode?: string
  errorMessage?: string
  publishedRoleId?: number
  publishedReleaseId?: number
  evidence: EmergingQueryEvidence[]
  fieldEvidence: EmergingQueryFieldEvidence[]
  fromCache: boolean
  cacheSimilarity: number
  matchedQuery?: string
  publishedAt?: string
  createdAt: string
  updatedAt: string
}

export interface EvolutionReport {
  roleId: number
  roleName: string
  baselineRelease: string
  currentRelease: string
  sampleControl: string
  conclusionLevel: string
  skills: Array<{
    skill: string
    changeType: string
    absoluteChange: number
    confidence: string
    points: Array<{ period: string; demandRate: number; companies: number; lowerBound: number; upperBound: number }>
  }>
  evidence: Evidence[]
  metadata?: ResultMetadata
}

export interface RoleEvolutionWindow {
  id: number
  type: 'BASELINE' | 'CURRENT'
  label: string
  startDate: string
  endDate: string
  documentCount: number
  companyCount: number
  sourceCount: number
  sourceNames: string[]
  cityScope: string
  levelScope: string
  sampleHash: string
}

export interface RoleEvolutionEvidence {
  id: number
  windowType: 'BASELINE' | 'CURRENT'
  documentId: number
  title: string
  company: string
  source: string
  sourceUrl: string
  publishedAt: string
  quote: string
  startOffset: number
  endOffset: number
  anchorValid: boolean
  direction: string
}

export interface RoleEvolutionChange {
  id: number
  skillId?: number
  skill: string
  changeType: 'NEW' | 'INCREASED' | 'DECREASED' | 'REMOVAL_CANDIDATE' | 'STABLE'
  confidenceLevel: 'FORMAL_CHANGE' | 'OBSERVATION_SIGNAL' | 'STABLE'
  absoluteChange: number
  differenceLowerBound: number
  differenceUpperBound: number
  sensitivityDirection: 'UP' | 'DOWN' | 'STABLE'
  consecutiveAbsentWindows: number
  publishable: boolean
  includedInDraft: boolean
  points: Array<{
    period: string
    documentCount: number
    companyCount: number
    demandRate: number
    lowerBound: number
    upperBound: number
  }>
  evidence: RoleEvolutionEvidence[]
}

export interface RoleEvolutionRun {
  id: number
  runCode: string
  roleId: number
  roleCode: string
  roleName: string
  policyVersion: string
  policyHash: string
  inputHash: string
  status: 'DRAFT_READY' | 'INSUFFICIENT_DATA' | 'PUBLISHED'
  conclusionLevel: 'FORMAL_CHANGES' | 'SIGNAL_ONLY' | 'STABLE' | 'INSUFFICIENT_TIME_DATA'
  sampleControl: string
  statisticsMethod: string
  version: number
  draftNote: string
  publishedReleaseId?: number
  publishedReleaseVersion?: string
  windows: RoleEvolutionWindow[]
  changes: RoleEvolutionChange[]
  createdAt: string
  updatedAt: string
  publishedAt?: string
}

export interface QualityReport {
  snapshotId: string
  snapshotName: string
  rawCount: number
  effectiveCount: number
  duplicateClusterCount: number
  publishable: boolean
  policyVersion: string
  metrics: Array<{ code: string; name: string; value: number; threshold: number; unit: string; status: string; explanation: string }>
  issues: Array<{ type: string; severity: string; documentName: string; detail: string; action: string }>
  generatedAt: string
}

export interface ExtractionResult {
  title: string
  salary: string
  level: string
  facts: Array<{ type: string; rawValue: string; normalizedValue: string; requirementType: string; documentId: number; startOffset: number; endOffset: number; evidenceQuote: string; confidence: number; gateStatus: string }>
  responsibilities: string[]
  scenarios: string[]
  evidence: Array<{ type: string; normalizedValue: string; documentId: number; startOffset: number; endOffset: number; quote: string; confidence: number; extractionMethod: string; gateStatus: string }>
  warnings: string[]
  policyVersion: string
  modelMode: string
  conclusionStatus: string
  metrics: { evidenceCoverageRate: number; citationConsistencyRate: number; unsupportedRefusalRate: number; falseAcceptanceRate: number; promptInjectionBlockRate: number; conclusionCount: number; evidenceBackedCount: number; rejectedCount: number }
}

export interface GraphView {
  releaseVersion: string
  viewType: string
  nodes: Array<{ id: string; label: string; type: string; group: string; evidenceCount: number; status: string }>
  edges: Array<{ id: string; source: string; target: string; relation: string; weight: number; status: string }>
  metadata?: ResultMetadata
}

export interface ResumeProfile {
  id: number
  name: string
  education: string
  major: string
  graduationYear: number
  weeklyHours: number
  abilities: Array<{ skill: string; level: number; levelName: string; quote: string; sourceType: string; confidence: number; verificationStatus?: 'PENDING_VERIFICATION' | 'VERIFIED' | 'REJECTED' }>
  projects: string[]
  certificates: string[]
  status: string
  metadata?: ResultMetadata
  facts?: Array<{ factType: string; value: string; normalizedValue: string; startOffset: number; endOffset: number; evidenceQuote: string; evidenceLevel: number; sourceType: string; verificationStatus: 'PENDING_VERIFICATION' | 'VERIFIED' | 'REJECTED'; confidence: number }>
  parsingMode?: 'TEXT' | 'APACHE_TIKA' | 'PADDLE_OCR' | 'MANUAL'
  redactedFields?: string[]
}

export interface MatchReport {
  id: number
  resumeId: number
  roleId: number
  roleName: string
  graphRelease: string
  hardGates: Array<{ name: string; expected: string; actual: string; status: string; explanation: string }>
  readiness: number
  growthPotential: number
  applicationState: string
  dimensions: Array<{ name: string; weight: number; rawScore: number; weightedScore: number; explanation: string }>
  skillDetails: Array<{
    skill: string
    matchType: string
    evidenceLevel: number
    requiredLevel: number
    score: number
    resumeEvidence: string
    gap: string
    remedy: string
    roleEvidenceLevel?: number
    personalEvidenceLevel?: number
    alignmentState?: 'SATISFIED' | 'EVIDENCE_INSUFFICIENT' | 'MISSING' | 'BONUS'
    roleEvidence?: Evidence[]
    personalEvidence?: Array<{ sourceType: string; quote: string; level: number }>
    gapReason?: string
    nextAction?: string
    semanticSimilarity?: number
    manualConfirmed?: boolean
  }>
  adjacentRoles: Array<{
    roleId: number
    roleName: string
    readiness: number
    keyGaps: number
    transferDistance: number
    confidence: number
    reusableSkills?: string[]
    missingSkills?: string[]
    recommendedTransitionRole?: string
  }>
  createdAt: string
  metadata?: ResultMetadata
  scoringPolicyVersion?: string
  calibrationDatasetHash?: string
  scoreConfidence?: number
  scoreLimitReason?: string
}

export interface LearningPlan {
  id: number
  matchRunId: number
  targetRole: string
  weeklyHours: number
  estimatedWeeks: number
  graphRelease: string
  items: Array<{ order: number; stage: string; skill: string; reason: string; prerequisites: string[]; resources: string[]; practicalTask: string; acceptanceCriteria: string; estimatedHours: number; weekStart?: number; weekEnd?: number; evidenceDeliverable?: string }>
  metadata?: ResultMetadata
}

export interface EvidenceExplanation {
  questionType: 'SKILL_REQUIREMENT' | 'ROLE_EVOLUTION' | 'LEARNING_ORDER'
  explanation: string
  evidence: Evidence[]
  refused: boolean
  metadata: ResultMetadata
}

export interface VerificationSubmission {
  id: number
  learningPlanId: number
  itemOrder: number
  skill: string
  evidenceType: VerificationEvidenceType
  evidenceUrl?: string
  evidenceText: string
  status: VerificationStatus
  createdAt: string
  reviewReason?: string
  validationChecks?: string[]
  verifiedAt?: string
}

export interface VerificationSubmissionPage {
  items: VerificationSubmission[]
  page: number
  size: number
  total: number
}

export interface DiagnosisComparison {
  beforeMatchRunId: number
  afterMatchRunId: number
  beforeReadiness: number
  afterReadiness: number
  improvement: number
  upgradedSkills: string[]
  createdAt: string
}

export interface ChangeProposal {
  id: number
  title: string
  type: string
  status: string
  creator: string
  reviewer?: string
  reason: string
  changes: string[]
  createdAt: string
  reviewedAt?: string
}

export interface GraphRelease {
  id: number
  version: string
  status: string
  snapshotId: string
  policyVersion: string
  publisher: string
  changes: string[]
  publishedAt: string
}

export interface ReleaseDiff {
  fromVersion: string
  toVersion: string
  added: string[]
  changed: string[]
  weakened: string[]
  removed: string[]
}

export interface ProcessingRun {
  id: string
  snapshotId: string
  status: string
  steps: Array<{
    step: string
    status: string
    inputCount: number
    outputCount: number
    durationMs: number
    errorCode?: string
    error?: string
    attempt: number
    startedAt?: string
    completedAt?: string
  }>
  progress: number
  errorCode?: string
  error?: string
  createdAt: string
  updatedAt: string
}

export interface CurrentReleaseSummary {
  version: string
  dataScope: string
  documentCount: number
  policyVersion: string
  updatedAt: string
}

export interface ImportResult {
  id: string
  sourceType: string
  sourceName: string
  acceptedCount: number
  skippedCount: number
  status: string
  snapshotId?: string
  createdAt: string
}

export interface FeedScanResult {
  snapshotId?: string
  acceptedCount: number
  skippedCount: number
  indexedEvidenceCount?: number
  httpStatus: number
  status: string
  startedAt: string
  completedAt: string
}

export interface FeedStatus {
  enabled: boolean
  feedUrl: string
  sourceName: string
  sourceType: string
  licenseStatus: string
  licenseNote: string
  scanDelayMs: number
  lastAttemptAt?: string
  lastSuccessAt?: string
  lastHttpStatus?: number
  lastFailure?: string
  lastResult?: FeedScanResult
}

export interface EvaluationStatus {
  code: string
  name: string
  targetSamples: number
  frozenSamples: number
  targetAccuracy: number
  status: string
}

export interface StructuredImportResult {
  id: number
  runCode: string
  sourceFileName: string
  sourceFileHash: string
  fetchedCount: number
  acceptedCount: number
  duplicateCount: number
  quarantinedCount: number
  autoApprovedCount: number
  sampledCount: number
  exceptionCount: number
  waitingSkillCount: number
  status: string
  policyVersion: string
  createdAt: string
}

export interface SourceLedgerItem {
  sourceId: string
  sourceName: string
  sourceTier: string
  timeScope: 'CURRENT' | 'HISTORICAL' | 'UNKNOWN_TIME' | string
  adapter: 'MANUAL_BATCH' | 'STRUCTURED_DATASET' | 'OFFICIAL_ATS' | 'PUBLIC_EMPLOYMENT' | 'ENTERPRISE_CAREERS' | string
  batchCount: number
  collectedCount: number
  duplicateCount: number
  acceptedCount: number
  exceptionCount: number
  updatedAt: string
}

export interface DynamicRecordSummary {
  id: number
  importId: number
  sourceRecordKey: string
  title: string
  company: string
  city: string
  contentHash: string
  dataMode: string
  status: string
  qualityScore: number
  skillCount: number
  candidateSkillCount: number
  issueCount: number
  reviewDecision: string
  riskLevel: string
  reviewRoute: string
  reviewReasons: string[]
  reviewSource: string
  triagePolicyVersion?: string
  triagedAt?: string
  collectedAt: string
}

export interface DynamicFact {
  id: number
  factType: string
  ontologyStatus: string
  canonicalValue: string
  suggestedCategory?: string
  rawValue: string
  requirementType?: string
  startOffset: number
  endOffset: number
  evidenceQuote: string
  confidence: number
  offsetVerified: boolean
  reviewDecision: string
  correctedValue?: string
  reviewNote?: string
}

export interface DynamicRecordDetail {
  summary: DynamicRecordSummary
  sourceName: string
  licenseStatus: string
  sourceUrl: string
  canonicalUrl: string
  salary: string
  experienceRequirement: string
  educationRequirement: string
  publishedAt?: string
  rawText: string
  facts: DynamicFact[]
  issues: Array<{ id: number; type: string; severity: string; detail: string; blocking: boolean }>
  reviews: Array<{ id: number; decision: string; reviewer: string; reason: string; policyVersion: string; reviewSource: string; reviewedAt: string }>
}

export interface DynamicRecordPage {
  items: DynamicRecordSummary[]
  page: number
  size: number
  total: number
}

export interface CandidateSkillSummary {
  skill: string
  suggestedCategory?: string
  factCount: number
  companyCount: number
  reviewDecision: string
  correctedValue?: string
  decisionSource: string
  requirementTypes: string[]
  recommendedDecision: string
  recommendedTarget?: string
  recommendationReasons: string[]
  evidenceSamples: CandidateSkillEvidence[]
}

export interface CandidateSkillEvidence {
  recordId: number
  title: string
  company: string
  quote: string
  requirementType?: string
  confidence: number
  offsetVerified: boolean
}

export interface ReviewQueueSummary {
  importId?: number
  totalRecords: number
  autoApprovedCount: number
  sampledCount: number
  exceptionCount: number
  waitingSkillCount: number
  humanApprovedCount: number
  blockedCount: number
  pendingCandidateSkillCount: number
  estimatedRemainingActions: number
}

export interface ProfessionalSession {
  authenticated: boolean
  username?: string
  loginRequired?: boolean
  roles?: string[]
  capabilities?: string[]
}

export interface PromotionBatch {
  id: number
  batchCode: string
  snapshotCode: string
  policyVersion: string
  status: string
  candidateCount: number
  promotedCount: number
  rejectedCount: number
  requestedBy: string
  approvedBy?: string
  reviewNote?: string
  createdAt: string
  completedAt?: string
  items: Array<{ recordId: number; title: string; company: string; decision: string; targetDocumentId?: number; reason?: string }>
}

export interface RealSnapshot {
  snapshotCode: string
  dataMode: string
  usageScope: string
  documentCount: number
  independentCompanyCount: number
  publishedTimeCoverage: boolean
  roles: Array<{
    roleCode: string
    roleName: string
    sampleSize: number
    independentCompanies: number
    confidenceLabel: string
    responsibilities: Array<{
      responsibility: string
      documentCount: number
      companyCount: number
      evidence: RealEvidence[]
    }>
    requiredSkills: RealSkillMetric[]
    bonusSkills: RealSkillMetric[]
    mentionedSkills: RealSkillMetric[]
    opposingEvidence: RealEvidence[]
  }>
}

export interface RealSkillMetric {
  skill: string
  rate: number
  documentCount: number
  companyCount: number
  evidence: RealEvidence[]
}

export interface RealEvidence {
  documentId: number
  title: string
  company: string
  quote: string
  startOffset: number
  endOffset: number
  sourceUrl: string
}

export interface RoleSkillEvidence {
  roleId: number
  roleName: string
  skill: string
  requirementType: string
  demandRate: number
  documentCount: number
  companyCount: number
  snapshotCode: string
  dataScope: string
  evidence: RealEvidence[]
}
