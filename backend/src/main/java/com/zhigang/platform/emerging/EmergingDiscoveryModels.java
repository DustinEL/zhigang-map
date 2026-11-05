package com.zhigang.platform.emerging;

import com.zhigang.platform.domain.PlatformModels.Evidence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class EmergingDiscoveryModels {
    private EmergingDiscoveryModels() {
    }

    public record DiscoveryRun(
            long id,
            String runCode,
            String status,
            String policyVersion,
            String policyHash,
            String inputHash,
            String embeddingModel,
            Integer embeddingDimension,
            String clusterModel,
            int documentCount,
            int clusterCount,
            int candidateCount,
            int noiseCount,
            String errorCode,
            String errorMessage,
            String requestedBy,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt
    ) {
    }

    public record DiscoveryCandidate(
            long id,
            long runId,
              int version,
              String name,
              String jobLevel,
              List<String> technologyStack,
              String nearestRole,
            double nearestRoleSimilarity,
            double emergenceScore,
            String uncertainty,
            int documentCount,
            int companyCount,
            int sourceCount,
            double clusterStability,
            String status,
            String generationMode,
            Map<String, Double> metrics,
            List<String> responsibilities,
            List<String> requiredSkills,
            List<String> bonusSkills,
            List<String> scenarios,
            List<Evidence> supportingEvidence,
            List<String> opposingEvidence,
            String creator,
            String reviewer,
            OffsetDateTime updatedAt
    ) {
    }

    public record CandidateDraft(
              int expectedVersion,
              String name,
              String jobLevel,
              List<String> technologyStack,
              List<String> responsibilities,
            List<String> requiredSkills,
            List<String> bonusSkills,
            List<String> scenarios,
            String reason,
            List<Long> evidenceIds,
            String editor
    ) {
    }

    public record CandidateReview(String decision, String reviewer, String comment) {
    }

    public record CandidatePublish(String version, String publisher) {
    }

    public record CandidateEvidence(
            long id,
            String fieldName,
            String fieldValue,
            long documentId,
            int startOffset,
            int endOffset,
            String quote,
            String direction,
            boolean anchorValid,
            String source,
            String company
    ) {
    }

    public record CandidateRevision(
            long id,
            int fromVersion,
            int toVersion,
            Map<String, Object> before,
            Map<String, Object> after,
            String reason,
            List<Long> evidenceIds,
            String editor,
            OffsetDateTime createdAt
    ) {
    }

    public record CandidateDetail(
            DiscoveryCandidate candidate,
            List<CandidateEvidence> evidence,
            List<CandidateRevision> revisions
    ) {
    }

    public record ClusterRequest(List<Long> documentIds, List<List<Double>> vectors,
                                 int minClusterSize, int minSamples) {
    }

    public record ClusterAssignment(long documentId, int label, double probability, boolean noise) {
    }

    public record ClusterResponse(String algorithm, int vectorDimension, List<ClusterAssignment> assignments) {
    }

    public record CandidateSeed(
            int clusterLabel,
            String status,
            int documentCount,
            int companyCount,
            int sourceCount,
            double medianProbability,
            Long nearestRoleId,
            String nearestRoleName,
            double nearestRoleSimilarity,
            double emergenceScore,
            Map<String, Double> metrics,
            List<String> opposingEvidence,
            String candidateName,
            String uncertainty,
            List<String> responsibilities,
            List<String> requiredSkills,
            List<String> bonusSkills,
            List<String> scenarios,
            String generationMode,
            String generationInputHash,
            List<ClusterAssignment> members,
            List<FieldEvidence> evidence
    ) {
    }

    public record FieldEvidence(
            String fieldName,
            String fieldValue,
            long documentId,
            int startOffset,
            int endOffset,
            String quote,
            String direction,
            boolean anchorValid
    ) {
    }
}
