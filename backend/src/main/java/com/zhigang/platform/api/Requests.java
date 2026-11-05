package com.zhigang.platform.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public final class Requests {
    private Requests() {
    }

    public record ExtractionRequest(Long documentId, @NotBlank @Size(max = 20000) String text) {
        public ExtractionRequest(String text) {
            this(null, text);
        }
    }

    public record ResumeRequest(
            @NotBlank @Size(max = 30000) String text,
            @Min(1) @Max(40) int weeklyHours,
            @Min(2020) @Max(2035) int graduationYear
    ) {
    }

    public record MatchRunRequest(
            @NotNull Long resumeId,
            @NotNull Long jobRoleId,
            String preferredCity,
            String workMode
    ) {
    }

    public record LearningPlanRequest(
            @NotNull Long matchRunId,
            @Min(1) @Max(40) int weeklyHours
    ) {
    }

    public record ResumeConfirmationRequest(@NotNull Boolean confirmed) {
    }

    public record AbilityProfileRequest(
            String name,
            @NotBlank String education,
            @NotBlank String major,
            @Min(2020) @Max(2035) int graduationYear,
            @Min(1) @Max(40) int weeklyHours,
            @Size(min = 1) List<@NotBlank String> skills,
            List<@NotBlank String> projects,
            List<@NotBlank String> certificates
    ) {
    }

    public record ResumeFactsUpdateRequest(
            Boolean confirmed,
            String name,
            String education,
            String major,
            @Min(2020) @Max(2035) Integer graduationYear,
            @Min(1) @Max(40) Integer weeklyHours,
            List<@NotBlank String> skills,
            List<@NotBlank String> projects,
            List<@NotBlank String> certificates
    ) {
    }

    public record EvidenceExplanationRequest(
            @NotBlank String questionType,
            @NotNull Long jobRoleId,
            String skill,
            String graphRelease,
            Long matchRunId
    ) {
    }

    public record VerificationSubmissionRequest(
            @NotNull Long learningPlanId,
            @Min(1) int itemOrder,
            @NotBlank String evidenceType,
            String evidenceUrl,
            @NotBlank @Size(max = 5000) String evidenceText
    ) {
    }

    public record VerificationReviewRequest(
            @NotBlank String decision,
            @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record DiagnosisComparisonRequest(
            @NotNull Long beforeMatchRunId,
            @NotNull Long verificationSubmissionId
    ) {
    }

    public record ClassificationEvaluationRequest(
            @Size(min = 2) List<@NotBlank String> expectedLabels,
            @Size(min = 2) List<@NotBlank String> predictedLabels,
            @Min(100) @Max(10000) int bootstrapSamples
    ) {
    }

    public record ExtractionEvaluationItem(
            @NotNull Set<@NotBlank String> expected,
            @NotNull Set<@NotBlank String> predicted
    ) {
    }

    public record ExtractionEvaluationRequest(
            @Size(min = 1) List<@NotNull ExtractionEvaluationItem> items
    ) {
    }

    public record ChangeProposalRequest(
            @NotBlank String title,
            @NotBlank String type,
            @NotBlank String reason,
            @Size(min = 1) List<String> changes
    ) {
    }

    public record ReviewRequest(
            @NotBlank String decision,
            @NotBlank String reviewer,
            String comment
    ) {
    }

    public record GraphReleaseRequest(
            @NotBlank String version,
            @NotBlank String snapshotId,
            @NotBlank String publisher,
            List<Long> proposalIds
    ) {
    }

    public record DataImportRequest(
            @NotBlank String sourceType,
            @NotBlank String sourceName,
            @NotBlank String licenseStatus,
            @Size(min = 1) List<@NotBlank String> documents
    ) {
    }

    public record DirectoryScanRequest(
            @NotBlank String sourceName,
            @NotBlank String licenseStatus
    ) {
    }

    public record ProcessingRunRequest(
            @NotBlank String snapshotId,
            List<String> steps
    ) {
    }
}
