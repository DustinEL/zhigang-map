package com.zhigang.platform.emerging;

import java.time.OffsetDateTime;
import java.util.List;

public final class EmergingQueryModels {
    private EmergingQueryModels() {
    }

    public record QueryRequest(String query) {
    }

    public record QueryEvidence(
            long id,
            String key,
            String title,
            String source,
            String domain,
            String url,
            String publishedAt,
            String excerpt
    ) {
    }

    public record QueryFieldEvidence(
            String fieldName,
            String fieldValue,
            long evidenceId,
            String evidenceKey
    ) {
    }

    public record QueryDiscovery(
            long id,
            String query,
            String normalizedQuery,
            String status,
            String domain,
            String name,
            String definition,
            List<String> responsibilities,
            List<String> requiredSkills,
            List<String> bonusSkills,
            List<String> scenarios,
            Long nearestRoleId,
            String nearestRoleName,
            double nearestRoleSimilarity,
            double noveltyScore,
            int evidenceCount,
            int sourceCount,
            boolean fieldEvidenceComplete,
            boolean webSearchUsed,
            boolean publishable,
            String modelName,
            String errorCode,
            String errorMessage,
            Long publishedRoleId,
            Long publishedReleaseId,
            List<QueryEvidence> evidence,
            List<QueryFieldEvidence> fieldEvidence,
            boolean fromCache,
            double cacheSimilarity,
            String matchedQuery,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record QueryDraft(
            String query,
            String normalizedQuery,
            String queryHash,
            String status,
            String domain,
            String name,
            String definition,
            List<String> responsibilities,
            List<String> requiredSkills,
            List<String> bonusSkills,
            List<String> scenarios,
            Long nearestRoleId,
            String nearestRoleName,
            double nearestRoleSimilarity,
            double noveltyScore,
            boolean fieldEvidenceComplete,
            boolean webSearchUsed,
            boolean publishable,
            String modelName,
            String modelInputHash,
            String errorCode,
            String errorMessage,
            List<QueryEvidenceDraft> evidence,
            List<QueryFieldEvidenceDraft> fieldEvidence
    ) {
    }

    public record QueryEvidenceDraft(
            String key,
            String title,
            String source,
            String domain,
            String url,
            String publishedAt,
            String excerpt
    ) {
    }

    public record QueryFieldEvidenceDraft(
            String fieldName,
            String fieldValue,
            String evidenceKey
    ) {
    }
}
