package com.zhigang.platform.repository;

import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.LearningResource;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.domain.PlatformModels.RoleSkillEvidence;
import com.zhigang.platform.domain.PlatformModels.SkillDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.OffsetDateTime;
import java.util.Comparator;

public interface PlatformDataProvider {
    List<JobRole> findJobRoles();

    Optional<JobRole> findJobRole(long id);

    List<SkillDefinition> findSkills();

    List<RawDocument> findDocuments();

    /**
     * 返回全部已晋级、非演示的公开招聘 JD；具体仓储负责按内容类型排除政策、课程等非岗位记录。
     */
    default List<RawDocument> findPublishedDiscoveryDocuments() {
        return findDocuments();
    }

    default PublicDataStats publicDataStats() {
        List<RawDocument> documents = findPublishedDiscoveryDocuments().stream()
                .filter(document -> !document.demoData())
                .toList();
        List<JobRole> roles = findJobRoles();
        OffsetDateTime updatedAt = java.util.stream.Stream.concat(
                        documents.stream().map(RawDocument::publishedAt),
                        roles.stream().map(JobRole::updatedAt))
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        String roleFingerprint = roles.stream()
                .map(role -> role.id() + ":" + role.code() + ":" + role.releaseVersion() + ":" + role.updatedAt())
                .sorted()
                .collect(java.util.stream.Collectors.joining("|"));
        return new PublicDataStats(documents.size(), roles.size(), updatedAt, roleFingerprint);
    }

    default List<DiscoveryEvidence> findPublishedDiscoveryEvidence() {
        return List.of();
    }

    default Map<Long, DocumentSemanticFacts> findDocumentSemanticFacts() {
        return Map.of();
    }

    default List<JobRole> findPublishedBaselineJobRoles() {
        return findJobRoles();
    }

    List<LearningResource> findLearningResources();

    String operatingMode();

    default Optional<RoleSkillEvidence> findRoleSkillEvidence(long roleId, String skill) {
        return Optional.empty();
    }

    default Set<Long> findRoleDocumentIds(long roleId) {
        return Set.of();
    }

    default String dataMode() {
        List<RawDocument> documents = findDocuments();
        if (documents.isEmpty()) return "NO_DATA";
        if (documents.stream().allMatch(RawDocument::demoData)) return "DEMO";
        return "REAL";
    }

    record DocumentSemanticFacts(List<String> responsibilities, List<String> skills) {
        public DocumentSemanticFacts {
            responsibilities = responsibilities == null ? List.of() : List.copyOf(responsibilities);
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    record DiscoveryEvidence(long documentId, String title, String source, String company,
                             String sourceUrl, String publishedAt, String content) {
    }

    record PublicDataStats(int sampleCount, int roleCount, OffsetDateTime updatedAt, String graphFingerprint) {
        public PublicDataStats(int sampleCount, int roleCount, OffsetDateTime updatedAt) {
            this(sampleCount, roleCount, updatedAt, "");
        }
    }
}
