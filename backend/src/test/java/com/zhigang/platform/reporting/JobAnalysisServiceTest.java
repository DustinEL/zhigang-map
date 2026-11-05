package com.zhigang.platform.reporting;

import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.quality.DataQualityService;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import com.zhigang.platform.repository.PlatformDataProvider;
import com.zhigang.platform.repository.PlatformDataProvider.PublicDataStats;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.domain.PlatformModels.SkillRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.OffsetDateTime;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobAnalysisServiceTest {
    private JobAnalysisService service;

    @BeforeEach
    void setUp() {
        DemoPlatformDataProvider provider = new DemoPlatformDataProvider();
        SkillNormalizationService normalization = new SkillNormalizationService(provider);
        DataQualityService quality = new DataQualityService(provider, 180, "v1.0.0");
        service = new JobAnalysisService(provider, normalization, quality, 8, 5, 2);
    }

    @Test
    void dashboardAndMarketCardExposeEvidenceScope() {
        var dashboard = service.dashboard();
        var card = service.marketCard(1L);
        var timeline = service.marketTimeline(1L);

        assertThat(dashboard.rawDocumentCount()).isGreaterThan(100);
        assertThat(dashboard.publishedRoleCount()).isGreaterThanOrEqualTo(3);
        assertThat(dashboard.pipeline()).hasSize(5);
        assertThat(card.role().name()).contains("RAG");
        assertThat(card.sampleSize()).isGreaterThan(0);
        assertThat(card.independentCompanies()).isGreaterThanOrEqualTo(5);
        assertThat(card.evidence()).allMatch(evidence -> evidence.endOffset() > evidence.startOffset());
        assertThat(card.confidence()).isBetween(0.0, 1.0);
        assertThat(timeline.roleId()).isEqualTo(1L);
        assertThat(timeline.periods()).isNotEmpty().hasSizeLessThanOrEqualTo(6)
                .allMatch(period -> period.confidence() >= 0 && period.confidence() <= 1);
    }

    @Test
    void publicOverviewExposesVerifiedRolesWithThreeEvidenceTiers() {
        var overview = service.publicOverview();

        assertThat(overview.roles()).hasSize(11);
        assertThat(overview.roles()).noneMatch(role -> "LLM_EVALUATION_ENGINEER".equals(role.code()));
        assertThat(overview.roles()).allMatch(role -> List.of("PUBLISHED", "BASELINE", "BUILDING").contains(role.status()));
        assertThat(overview.roles().stream().filter(role -> "PUBLISHED".equals(role.status())).toList()).hasSize(3);
        assertThat(overview.roles().stream().filter(role -> "BASELINE".equals(role.status())).toList()).hasSize(2);
        assertThat(overview.roles().stream().filter(role -> !"PUBLISHED".equals(role.status())).toList())
                .allMatch(role -> role.sampleCount() == null && role.companyCount() == null);
        assertThat(overview.roles()).allMatch(role -> role.family() != null && !role.family().isBlank());
        assertThat(overview.disclaimer()).contains("JD 证据次数排序");
    }

    @Test
    void publicPanoramaConnectsVerifiedRolesFamiliesAndSharedSkills() {
        var graph = service.publicGraphOverview();

        assertThat(graph.viewType()).isEqualTo("PUBLIC_PANORAMA");
        assertThat(graph.nodes().stream().filter(node -> "JOB_ROLE".equals(node.type())).toList()).hasSize(11);
        assertThat(graph.nodes().stream().filter(node -> "JOB_FAMILY".equals(node.type())).toList()).hasSize(5);
        assertThat(graph.nodes()).anyMatch(node -> "SKILL".equals(node.type()) && "REAL_EVIDENCE".equals(node.status()));
        assertThat(graph.nodes()).anyMatch(node -> "SKILL".equals(node.type()) && "BASELINE".equals(node.status()));
        assertThat(graph.edges()).anyMatch(edge -> "CONTAINS".equals(edge.relation()));
        assertThat(graph.edges()).anyMatch(edge -> "REQUIRES".equals(edge.relation()));
    }

    @Test
    void graphFiltersChangeNodesAndRelationsInsteadOfOnlyEchoingParameters() {
        var all = service.publicGraphOverview();
        var java = service.publicGraphOverview("Java", "初中级", "2026.07", "REAL_EVIDENCE");
        var baseline = service.publicGraphOverview(null, null, null, "BASELINE");

        assertThat(java.nodes()).isNotEmpty().hasSizeLessThan(all.nodes().size());
        assertThat(java.edges()).isNotEmpty().hasSizeLessThan(all.edges().size());
        assertThat(java.nodes().stream().filter(node -> "SKILL".equals(node.type())))
                .allMatch(node -> node.label().contains("Java") || node.group().contains("Java"));
        assertThat(java.nodes()).anyMatch(node -> "JOB_ROLE".equals(node.type()) && node.label().contains("Java"));
        assertThat(baseline.nodes().stream().filter(node -> "JOB_ROLE".equals(node.type())))
                .allMatch(node -> "BASELINE".equals(node.status()));
        assertThat(service.publicGraphOverview("不存在的技术栈", null, null, null).nodes()).isEmpty();
    }

    @Test
    void publicRoleOverviewExposesGraphFilterDimensions() {
        var roles = service.publicOverview().roles();

        assertThat(roles).allMatch(role -> role.jobLevel() != null && !role.jobLevel().isBlank());
        assertThat(roles.stream().filter(role -> !role.coreSkills().isEmpty()))
                .allMatch(role -> !role.techStack().isEmpty());
        assertThat(roles).allMatch(role -> role.roleVersion() != null && !role.roleVersion().isBlank());
        assertThat(roles).allMatch(role -> List.of("REAL_EVIDENCE", "BASELINE", "BUILDING").contains(role.evidenceLayer()));
    }

    @Test
    void emergingEvolutionGraphAndAdjacentRolesAreExplainable() {
        var candidates = service.emergingCandidates();
        var evolution = service.evolution(2L);
        var graph = service.graph(1L, "PANORAMA");
        var adjacent = service.adjacentRoles(1L);

        assertThat(candidates).isEmpty();
        assertThat(evolution.skills()).anyMatch(skill -> skill.skill().equals("Docker"));
        assertThat(evolution.evidence()).isNotEmpty();
        assertThat(graph.nodes()).anyMatch(node -> node.type().equals("JOB_ROLE"));
        assertThat(graph.nodes()).anyMatch(node -> node.type().equals("LEARNING_RESOURCE"));
        assertThat(graph.edges()).isNotEmpty();
        assertThat(adjacent).hasSizeGreaterThanOrEqualTo(2)
                .isSortedAccordingTo((left, right) -> Double.compare(left.transferDistance(), right.transferDistance()));
    }

    @Test
    void unknownRoleIsRejected() {
        assertThatThrownBy(() -> service.role(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标岗位");
    }

    @Test
    void directoryContainsOnlyVerifiedRolesAndFiveMatchableProfiles() {
        assertThat(service.roles()).hasSize(11);
        assertThat(service.roles()).noneMatch(role -> "LLM_EVALUATION_ENGINEER".equals(role.code()));
        assertThat(service.roles().stream().filter(role -> !role.requiredSkills().isEmpty()).toList()).hasSize(5);
        assertThat(service.marketCard(7L).sampleSize()).isZero();
        assertThat(service.marketCard(7L).confidence()).isZero();
    }

    @Test
    void realSnapshotWithoutPublicationTimeDoesNotFabricateHistory() {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        JobRole role = new JobRole(10002L, "JAVA", "Java 开发工程师", "软件与人工智能", "初中级",
                false, "当前试验快照", "", List.of("开发后端接口"),
                List.of(new SkillRequirement("Java", "编程语言", "REQUIRED", 1, 1, "NO_TREND")),
                List.of(), List.of(), "snapshot-test", null);
        RawDocument document = new RawDocument(1, "RECRUITMENT_JD", "授权样本", "OPEN", "甲公司",
                "北京", "软件", "初中级", "Java 开发工程师", "", "要求熟练掌握 Java", null,
                "hash", false);
        when(provider.findJobRole(10002L)).thenReturn(java.util.Optional.of(role));
        when(provider.findJobRoles()).thenReturn(List.of(role));
        when(provider.findDocuments()).thenReturn(List.of(document));
        when(provider.findLearningResources()).thenReturn(List.of());
        when(provider.dataMode()).thenReturn("REAL");
        SkillNormalizationService normalization = new SkillNormalizationService(provider);
        DataQualityService quality = new DataQualityService(provider, 180, "v1.0.0");
        JobAnalysisService realService = new JobAnalysisService(provider, normalization, quality, 8, 5, 2);

        var timeline = realService.marketTimeline(10002L);
        var evolution = realService.evolution(10002L);

        assertThat(timeline.periods()).isEmpty();
        assertThat(evolution.skills()).isEmpty();
        assertThat(evolution.sampleControl()).isEqualTo("当前试验快照");
        assertThat(evolution.conclusionLevel()).isEqualTo("CURRENT_SNAPSHOT");
    }

    @Test
    void dashboardCountsDistinctSourceNamesInsteadOfGenericDocumentType() {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        RawDocument first = new RawDocument(1, "RECRUITMENT_JD", "来源甲", "OPEN", "甲公司",
                "北京", "软件", "初中级", "Java 开发工程师", "", "Java", null,
                "hash-1", false);
        RawDocument second = new RawDocument(2, "RECRUITMENT_JD", "来源乙", "OPEN", "乙公司",
                "上海", "软件", "初中级", "AI Agent 开发工程师", "", "AI Agent", null,
                "hash-2", false);
        when(provider.findDocuments()).thenReturn(List.of(first, second));
        when(provider.findJobRoles()).thenReturn(List.of());
        when(provider.findSkills()).thenReturn(List.of());
        when(provider.dataMode()).thenReturn("REAL");
        when(provider.operatingMode()).thenReturn("MYSQL_FACT_SOURCE");
        SkillNormalizationService normalization = new SkillNormalizationService(provider);
        DataQualityService quality = new DataQualityService(provider, 180, "v1.0.0");
        JobAnalysisService realService = new JobAnalysisService(provider, normalization, quality, 8, 5, 2);

        assertThat(realService.dashboard().sourceCount()).isEqualTo(2);
    }

    @Test
    void publicOverviewCountsPublishedTierOneDocumentsAndExcludesDemoDocuments() {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-08-01T12:00:00+08:00");
        RawDocument first = new RawDocument(1, "TIER_1", "来源甲", "OPEN", "甲公司",
                "北京", "软件", "初中级", "Java 开发工程师", "", "Java", publishedAt,
                "hash-1", true);
        RawDocument second = new RawDocument(2, "TIER_1", "来源乙", "OPEN", "乙公司",
                "上海", "软件", "初中级", "前端开发工程师", "", "JavaScript", publishedAt,
                "hash-2", false);
        RawDocument excludedDemo = new RawDocument(3, "TIER_1", "来源丙", "OPEN", "丙公司",
                "", "", "", "政策", "", "不属于公开 JD", publishedAt, "hash-3", true);
        RawDocument excludedNonJd = new RawDocument(4, "POLICY", "来源丁", "OPEN", "丁公司",
                "", "", "", "产业政策", "", "非演示政策资料也不能计入 JD", publishedAt, "hash-4", false);
        when(provider.findDocuments()).thenReturn(List.of(first, second, excludedDemo, excludedNonJd));
        when(provider.findPublishedDiscoveryDocuments()).thenReturn(List.of(first, second, excludedDemo));
        when(provider.findJobRoles()).thenReturn(List.of());
        when(provider.dataMode()).thenReturn("REAL");
        JobAnalysisService realService = new JobAnalysisService(provider, new SkillNormalizationService(provider),
                new DataQualityService(provider, 180, "v1.0.0"), 8, 5, 2);

        var overview = realService.publicOverview();

        assertThat(overview.sampleCount()).isEqualTo(1);
        assertThat(overview.companyCount()).isEqualTo(1);
    }

    @Test
    void publicOverviewUsesAllPublishedDocumentsWhileDashboardKeepsLatestSnapshotScope() {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-08-01T12:00:00+08:00");
        List<RawDocument> publishedDocuments = IntStream.range(0, 317).mapToObj(index -> new RawDocument(
                index + 1L, "TIER_1", "来源" + index % 5, "OPEN", "企业" + index % 87,
                "城市", "软件", "初中级", "岗位" + index, "", "公开岗位资料", publishedAt,
                "hash-" + index, false)).toList();
        when(provider.findPublishedDiscoveryDocuments()).thenReturn(publishedDocuments);
        when(provider.findDocuments()).thenReturn(publishedDocuments.subList(0, 35));
        when(provider.findJobRoles()).thenReturn(List.of());
        when(provider.findSkills()).thenReturn(List.of());
        when(provider.findLearningResources()).thenReturn(List.of());
        when(provider.dataMode()).thenReturn("REAL");
        when(provider.operatingMode()).thenReturn("MYSQL_FACT_SOURCE");
        JobAnalysisService realService = new JobAnalysisService(provider, new SkillNormalizationService(provider),
                new DataQualityService(provider, 180, "v1.0.0"), 8, 5, 2);

        var overview = realService.publicOverview();
        var dashboard = realService.dashboard();

        assertThat(overview.sampleCount()).isEqualTo(317);
        assertThat(overview.companyCount()).isEqualTo(87);
        assertThat(dashboard.rawDocumentCount()).isEqualTo(35);
    }

    @Test
    void publicOverviewRanksTopSkillsByPublishedJdEvidenceCount() {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        JobRole published = new JobRole(31, "JAVA_ENGINEER", "Java 开发工程师", "后端开发", "初中级",
                false, "负责企业级后端系统开发", "", List.of("开发后端服务"),
                List.of(
                        new SkillRequirement("Java", "编程语言", "REQUIRED", 0.8, 8, "STABLE"),
                        new SkillRequirement("MySQL", "数据库", "REQUIRED", 0.6, 12, "STABLE"),
                        new SkillRequirement("Docker", "工程工具", "REQUIRED", 0.5, 5, "STABLE")
                ),
                List.of(new SkillRequirement("Java", "编程语言", "BONUS", 0.2, 3, "STABLE")),
                List.of(), "real-jd-2026-08", OffsetDateTime.parse("2026-08-01T12:00:00+08:00"));
        JobRole baseline = new JobRole(32, "BASELINE_ROLE", "能力基线岗位", "后端开发", "初中级",
                false, "用于方向了解", "", List.of(),
                List.of(new SkillRequirement("不应进入榜单", "基线能力", "REQUIRED", 1, 999, "BASELINE")),
                List.of(), List.of(), "2026.08", null);
        when(provider.findJobRoles()).thenReturn(List.of(published, baseline));
        when(provider.findJobRole(31L)).thenReturn(java.util.Optional.of(published));
        when(provider.findJobRole(32L)).thenReturn(java.util.Optional.of(baseline));
        when(provider.findDocuments()).thenReturn(List.of());
        when(provider.findPublishedDiscoveryDocuments()).thenReturn(List.of());
        when(provider.dataMode()).thenReturn("REAL");
        JobAnalysisService realService = new JobAnalysisService(provider, new SkillNormalizationService(provider),
                new DataQualityService(provider, 180, "v1.0.0"), 8, 5, 2);

        var overview = realService.publicOverview();

        assertThat(overview.topSkills()).containsExactly("MySQL", "Java", "Docker");
        assertThat(overview.dataScope()).doesNotContain("真实证据画像计入样本");
        assertThat(overview.disclaimer()).doesNotContain("不代表市场趋势");
    }

    @Test
    void publicOverviewLoadsSharedMarketDataOnceInsteadOfOncePerRole() {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        JobRole published = new JobRole(41, "JAVA_ENGINEER", "Java 开发工程师", "后端开发", "初中级",
                false, "负责后端服务开发", "", List.of("开发后端服务"),
                List.of(new SkillRequirement("Java", "编程语言", "REQUIRED", 1, 1, "STABLE")),
                List.of(), List.of(), "real-jd-2026-08", OffsetDateTime.parse("2026-08-01T12:00:00+08:00"));
        JobRole baseline = new JobRole(42, "BASELINE_ROLE", "基线岗位", "通用", "初中级",
                false, "了解基线能力", "", List.of(),
                List.of(new SkillRequirement("沟通", "通用能力", "REQUIRED", 1, 1, "BASELINE")),
                List.of(), List.of(), "2026.08", null);
        RawDocument document = new RawDocument(1, "RECRUITMENT_JD", "公开来源", "OPEN", "甲公司",
                "北京", "JAVA_ENGINEER", "初中级", "Java 开发工程师", "", "需要 Java 能力", null,
                "hash-1", false);
        when(provider.findPublishedDiscoveryDocuments()).thenReturn(List.of(document));
        when(provider.findJobRoles()).thenReturn(List.of(published, baseline));
        when(provider.findJobRole(41L)).thenReturn(java.util.Optional.of(published));
        when(provider.findJobRole(42L)).thenReturn(java.util.Optional.of(baseline));
        when(provider.findDocuments()).thenReturn(List.of(document));
        when(provider.findRoleDocumentIds(41L)).thenReturn(java.util.Set.of(1L));
        when(provider.dataMode()).thenReturn("REAL");
        JobAnalysisService realService = new JobAnalysisService(provider, new SkillNormalizationService(provider),
                new DataQualityService(provider, 180, "v1.0.0"), 8, 5, 2);

        var overview = realService.publicOverview();

        assertThat(overview.roles()).hasSize(2);
        verify(provider, times(1)).findJobRoles();
        verify(provider, times(1)).findDocuments();
        verify(provider, times(1)).dataMode();
        verify(provider, times(1)).findRoleDocumentIds(41L);
        verify(provider, times(0)).findRoleDocumentIds(42L);
    }

    @Test
    void javaAndJavaScriptGraphFiltersDoNotCrossMatch() {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        JobRole javaRole = new JobRole(21, "JAVA_ENGINEER", "Java 开发工程师", "后端开发", "初中级",
                false, "Java 后端开发", "", List.of(),
                List.of(new SkillRequirement("Java", "编程语言", "REQUIRED", 1, 3, "STABLE")),
                List.of(), List.of(), "v1", null);
        JobRole javascriptRole = new JobRole(22, "FRONTEND_ENGINEER", "前端开发工程师", "前端开发", "初中级",
                false, "Web 前端开发", "", List.of(),
                List.of(new SkillRequirement("JavaScript", "编程语言", "REQUIRED", 1, 3, "STABLE")),
                List.of(), List.of(), "v1", null);
        when(provider.findJobRoles()).thenReturn(List.of(javaRole, javascriptRole));
        when(provider.findDocuments()).thenReturn(List.of());
        when(provider.dataMode()).thenReturn("REAL");
        JobAnalysisService realService = new JobAnalysisService(provider, new SkillNormalizationService(provider),
                new DataQualityService(provider, 180, "v1.0.0"), 8, 5, 2);

        var javaGraph = realService.publicGraphOverview("Java", null, null, null);
        var javascriptGraph = realService.publicGraphOverview("JavaScript", null, null, null);

        assertThat(javaGraph.nodes()).anyMatch(node -> node.id().equals("role-21"))
                .noneMatch(node -> node.id().equals("role-22") || node.label().equals("JavaScript"));
        assertThat(javascriptGraph.nodes()).anyMatch(node -> node.id().equals("role-22"))
                .noneMatch(node -> node.id().equals("role-21") || node.label().equals("Java"));
    }

    @Test
    void publicDataVersionIsStableAndChangesWithPublicData() {
        PlatformDataProvider firstProvider = versionProvider(317,
                OffsetDateTime.parse("2026-08-01T12:00:00+08:00"));
        PlatformDataProvider sameProvider = versionProvider(317,
                OffsetDateTime.parse("2026-08-01T12:00:00+08:00"));
        PlatformDataProvider moreDocumentsProvider = versionProvider(318,
                OffsetDateTime.parse("2026-08-01T12:00:00+08:00"));
        PlatformDataProvider updatedRoleProvider = versionProvider(317,
                OffsetDateTime.parse("2026-08-02T12:00:00+08:00"));
        PlatformDataProvider updatedGraphProvider = versionProvider(317,
                OffsetDateTime.parse("2026-08-01T12:00:00+08:00"), "graph-v2");

        String first = version(firstProvider);
        assertThat(version(sameProvider)).isEqualTo(first);
        assertThat(version(moreDocumentsProvider)).isNotEqualTo(first);
        assertThat(version(updatedRoleProvider)).isNotEqualTo(first);
        assertThat(version(updatedGraphProvider)).isNotEqualTo(first);
    }

    private PlatformDataProvider versionProvider(int documentCount, OffsetDateTime roleUpdatedAt) {
        return versionProvider(documentCount, roleUpdatedAt, "graph-v1");
    }

    private PlatformDataProvider versionProvider(int documentCount, OffsetDateTime roleUpdatedAt,
                                                 String graphFingerprint) {
        PlatformDataProvider provider = mock(PlatformDataProvider.class);
        when(provider.publicDataStats()).thenReturn(new PublicDataStats(documentCount, 1, roleUpdatedAt,
                graphFingerprint));
        return provider;
    }

    private String version(PlatformDataProvider provider) {
        JobAnalysisService target = new JobAnalysisService(provider, new SkillNormalizationService(provider),
                new DataQualityService(provider, 180, "v1.0.0"), 8, 5, 2);
        return (String) target.publicDataVersion().get("version");
    }
}
