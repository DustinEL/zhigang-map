package com.zhigang.platform.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhigang.platform.common.CitationIds;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

public final class PlatformModels {
    private PlatformModels() {
    }

    public record SkillDefinition(
            long id,
            String name,
            String category,
            List<String> aliases,
            List<String> prerequisites
    ) {
    }

    public record SkillRequirement(
            String skill,
            String category,
            String requirementType,
            double demandRate,
            int evidenceCount,
            String trend
    ) {
    }

    public record JobRole(
            long id,
            String code,
            String name,
            String family,
            String level,
            boolean emerging,
            String definition,
            String salaryRange,
            List<String> responsibilities,
            List<SkillRequirement> requiredSkills,
            List<SkillRequirement> bonusSkills,
            List<String> scenarios,
            String releaseVersion,
            OffsetDateTime updatedAt
    ) {
    }

    public record PublicRoleOverview(
            long id,
            String code,
            String name,
            String family,
            boolean emerging,
            String definition,
            String suitableBackground,
            String status,
            List<String> coreSkills,
            List<String> responsibilities,
            String jobLevel,
            List<String> techStack,
            String roleVersion,
            String evidenceLayer,
            Integer sampleCount,
            Integer companyCount,
            OffsetDateTime updatedAt
    ) {
    }

    public record PublicOverview(
            OffsetDateTime updatedAt,
            String dataScope,
            int sampleCount,
            int companyCount,
            List<PublicRoleOverview> roles,
            List<String> topSkills,
            String disclaimer
    ) {
    }

    public record RawDocument(
            long id,
            String sourceType,
            String sourceName,
            String licenseStatus,
            String company,
            String city,
            String industry,
            String level,
            String jobTitle,
            String salary,
            String content,
            OffsetDateTime publishedAt,
            String contentHash,
            boolean demoData
    ) {
    }

    public record LearningResource(
            long id,
            String name,
            String type,
            String provider,
            List<String> skills,
            boolean reviewed
    ) {
    }

    public record ResultMetadata(
            String dataMode,
            String graphRelease,
            String timeWindow,
            Integer sampleSize,
            Integer independentCompanies,
            Double confidence,
            Boolean evidenceAvailable
    ) {
    }

    public record DashboardSummary(
            int sourceCount,
            int rawDocumentCount,
            int independentCompanyCount,
            int skillCount,
            int publishedRoleCount,
            int pendingReviewCount,
            String currentRelease,
            String operatingMode,
            List<PipelineStep> pipeline,
            List<MetricTrend> trends
    ) {
    }

    public record PipelineStep(String name, String status, int count, String detail) {
    }

    public record MetricTrend(String label, List<Double> values, List<String> periods) {
    }

    public record QualityMetric(String code, String name, double value, double threshold, String unit, String status, String explanation) {
    }

    public record QualityIssue(String type, String severity, String documentName, String detail, String action) {
    }

    public record QualityReport(
            String snapshotId,
            String snapshotName,
            int rawCount,
            int effectiveCount,
            int duplicateClusterCount,
            boolean publishable,
            String policyVersion,
            List<QualityMetric> metrics,
            List<QualityIssue> issues,
            OffsetDateTime generatedAt
    ) {
    }

    public record Evidence(
            long documentId,
            String source,
            String company,
            OffsetDateTime publishedAt,
            String quote,
            int startOffset,
            int endOffset,
            double timeWeight,
            String gateStatus
    ) {
        @JsonProperty("citation_id")
        public String citationId() {
            return CitationIds.fromAnchor(documentId, startOffset, endOffset);
        }
    }

    public record RoleSkillEvidence(
            long roleId,
            String roleName,
            String skill,
            String requirementType,
            double demandRate,
            int documentCount,
            int companyCount,
            String snapshotCode,
            String dataScope,
            List<RoleSkillEvidenceItem> evidence
    ) {
    }

    public record RoleSkillEvidenceItem(
            long documentId,
            String title,
            String company,
            String quote,
            int startOffset,
            int endOffset,
            String sourceUrl
    ) {
        @JsonProperty("citation_id")
        public String citationId() {
            return CitationIds.fromAnchor(documentId, startOffset, endOffset);
        }
    }

    public record MarketCard(
            JobRole role,
            int sampleSize,
            int independentCompanies,
            double demandHeat,
            List<Integer> salaryQuartiles,
            Map<String, Double> cityDistribution,
            Map<String, Double> levelDistribution,
            List<String> commonSkillCombinations,
            List<Evidence> evidence,
            double confidence,
            String sampleScope,
            ResultMetadata metadata
    ) {
        public MarketCard(JobRole role, int sampleSize, int independentCompanies, double demandHeat,
                          List<Integer> salaryQuartiles, Map<String, Double> cityDistribution,
                          Map<String, Double> levelDistribution, List<String> commonSkillCombinations,
                          List<Evidence> evidence, double confidence, String sampleScope) {
            this(role, sampleSize, independentCompanies, demandHeat, salaryQuartiles, cityDistribution,
                    levelDistribution, commonSkillCombinations, evidence, confidence, sampleScope, null);
        }
    }

    public record RoleMarketTimeline(long roleId, List<RoleMarketPeriod> periods) {
    }

    public record RoleMarketPeriod(
            String period,
            int postingCount,
            int newPostingCount,
            double salaryP25,
            double salaryMedian,
            double salaryP75,
            double demandIndex,
            int evidenceCount,
            double confidence
    ) {
    }

    public record RepositoryOverview(
            RepositoryLibrary baseLibrary,
            RepositoryLibrary dynamicLibrary,
            List<RepositoryPipelineStep> pipeline,
            List<CrawlerTaskSummary> crawlerTasks,
            PromotionSummary promotion,
            boolean demoData
    ) {
    }

    public record RepositoryLibrary(String name, int recordCount, String status, String detail) {
    }

    public record RepositoryPipelineStep(String name, int count, String tone) {
    }

    public record CrawlerTaskSummary(String name, String schedule, String parserVersion, int recordCount, String status) {
    }

    public record PromotionSummary(String batchCode, int candidates, int promoted, int quarantined,
                                   int rejected, String policyVersion) {
    }

    public record EvaluationSummary(
            List<EvaluationMetric> metrics,
            List<EvaluationDatasetSummary> datasets,
            List<EvaluationErrorSummary> errors,
            String algorithmVersion,
            String promptVersion,
            boolean demoData
    ) {
    }

    public record EvaluationMetric(
            String code,
            String name,
            Double value,
            double target,
            int sampleCount,
            int targetSamples,
            Double precision,
            Double recall,
            Double f1,
            String status,
            String color,
            String note
    ) {
    }

    public record EvaluationDatasetSummary(String name, String type, int sampleCount, int targetSamples,
                                           String version, String status) {
    }

    public record EvaluationErrorSummary(String type, int count, String share, String example) {
    }

    public record EmergingCandidate(
            long id,
            String name,
            String nearestRole,
            double emergenceScore,
            String uncertainty,
            int documentCount,
            int companyCount,
            int sourceCount,
            double clusterStability,
            String status,
            List<String> responsibilities,
            List<String> requiredSkills,
            List<String> bonusSkills,
            List<String> scenarios,
            List<Evidence> supportingEvidence,
            List<String> opposingEvidence,
            ResultMetadata metadata
    ) {
        public EmergingCandidate(long id, String name, String nearestRole, double emergenceScore, String uncertainty,
                                 int documentCount, int companyCount, int sourceCount, double clusterStability,
                                 String status, List<String> responsibilities, List<String> requiredSkills,
                                 List<String> bonusSkills, List<String> scenarios, List<Evidence> supportingEvidence,
                                 List<String> opposingEvidence) {
            this(id, name, nearestRole, emergenceScore, uncertainty, documentCount, companyCount, sourceCount,
                    clusterStability, status, responsibilities, requiredSkills, bonusSkills, scenarios,
                    supportingEvidence, opposingEvidence, null);
        }
    }

    public record EvolutionPoint(String period, double demandRate, int companies, double lowerBound, double upperBound) {
    }

    public record SkillEvolution(String skill, String changeType, double absoluteChange, String confidence, List<EvolutionPoint> points) {
    }

    public record EvolutionReport(
            long roleId,
            String roleName,
            String baselineRelease,
            String currentRelease,
            String sampleControl,
            List<SkillEvolution> skills,
            List<Evidence> evidence,
            String conclusionLevel,
            ResultMetadata metadata
    ) {
        public EvolutionReport(long roleId, String roleName, String baselineRelease, String currentRelease,
                               String sampleControl, List<SkillEvolution> skills, List<Evidence> evidence,
                               String conclusionLevel) {
            this(roleId, roleName, baselineRelease, currentRelease, sampleControl, skills, evidence, conclusionLevel, null);
        }
    }

    public record GraphNode(String id, String label, String type, String group, int evidenceCount, String status) {
    }

    public record GraphEdge(String id, String source, String target, String relation, double weight, String status) {
    }

    public record GraphView(String releaseVersion, String viewType, List<GraphNode> nodes, List<GraphEdge> edges,
                            ResultMetadata metadata) {
        public GraphView(String releaseVersion, String viewType, List<GraphNode> nodes, List<GraphEdge> edges) {
            this(releaseVersion, viewType, nodes, edges, null);
        }
    }

    public record ExtractedFact(
            String type,
            String rawValue,
            String normalizedValue,
            String requirementType,
            long documentId,
            int startOffset,
            int endOffset,
            String evidenceQuote,
            double confidence,
            String gateStatus
    ) {
        public ExtractedFact(String type, String rawValue, String normalizedValue, String requirementType,
                             int startOffset, int endOffset, double confidence, String gateStatus) {
            this(type, rawValue, normalizedValue, requirementType, 0, startOffset, endOffset,
                    rawValue, confidence, gateStatus);
        }

        @JsonProperty("citation_id")
        public String citationId() {
            return CitationIds.fromAnchor(documentId, startOffset, endOffset);
        }
    }

    public record ExtractedEvidence(
            String type,
            String normalizedValue,
            long documentId,
            int startOffset,
            int endOffset,
            String quote,
            double confidence,
            String extractionMethod,
            String gateStatus
    ) {
        @JsonProperty("citation_id")
        public String citationId() {
            return CitationIds.fromAnchor(documentId, startOffset, endOffset);
        }
    }

    public record ExtractionMetrics(
            double evidenceCoverageRate,
            double citationConsistencyRate,
            double unsupportedRefusalRate,
            double falseAcceptanceRate,
            double promptInjectionBlockRate,
            int conclusionCount,
            int evidenceBackedCount,
            int rejectedCount
    ) {
    }

    public record ExtractionResult(
            String title,
            String salary,
            String level,
            List<ExtractedFact> facts,
            List<String> responsibilities,
            List<String> scenarios,
            List<ExtractedEvidence> evidence,
            List<String> warnings,
            String policyVersion,
            String modelMode,
            String conclusionStatus,
            ExtractionMetrics metrics
    ) {
    }

    public record AbilityEvidence(String skill, int level, String levelName, String quote, String sourceType,
                                  double confidence, String verificationStatus) {
        public AbilityEvidence(String skill, int level, String levelName, String quote, String sourceType, double confidence) {
            this(skill, level, levelName, quote, sourceType, confidence, "PENDING_VERIFICATION");
        }
    }

    public record ResumeFact(String factType, String value, String normalizedValue, int startOffset, int endOffset,
                             String evidenceQuote, int evidenceLevel, String sourceType,
                             String verificationStatus, double confidence) {
    }

    public record ResumeProfile(
            long id,
            String name,
            String education,
            String major,
            int graduationYear,
            int weeklyHours,
            List<AbilityEvidence> abilities,
            List<String> projects,
            List<String> certificates,
            String status,
            ResultMetadata metadata,
            List<ResumeFact> facts,
            String parsingMode,
            List<String> redactedFields
    ) {
        public ResumeProfile(long id, String name, String education, String major, int graduationYear, int weeklyHours,
                             List<AbilityEvidence> abilities, List<String> projects, List<String> certificates, String status) {
            this(id, name, education, major, graduationYear, weeklyHours, abilities, projects, certificates, status,
                    null, List.of(), "MANUAL", List.of());
        }

        public ResumeProfile(long id, String name, String education, String major, int graduationYear, int weeklyHours,
                             List<AbilityEvidence> abilities, List<String> projects, List<String> certificates, String status,
                             ResultMetadata metadata) {
            this(id, name, education, major, graduationYear, weeklyHours, abilities, projects, certificates, status,
                    metadata, List.of(), "MANUAL", List.of());
        }
    }

    public record HardGate(String name, String expected, String actual, String status, String explanation) {
    }

    public record MatchDimension(String name, double weight, double rawScore, double weightedScore, String explanation) {
    }

    public record MatchSkillDetail(
            String skill,
            String matchType,
            int evidenceLevel,
            int requiredLevel,
            double score,
            String resumeEvidence,
            String gap,
            String remedy,
            int roleEvidenceLevel,
            int personalEvidenceLevel,
            String alignmentState,
            List<Evidence> roleEvidence,
            List<PersonalEvidence> personalEvidence,
            String gapReason,
            String nextAction,
            double semanticSimilarity,
            boolean manualConfirmed
    ) {
        public MatchSkillDetail(String skill, String matchType, int evidenceLevel, int requiredLevel, double score,
                                String resumeEvidence, String gap, String remedy, int roleEvidenceLevel,
                                int personalEvidenceLevel, String alignmentState, List<Evidence> roleEvidence,
                                List<PersonalEvidence> personalEvidence, String gapReason, String nextAction) {
            this(skill, matchType, evidenceLevel, requiredLevel, score, resumeEvidence, gap, remedy,
                    roleEvidenceLevel, personalEvidenceLevel, alignmentState, roleEvidence, personalEvidence,
                    gapReason, nextAction, "SEMANTIC_CONFIRMED".equals(matchType) ? 0.82 : 1.0, true);
        }

        public MatchSkillDetail(String skill, String matchType, int evidenceLevel, int requiredLevel, double score,
                                String resumeEvidence, String gap, String remedy) {
            this(skill, matchType, evidenceLevel, requiredLevel, score, resumeEvidence, gap, remedy,
                    requiredLevel, evidenceLevel, evidenceLevel >= requiredLevel ? "SATISFIED" : evidenceLevel > 0 ? "EVIDENCE_INSUFFICIENT" : "MISSING",
                    List.of(), resumeEvidence == null || resumeEvidence.isBlank() ? List.of()
                            : List.of(new PersonalEvidence("RESUME", resumeEvidence, evidenceLevel)), gap, remedy);
        }
    }

    public record PersonalEvidence(String sourceType, String quote, int level) {
    }

    public record AdjacentRole(long roleId, String roleName, double readiness, int keyGaps, double transferDistance, double confidence,
                               List<String> reusableSkills, List<String> missingSkills, String recommendedTransitionRole) {
        public AdjacentRole(long roleId, String roleName, double readiness, int keyGaps, double transferDistance, double confidence) {
            this(roleId, roleName, readiness, keyGaps, transferDistance, confidence, List.of(), List.of(), "");
        }
    }

    public record MatchReport(
            long id,
            long resumeId,
            long roleId,
            String roleName,
            String graphRelease,
            List<HardGate> hardGates,
            double readiness,
            double growthPotential,
            String applicationState,
            List<MatchDimension> dimensions,
            List<MatchSkillDetail> skillDetails,
            List<AdjacentRole> adjacentRoles,
            OffsetDateTime createdAt,
            ResultMetadata metadata,
            String scoringPolicyVersion,
            String calibrationDatasetHash,
            double scoreConfidence,
            String scoreLimitReason
    ) {
        public MatchReport(long id, long resumeId, long roleId, String roleName, String graphRelease, List<HardGate> hardGates,
                           double readiness, double growthPotential, String applicationState, List<MatchDimension> dimensions,
                           List<MatchSkillDetail> skillDetails, List<AdjacentRole> adjacentRoles, OffsetDateTime createdAt,
                           ResultMetadata metadata) {
            this(id, resumeId, roleId, roleName, graphRelease, hardGates, readiness, growthPotential, applicationState,
                    dimensions, skillDetails, adjacentRoles, createdAt, metadata, "LEGACY", "", 0, "");
        }

        public MatchReport(long id, long resumeId, long roleId, String roleName, String graphRelease, List<HardGate> hardGates,
                           double readiness, double growthPotential, String applicationState, List<MatchDimension> dimensions,
                           List<MatchSkillDetail> skillDetails, List<AdjacentRole> adjacentRoles, OffsetDateTime createdAt) {
            this(id, resumeId, roleId, roleName, graphRelease, hardGates, readiness, growthPotential, applicationState,
                    dimensions, skillDetails, adjacentRoles, createdAt, null);
        }
    }

    public record LearningPlanItem(
            int order,
            String stage,
            String skill,
            String reason,
            List<String> prerequisites,
            List<String> resources,
            String practicalTask,
            String acceptanceCriteria,
            int estimatedHours,
            int weekStart,
            int weekEnd,
            String evidenceDeliverable
    ) {
        public LearningPlanItem(int order, String stage, String skill, String reason, List<String> prerequisites,
                                List<String> resources, String practicalTask, String acceptanceCriteria, int estimatedHours) {
            this(order, stage, skill, reason, prerequisites, resources, practicalTask, acceptanceCriteria, estimatedHours,
                    order, order, "项目链接、实现说明与测试结果");
        }
    }

    public record LearningPlan(
            long id,
            long matchRunId,
            String targetRole,
            int weeklyHours,
            int estimatedWeeks,
            List<LearningPlanItem> items,
            String graphRelease,
            ResultMetadata metadata
    ) {
        public LearningPlan(long id, long matchRunId, String targetRole, int weeklyHours, int estimatedWeeks,
                            List<LearningPlanItem> items, String graphRelease) {
            this(id, matchRunId, targetRole, weeklyHours, estimatedWeeks, items, graphRelease, null);
        }
    }

    public record VerificationSubmission(
            long id,
            long learningPlanId,
            int itemOrder,
            String skill,
            String evidenceType,
            String evidenceUrl,
            String evidenceText,
            String status,
            OffsetDateTime createdAt,
            String reviewReason,
            List<String> validationChecks,
            OffsetDateTime verifiedAt
    ) {
        public VerificationSubmission(long id, long learningPlanId, int itemOrder, String skill, String evidenceType,
                                      String evidenceUrl, String evidenceText, String status, OffsetDateTime createdAt) {
            this(id, learningPlanId, itemOrder, skill, evidenceType, evidenceUrl, evidenceText, status, createdAt,
                    "", List.of(), "VERIFIED".equals(status) ? createdAt : null);
        }
    }

    public record VerificationSubmissionPage(
            List<VerificationSubmission> items,
            int page,
            int size,
            long total
    ) {
    }

    public record DiagnosisComparison(
            long beforeMatchRunId,
            long afterMatchRunId,
            double beforeReadiness,
            double afterReadiness,
            double improvement,
            List<String> upgradedSkills,
            OffsetDateTime createdAt
    ) {
    }

    public record EvidenceExplanation(
            String questionType,
            String explanation,
            List<Evidence> evidence,
            boolean refused,
            ResultMetadata metadata
    ) {
    }

    public record ChangeProposal(
            long id,
            String title,
            String type,
            String status,
            String creator,
            String reviewer,
            String reason,
            List<String> changes,
            OffsetDateTime createdAt,
            OffsetDateTime reviewedAt
    ) {
    }

    public record GraphRelease(
            long id,
            String version,
            String status,
            String snapshotId,
            String policyVersion,
            String publisher,
            List<String> changes,
            OffsetDateTime publishedAt
    ) {
    }

    public record ReleaseDiff(String fromVersion, String toVersion, List<String> added, List<String> changed, List<String> weakened, List<String> removed) {
    }
}
