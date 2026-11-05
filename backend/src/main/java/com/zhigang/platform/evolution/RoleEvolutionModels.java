package com.zhigang.platform.evolution;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class RoleEvolutionModels {
    private RoleEvolutionModels() {
    }

    public record Policy(String version, int windowMonths, int minimumDocumentsPerWindow,
                         int minimumCompaniesPerWindow, int minimumSourcesPerWindow,
                         double minimumAbsoluteChange, int newSkillBaselineMaximumCompanies,
                         int newSkillCurrentMinimumCompanies, int removalBaselineMinimumCompanies,
                         int removalConsecutiveWindows, int evidenceLimitPerWindow, double confidenceZ) {
    }

    public record SkillAnchor(Long skillId, String skill, String quote, int startOffset, int endOffset,
                              boolean anchorValid, String direction) {
    }

    public record SourceDocument(long documentId, String title, String company, String source, String sourceUrl,
                                 String city, String level, LocalDate publishedAt, List<SkillAnchor> skills) {
    }

    public record RoleIdentity(long id, String code, String name) {
    }

    public record WindowDraft(String type, String label, LocalDate startDate, LocalDate endDate,
                              int documentCount, int companyCount, int sourceCount, List<String> sourceNames,
                              String cityScope, String levelScope, String sampleHash) {
    }

    public record EvidenceDraft(String windowType, long documentId, String title, String company, String source,
                                String sourceUrl, LocalDate publishedAt, String quote, int startOffset,
                                int endOffset, boolean anchorValid, String direction) {
    }

    public record ChangeDraft(Long skillId, String skill, String changeType, String confidenceLevel,
                              int baselineDocumentCount, int baselineCompanyCount, double baselineRate,
                              double baselineLowerBound, double baselineUpperBound,
                              int currentDocumentCount, int currentCompanyCount, double currentRate,
                              double currentLowerBound, double currentUpperBound, double absoluteChange,
                              double differenceLowerBound, double differenceUpperBound,
                              String sensitivityDirection, int consecutiveAbsentWindows,
                              boolean publishable, boolean includedInDraft, List<EvidenceDraft> evidence) {
    }

    public record RunDraft(String runCode, long roleId, String roleCode, String roleName,
                           String policyVersion, String policyHash, String inputHash, String status,
                           String conclusionLevel, String sampleControl, String statisticsMethod,
                           String scopeJson, List<WindowDraft> windows, List<ChangeDraft> changes) {
    }

    public record WindowView(long id, String type, String label, LocalDate startDate, LocalDate endDate,
                             int documentCount, int companyCount, int sourceCount, List<String> sourceNames,
                             String cityScope, String levelScope, String sampleHash) {
    }

    public record EvidenceView(long id, String windowType, long documentId, String title, String company,
                               String source, String sourceUrl, LocalDate publishedAt, String quote,
                               int startOffset, int endOffset, boolean anchorValid, String direction) {
    }

    public record PointView(String period, int documentCount, int companyCount, double demandRate,
                            double lowerBound, double upperBound) {
    }

    public record ChangeView(long id, Long skillId, String skill, String changeType, String confidenceLevel,
                             double absoluteChange, double differenceLowerBound, double differenceUpperBound,
                             String sensitivityDirection, int consecutiveAbsentWindows, boolean publishable,
                             boolean includedInDraft, List<PointView> points, List<EvidenceView> evidence) {
    }

    public record RunView(long id, String runCode, long roleId, String roleCode, String roleName,
                          String policyVersion, String policyHash, String inputHash, String status,
                          String conclusionLevel, String sampleControl, String statisticsMethod,
                          int version, String draftNote, Long publishedReleaseId, String publishedReleaseVersion,
                          List<WindowView> windows, List<ChangeView> changes,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime publishedAt) {
    }

    public record DraftUpdate(int expectedVersion, List<Long> includedChangeIds, String note,
                              String reason, String editor) {
    }

    public record PublishCommand(int expectedVersion, String version, String publisher) {
    }
}
