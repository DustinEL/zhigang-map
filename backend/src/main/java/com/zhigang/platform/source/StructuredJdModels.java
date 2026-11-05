package com.zhigang.platform.source;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class StructuredJdModels {
    private StructuredJdModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StructuredJdBatch(
            String schemaVersion,
            OffsetDateTime generatedAt,
            String batchKey,
            SourceWorkbook sourceWorkbook,
            ProcessingPolicy processingPolicy,
            List<StructuredJdDocument> documents
    ) {
        public StructuredJdBatch(String schemaVersion, OffsetDateTime generatedAt,
                                 SourceWorkbook sourceWorkbook, ProcessingPolicy processingPolicy,
                                 List<StructuredJdDocument> documents) {
            this(schemaVersion, generatedAt,
                    sourceWorkbook == null ? null : "legacy-" + sourceWorkbook.sha256().substring(0, 12),
                    sourceWorkbook, processingPolicy, documents);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceWorkbook(String fileName, String sha256, int recordCount, int rawTextCharacterCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcessingPolicy(
            String version,
            String method,
            String publishedAtPolicy,
            String sourceLicenseStatus,
            String statisticsEligibility
    ) {
        public ProcessingPolicy(String version, String method) {
            this(version, method, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StructuredJdDocument(
            String sourceRecordKey,
            String jobTitle,
            String company,
            String city,
            Salary salary,
            String experienceRequirement,
            String educationRequirement,
            OffsetDateTime publishedAt,
            LocalDate collectedAt,
            String timeScope,
            OffsetDateTime observedAt,
            Source source,
            String rawText,
            int rawTextLength,
            String contentHash,
            String dataMode,
            String processingStatus,
            List<String> statusReasons,
            Facts facts
    ) {
        public StructuredJdDocument(
                String sourceRecordKey, String jobTitle, String company, String city, Salary salary,
                String experienceRequirement, String educationRequirement, OffsetDateTime publishedAt,
                LocalDate collectedAt, Source source, String rawText, int rawTextLength, String contentHash,
                String dataMode, String processingStatus, List<String> statusReasons, Facts facts
        ) {
            this(sourceRecordKey, jobTitle, company, city, salary, experienceRequirement, educationRequirement,
                    publishedAt, collectedAt, "CURRENT",
                    collectedAt == null ? null : collectedAt.atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(8)),
                    source, rawText, rawTextLength, contentHash, dataMode, processingStatus, statusReasons, facts);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Salary(
            String raw,
            BigDecimal minMonthly,
            BigDecimal maxMonthly,
            BigDecimal salaryMonths,
            String currency
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(
            String sourceId,
            String name,
            String sourceTier,
            String adapter,
            String sourceType,
            String licenseStatus,
            String licenseNote,
            String originalUrl,
            String canonicalUrl
    ) {
        public Source(String name, String sourceType, String licenseStatus,
                      String originalUrl, String canonicalUrl) {
            this("legacy-manual-source", name, "TIER_3", "MANUAL_BATCH", sourceType, licenseStatus,
                    "由兼容构造器提供；正式导入必须提交明确许可说明。", originalUrl, canonicalUrl);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Facts(List<StructuredSkillFact> skills, List<StructuredResponsibilityFact> responsibilities) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StructuredSkillFact(
            String ontologyStatus,
            String canonicalSkill,
            String suggestedCategory,
            String rawValue,
            String requirementType,
            int startOffset,
            int endOffset,
            String evidenceQuote,
            double confidence,
            boolean offsetVerified
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StructuredResponsibilityFact(
            String rawValue,
            String normalizedValue,
            int startOffset,
            int endOffset,
            String evidenceQuote,
            double confidence,
            boolean offsetVerified
    ) {
    }

    public record QualityIssueDraft(String type, String severity, String detail, boolean blocking) {
    }

    public record ValidatedDocument(
            StructuredJdDocument document,
            String status,
            double qualityScore,
            List<QualityIssueDraft> issues
    ) {
    }

    public record StructuredImportResult(
            long id,
            String runCode,
            String sourceFileName,
            String sourceFileHash,
            int fetchedCount,
            int acceptedCount,
            int duplicateCount,
            int quarantinedCount,
            int autoApprovedCount,
            int sampledCount,
            int exceptionCount,
            int waitingSkillCount,
            String status,
            String policyVersion,
            OffsetDateTime createdAt
    ) {
    }

    public record SourceLedgerItem(
            String sourceId,
            String sourceName,
            String sourceTier,
            String timeScope,
            String adapter,
            int batchCount,
            int collectedCount,
            int duplicateCount,
            int acceptedCount,
            int exceptionCount,
            OffsetDateTime updatedAt
    ) {
    }

    public record DynamicRecordPage(List<DynamicRecordSummary> items, int page, int size, long total) {
    }

    public record DynamicRecordSummary(
            long id,
            long importId,
            String sourceRecordKey,
            String title,
            String company,
            String city,
            String contentHash,
            String dataMode,
            String status,
            double qualityScore,
            int skillCount,
            int candidateSkillCount,
            int issueCount,
            String reviewDecision,
            String riskLevel,
            String reviewRoute,
            List<String> reviewReasons,
            String reviewSource,
            String triagePolicyVersion,
            OffsetDateTime triagedAt,
            OffsetDateTime collectedAt
    ) {
    }

    public record DynamicRecordDetail(
            DynamicRecordSummary summary,
            String sourceName,
            String licenseStatus,
            String sourceUrl,
            String canonicalUrl,
            String salary,
            String experienceRequirement,
            String educationRequirement,
            OffsetDateTime publishedAt,
            String rawText,
            List<DynamicFact> facts,
            List<DynamicQualityIssue> issues,
            List<DynamicReview> reviews
    ) {
    }

    public record DynamicFact(
            long id,
            String factType,
            String ontologyStatus,
            String canonicalValue,
            String suggestedCategory,
            String rawValue,
            String requirementType,
            int startOffset,
            int endOffset,
            String evidenceQuote,
            double confidence,
            boolean offsetVerified,
            String reviewDecision,
            String correctedValue,
            String reviewNote
    ) {
    }

    public record DynamicQualityIssue(long id, String type, String severity, String detail, boolean blocking) {
    }

    public record DynamicReview(long id, String decision, String reviewer, String reason,
                                String policyVersion, String reviewSource, OffsetDateTime reviewedAt) {
    }

    public record FactReviewCommand(long factId, String decision, String correctedValue, String note) {
    }

    public record DynamicReviewCommand(
            String decision,
            String reviewer,
            String reason,
            List<FactReviewCommand> factReviews
    ) {
    }

    public record CandidateSkillSummary(
            String skill,
            String suggestedCategory,
            int factCount,
            int companyCount,
            String reviewDecision,
            String correctedValue,
            String decisionSource,
            List<String> requirementTypes,
            String recommendedDecision,
            String recommendedTarget,
            List<String> recommendationReasons,
            List<CandidateSkillEvidence> evidenceSamples
    ) {
    }

    public record CandidateSkillEvidence(
            long recordId,
            String title,
            String company,
            String quote,
            String requirementType,
            double confidence,
            boolean offsetVerified
    ) {
    }

    public record CandidateSkillReviewCommand(
            String decision,
            String correctedValue,
            String reviewer,
            String note
    ) {
    }

    public record BulkDynamicReviewCommand(
            List<Long> recordIds,
            String decision,
            String reviewer,
            String reason
    ) {
    }

    public record BulkCandidateSkillReviewCommand(
            List<String> skills,
            String decision,
            String correctedValue,
            String reviewer,
            String note
    ) {
    }

    public record DynamicRecordQuery(
            Long importId,
            String status,
            String keyword,
            String riskLevel,
            String reviewRoute,
            boolean onlyExceptions,
            int page,
            int size
    ) {
    }

    public record ReviewQueueSummary(
            Long importId,
            long totalRecords,
            long autoApprovedCount,
            long sampledCount,
            long exceptionCount,
            long waitingSkillCount,
            long humanApprovedCount,
            long blockedCount,
            long pendingCandidateSkillCount,
            long estimatedRemainingActions
    ) {
    }

    public record TriageCandidate(
            long id,
            long importId,
            String status,
            double qualityScore,
            String contentHash,
            int unresolvedCandidateCount,
            int blockingIssueCount,
            int elevatedIssueCount,
            double minimumConfidence,
            String latestDecision,
            String latestReviewSource
    ) {
    }

    public record TriageAssignment(
            long recordId,
            String riskLevel,
            String reviewRoute,
            List<String> reasons,
            String reviewSource,
            boolean createAutoApproval
    ) {
    }

    public record PromotionBatchCommand(
            List<Long> recordIds,
            Long importId,
            String selectionMode,
            String requestedBy
    ) {
        public PromotionBatchCommand(List<Long> recordIds, String requestedBy) {
            this(recordIds, null, "IDS", requestedBy);
        }
    }

    public record PromotionReviewCommand(String decision, String reviewer, String note) {
    }

    public record PromotionPublishCommand(String publisher) {
    }

    public record PromotionBatchView(
            long id,
            String batchCode,
            String snapshotCode,
            String policyVersion,
            String status,
            int candidateCount,
            int promotedCount,
            int rejectedCount,
            String requestedBy,
            String approvedBy,
            String reviewNote,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            List<PromotionItemView> items
    ) {
    }

    public record PromotionItemView(long recordId, String title, String company, String decision,
                                    Long targetDocumentId, String reason) {
    }

    public record RealSnapshotView(
            String snapshotCode,
            String dataMode,
            String usageScope,
            int documentCount,
            int independentCompanyCount,
            boolean publishedTimeCoverage,
            List<RealRoleSnapshot> roles
    ) {
    }

    public record RealRoleSnapshot(
            String roleCode,
            String roleName,
            int sampleSize,
            int independentCompanies,
            String confidenceLabel,
            List<RealResponsibilityMetric> responsibilities,
            List<RealSkillMetric> requiredSkills,
            List<RealSkillMetric> bonusSkills,
            List<RealSkillMetric> mentionedSkills,
            List<RealEvidence> opposingEvidence
    ) {
    }

    public record RealResponsibilityMetric(
            String responsibility,
            int documentCount,
            int companyCount,
            List<RealEvidence> evidence
    ) {
    }

    public record RealSkillMetric(
            String skill,
            double rate,
            int documentCount,
            int companyCount,
            List<RealEvidence> evidence
    ) {
    }

    public record RealEvidence(
            long documentId,
            String title,
            String company,
            String quote,
            int startOffset,
            int endOffset,
            String sourceUrl
    ) {
    }
}
