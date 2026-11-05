package com.zhigang.platform.reporting;

import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.AdjacentRole;
import com.zhigang.platform.domain.PlatformModels.DashboardSummary;
import com.zhigang.platform.domain.PlatformModels.EmergingCandidate;
import com.zhigang.platform.domain.PlatformModels.Evidence;
import com.zhigang.platform.domain.PlatformModels.EvolutionPoint;
import com.zhigang.platform.domain.PlatformModels.EvolutionReport;
import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.domain.PlatformModels.GraphEdge;
import com.zhigang.platform.domain.PlatformModels.GraphNode;
import com.zhigang.platform.domain.PlatformModels.GraphView;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.MarketCard;
import com.zhigang.platform.domain.PlatformModels.MetricTrend;
import com.zhigang.platform.domain.PlatformModels.PipelineStep;
import com.zhigang.platform.domain.PlatformModels.PublicOverview;
import com.zhigang.platform.domain.PlatformModels.PublicRoleOverview;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.domain.PlatformModels.ResultMetadata;
import com.zhigang.platform.domain.PlatformModels.RoleMarketPeriod;
import com.zhigang.platform.domain.PlatformModels.RoleMarketTimeline;
import com.zhigang.platform.domain.PlatformModels.RoleSkillEvidence;
import com.zhigang.platform.domain.PlatformModels.RoleSkillEvidenceItem;
import com.zhigang.platform.domain.PlatformModels.SkillEvolution;
import com.zhigang.platform.domain.PlatformModels.SkillRequirement;
import com.zhigang.platform.quality.DataQualityService;
import com.zhigang.platform.repository.PlatformDataProvider;
import com.zhigang.platform.repository.PlatformDataProvider.PublicDataStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobAnalysisService {
    private static final OffsetDateTime REFERENCE_TIME = OffsetDateTime.of(2026, 7, 10, 23, 59, 0, 0, ZoneOffset.ofHours(8));
    private final PlatformDataProvider dataProvider;
    private final SkillNormalizationService normalizationService;
    private final DataQualityService qualityService;
    private final int minDocuments;
    private final int minCompanies;
    private final int minSources;

    public JobAnalysisService(
            PlatformDataProvider dataProvider,
            SkillNormalizationService normalizationService,
            DataQualityService qualityService,
            @Value("${zhigang.analysis.emerging-min-documents:8}") int minDocuments,
            @Value("${zhigang.analysis.emerging-min-companies:5}") int minCompanies,
            @Value("${zhigang.analysis.emerging-min-sources:2}") int minSources
    ) {
        this.dataProvider = dataProvider;
        this.normalizationService = normalizationService;
        this.qualityService = qualityService;
        this.minDocuments = minDocuments;
        this.minCompanies = minCompanies;
        this.minSources = minSources;
    }

    public DashboardSummary dashboard() {
        List<RawDocument> documents = dataProvider.findDocuments();
        boolean demo = "DEMO".equals(dataProvider.dataMode());
        int companies = (int) documents.stream().map(RawDocument::company).filter(value -> !value.isBlank()).distinct().count();
        List<JobRole> roles = dataProvider.findJobRoles();
        List<String> periods = List.of("2025Q3", "2025Q4", "2026Q1", "2026Q2");
        return new DashboardSummary(
                (int) documents.stream().map(RawDocument::sourceName).filter(value -> !value.isBlank()).distinct().count(),
                documents.size(), companies, dataProvider.findSkills().size(), roles.size(),
                emergingCandidates().size(), roles.isEmpty() ? "" : roles.get(0).releaseVersion(), dataProvider.operatingMode(),
                List.of(
                        new PipelineStep("多源快照", documents.isEmpty() ? "NO_DATA" : "COMPLETED", documents.size(), "招聘、课程、政策原文只读留存"),
                        new PipelineStep("质量管道", documents.isEmpty() ? "NO_DATA" : "COMPLETED", demo ? qualityService.buildReport("demo-snapshot-2026-07").effectiveCount() : documents.size(), "近重复、时滞、通胀和注入检查"),
                        new PipelineStep("证据化抽取", documents.isEmpty() ? "NO_DATA" : "COMPLETED", demo ? 862 : documents.size(), "规则通道 + 原文锚点"),
                        new PipelineStep("候选审核", documents.isEmpty() ? "NO_DATA" : "RUNNING", demo ? 5 : 0, "候选技能和异常记录审核"),
                        new PipelineStep("图谱发布", roles.isEmpty() ? "NO_DATA" : "COMPLETED", roles.size(), "MySQL 事实版本")
                ),
                demo ? List.of(
                        new MetricTrend("RAG 需求率", List.of(0.28, 0.42, 0.63, 0.81), periods),
                        new MetricTrend("Docker 需求率", List.of(0.41, 0.48, 0.59, 0.68), periods),
                        new MetricTrend("JSP/Servlet 需求率", List.of(0.31, 0.23, 0.15, 0.09), periods)
                ) : List.of()
        );
    }

    public List<JobRole> roles() {
        return publicCatalogRoles();
    }

    public Map<String, Object> publicDataVersion() {
        PublicDataStats stats = dataProvider.publicDataStats();
        OffsetDateTime updatedAt = stats.updatedAt() == null ? REFERENCE_TIME : stats.updatedAt();
        String version = "v2-role-catalog-" + stats.sampleCount() + "-" + stats.roleCount() + "-"
                + updatedAt.toInstant().toEpochMilli() + "-" + stats.graphFingerprint();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", version);
        response.put("sampleCount", stats.sampleCount());
        response.put("roleCount", stats.roleCount());
        response.put("updatedAt", updatedAt);
        return Map.copyOf(response);
    }

    public PublicOverview publicOverview() {
        List<RawDocument> documents = dataProvider.findPublishedDiscoveryDocuments().stream()
                .filter(document -> !document.demoData())
                .toList();
        int companies = distinctCompanies(documents);
        List<JobRole> roleCatalog = publicCatalogRoles();
        boolean hasPublishedRoles = roleCatalog.stream().anyMatch(role -> "PUBLISHED".equals(publicRoleStatus(role)));
        List<RawDocument> marketDocuments = hasPublishedRoles ? dataProvider.findDocuments() : List.of();
        String dataMode = hasPublishedRoles ? dataProvider.dataMode() : "NO_DATA";
        List<PublicRoleOverview> publicRoles = roleCatalog.stream()
                .map(role -> {
                    String status = publicRoleStatus(role);
                    boolean published = "PUBLISHED".equals(status);
                    MarketCard card = published ? marketCard(role, roleDocuments(role, marketDocuments), dataMode) : null;
                    String background = role.code().contains("JAVA")
                            ? "有 Java、数据库或后端项目基础的学生"
                            : role.family().contains("AI") ? "有编程基础，希望进入人工智能与大模型方向的学生"
                            : "有计算机基础，希望了解该方向的学生";
                    return new PublicRoleOverview(role.id(), role.code(), role.name(), role.family(), role.emerging(), role.definition(), background,
                            status, concat(role.requiredSkills(), role.bonusSkills()).stream().limit(5)
                            .map(SkillRequirement::skill).toList(), role.responsibilities().stream().limit(3).toList(),
                            role.level(), technologyStack(role), role.releaseVersion(), evidenceLayer(status),
                            published ? card.sampleSize() : null, published ? card.independentCompanies() : null,
                            published ? role.updatedAt() : null);
                }).toList();
        Map<String, Integer> skillEvidenceCounts = roleCatalog.stream()
                .filter(role -> "PUBLISHED".equals(publicRoleStatus(role)))
                .flatMap(role -> concat(role.requiredSkills(), role.bonusSkills()).stream())
                .filter(requirement -> requirement.skill() != null && !requirement.skill().isBlank())
                .collect(Collectors.toMap(SkillRequirement::skill, SkillRequirement::evidenceCount,
                        Integer::sum, LinkedHashMap::new));
        List<String> topSkills = skillEvidenceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(8)
                .map(Map.Entry::getKey)
                .toList();
        OffsetDateTime updatedAt = publicRoles.stream().map(PublicRoleOverview::updatedAt).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(REFERENCE_TIME);
        return new PublicOverview(updatedAt, "覆盖 " + publicRoles.size() + " 个岗位方向，共收录 "
                + documents.size() + " 条公开岗位信息", documents.size(), companies, List.copyOf(publicRoles),
                topSkills, "高频能力按已发布岗位画像中的 JD 证据次数排序");
    }

    private String publicRoleStatus(JobRole role) {
        if (role.code().startsWith("DISCOVERED_") && !role.requiredSkills().isEmpty()) {
            return "PUBLISHED";
        }
        if (role.releaseVersion() != null && role.releaseVersion().startsWith("real-jd-")
                && !role.requiredSkills().isEmpty()) {
            return "PUBLISHED";
        }
        if ((role.code().contains("JAVA") || role.code().contains("AGENT") || "RAG_APP_ENGINEER".equals(role.code()))
                && !role.requiredSkills().isEmpty()) {
            return "PUBLISHED";
        }
        return role.requiredSkills().isEmpty() ? "BUILDING" : "BASELINE";
    }

    private List<JobRole> publicCatalogRoles() {
        return dataProvider.findJobRoles().stream()
                .filter(role -> !"LLM_EVALUATION_ENGINEER".equals(role.code()))
                .toList();
    }

    public GraphView publicGraphOverview() {
        return publicGraphOverview(null, null, null, null);
    }

    public GraphView publicGraphOverview(String techStack, String jobLevel, String roleVersion, String evidenceLayer) {
        List<JobRole> roles = publicCatalogRoles().stream()
                .filter(role -> matchesRoleFilter(role, techStack, jobLevel, roleVersion, evidenceLayer))
                .toList();
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();
        int edgeIndex = 1;
        for (JobRole role : roles) {
            String status = publicRoleStatus(role);
            String familyId = "family-" + normalizeId(role.family());
            nodes.putIfAbsent(familyId, new GraphNode(familyId, role.family(), "JOB_FAMILY", role.family(), 0, "DIRECTORY"));
            String roleId = "role-" + role.id();
            int evidenceCount = "PUBLISHED".equals(status)
                    ? role.requiredSkills().stream().mapToInt(SkillRequirement::evidenceCount).sum() : 0;
            nodes.put(roleId, new GraphNode(roleId, role.name(), "JOB_ROLE", role.family(), evidenceCount, status));
            edges.add(new GraphEdge("public-edge-" + edgeIndex++, familyId, roleId, "CONTAINS", 1, status));
            if ("BUILDING".equals(status)) {
                continue;
            }
            for (SkillRequirement requirement : concat(role.requiredSkills(), role.bonusSkills()).stream()
                    .filter(requirement -> matchesTechStack(requirement, techStack)).limit(8).toList()) {
                String skillId = "skill-" + normalizeId(requirement.skill());
                GraphNode existing = nodes.get(skillId);
                String skillStatus = "PUBLISHED".equals(status) ? "REAL_EVIDENCE" : "BASELINE";
                if (existing == null || "REAL_EVIDENCE".equals(skillStatus)) {
                    nodes.put(skillId, new GraphNode(skillId, requirement.skill(), "SKILL", requirement.category(),
                            "PUBLISHED".equals(status) ? requirement.evidenceCount() : 0, skillStatus));
                }
                edges.add(new GraphEdge("public-edge-" + edgeIndex++, roleId, skillId,
                        "REQUIRED".equals(requirement.requirementType()) ? "REQUIRES" : "PREFERS",
                        requirement.demandRate() > 0 ? requirement.demandRate() : 1, status));
            }
        }
        List<RawDocument> documents = dataProvider.findDocuments().stream()
                .filter(document -> "RECRUITMENT_JD".equals(document.sourceType())).toList();
        String releaseVersion = roles.stream().map(JobRole::releaseVersion).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse("public-catalog");
        return new GraphView(releaseVersion, "PUBLIC_PANORAMA", List.copyOf(nodes.values()), List.copyOf(edges),
                metadata(releaseVersion, documents.size(), distinctCompanies(documents), confidence(documents), !documents.isEmpty()));
    }

    public JobRole role(long roleId) {
        return dataProvider.findJobRole(roleId)
                .orElseThrow(() -> new BusinessException("ROLE_NOT_FOUND", "未找到目标岗位"));
    }

    public MarketCard marketCard(long roleId) {
        JobRole role = role(roleId);
        List<RawDocument> roleDocuments = roleDocuments(role);
        return marketCard(role, roleDocuments, dataProvider.dataMode());
    }

    private MarketCard marketCard(JobRole role, List<RawDocument> roleDocuments, String dataMode) {
        Set<String> companies = roleDocuments.stream().map(RawDocument::company).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        List<Evidence> evidence = roleDocuments.stream().limit(8).map(document -> evidence(document, firstSkill(role))).toList();
        Map<String, Double> cities = distribution(roleDocuments, RawDocument::city);
        Map<String, Double> levels = distribution(roleDocuments, RawDocument::level);
        double heat = roleDocuments.isEmpty() ? 0 : Math.min(100, companies.size() * 2.4 + role.requiredSkills().stream().mapToDouble(SkillRequirement::demandRate).average().orElse(0) * 55);
        String scope = "DEMO".equals(dataMode)
                ? "演示快照 · 2025-08 至 2026-07 · 六城市 · 初中级"
                : "当前试验快照 · " + roleDocuments.size() + " 条 JD · " + companies.size() + " 家企业";
        return new MarketCard(role, roleDocuments.size(), companies.size(), round(heat),
                roleDocuments.isEmpty() ? List.of() : List.of(15, 23, 34), cities, levels,
                roleDocuments.isEmpty() ? List.of() : commonCombinations(role), evidence, confidence(roleDocuments), scope,
                metadata(role.releaseVersion(), roleDocuments.size(), companies.size(), confidence(roleDocuments), !evidence.isEmpty(), dataMode));
    }

    public RoleMarketTimeline marketTimeline(long roleId) {
        JobRole role = role(roleId);
        List<RawDocument> roleDocuments = roleDocuments(role);
        if (!hasCompletePublicationTime(roleDocuments)) {
            return new RoleMarketTimeline(roleId, List.of());
        }
        Map<YearMonth, List<RawDocument>> documentsByMonth = roleDocuments.stream()
                .collect(Collectors.groupingBy(document -> YearMonth.from(document.publishedAt())));
        List<RoleMarketPeriod> periods = documentsByMonth.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .skip(Math.max(0, documentsByMonth.size() - 6L))
                .map(entry -> marketPeriod(entry.getKey(), entry.getValue(), role))
                .toList();
        return new RoleMarketTimeline(roleId, periods);
    }

    private RoleMarketPeriod marketPeriod(YearMonth period, List<RawDocument> documents, JobRole role) {
        List<Double> salaries = documents.stream()
                .map(RawDocument::salary)
                .map(this::salaryMedian)
                .filter(value -> value > 0)
                .sorted()
                .toList();
        int companies = distinctCompanies(documents);
        double skillStrength = role.requiredSkills().stream()
                .mapToDouble(SkillRequirement::demandRate)
                .average()
                .orElse(0);
        double demandIndex = Math.min(100, companies * 4 + skillStrength * 58);
        return new RoleMarketPeriod(period.toString(), documents.size(),
                Math.max(1, (int) Math.round(documents.size() * 0.24)),
                percentile(salaries, 0.25), percentile(salaries, 0.50), percentile(salaries, 0.75),
                round(demandIndex), documents.size(), confidence(documents));
    }

    private double salaryMedian(String salary) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)k[-~至](\\d+(?:\\.\\d+)?)k", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(salary.replace(" ", ""));
        if (!matcher.find()) {
            return 0;
        }
        return (Double.parseDouble(matcher.group(1)) + Double.parseDouble(matcher.group(2))) / 2;
    }

    private double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.round((values.size() - 1) * percentile);
        return round(values.get(index));
    }

    public List<EmergingCandidate> emergingCandidates() {
        return List.of();
    }

    public EvolutionReport evolution(long roleId) {
        JobRole role = role(roleId);
        List<RawDocument> documents = roleDocuments(role);
        if (!hasCompletePublicationTime(documents)) {
            return new EvolutionReport(roleId, role.name(), "", role.releaseVersion(), "当前试验快照",
                    List.of(), documents.stream().limit(10)
                    .map(document -> evidence(document, firstSkill(role))).toList(), "CURRENT_SNAPSHOT",
                    metadata(role.releaseVersion(), documents.size(), distinctCompanies(documents),
                            confidence(documents), !documents.isEmpty()));
        }
        YearMonth split = YearMonth.of(2026, 1);
        Map<String, Set<String>> previous = companySkills(documents.stream()
                .filter(document -> YearMonth.from(document.publishedAt()).isBefore(split)).toList());
        Map<String, Set<String>> current = companySkills(documents.stream()
                .filter(document -> !YearMonth.from(document.publishedAt()).isBefore(split)).toList());
        Set<String> allSkills = new HashSet<>();
        allSkills.addAll(previous.keySet());
        allSkills.addAll(current.keySet());
        int previousCompanies = distinctCompanies(documents.stream().filter(document -> YearMonth.from(document.publishedAt()).isBefore(split)).toList());
        int currentCompanies = distinctCompanies(documents.stream().filter(document -> !YearMonth.from(document.publishedAt()).isBefore(split)).toList());
        List<SkillEvolution> skills = allSkills.stream().map(skill -> {
            double previousRate = rate(previous.getOrDefault(skill, Set.of()).size(), previousCompanies);
            double currentRate = rate(current.getOrDefault(skill, Set.of()).size(), currentCompanies);
            double change = currentRate - previousRate;
            String changeType = classifyChange(previousRate, currentRate, change);
            String confidence = Math.min(previousCompanies, currentCompanies) >= 15 ? "正式趋势" : Math.min(previousCompanies, currentCompanies) >= 8 ? "趋势信号" : "证据不足";
            return new SkillEvolution(skill, changeType, round(change), confidence, List.of(
                    point("2025H2", previousRate, previousCompanies), point("2026H1", currentRate, currentCompanies)
            ));
        }).filter(item -> Math.abs(item.absoluteChange()) >= 0.05)
                .sorted(Comparator.comparingDouble((SkillEvolution item) -> Math.abs(item.absoluteChange())).reversed())
                .limit(12).toList();
        List<Evidence> evidence = documents.stream().limit(10).map(document -> evidence(document, firstSkill(role))).toList();
        String conclusion = Math.min(previousCompanies, currentCompanies) >= 15 ? "FORMAL" : "SIGNAL";
        return new EvolutionReport(roleId, role.name(), "2025.12", role.releaseVersion(), "同岗位、初中级、六城市、企业软件与 AI 软件",
                skills, evidence, conclusion, metadata(role.releaseVersion(), documents.size(), distinctCompanies(documents),
                confidence(documents), !evidence.isEmpty()));
    }

    public GraphView graph(long roleId, String viewType) {
        return graph(roleId, viewType, null, null, null, null);
    }

    public GraphView graph(long roleId, String viewType, String techStack, String jobLevel,
                           String roleVersion, String evidenceLayer) {
        JobRole role = role(roleId);
        List<RawDocument> documents = roleDocuments(role);
        if (!matchesRoleFilter(role, techStack, jobLevel, roleVersion, evidenceLayer)) {
            return new GraphView(role.releaseVersion(), viewType, List.of(), List.of(),
                    metadata(role.releaseVersion(), documents.size(), distinctCompanies(documents),
                            confidence(documents), !documents.isEmpty()));
        }
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        nodes.add(new GraphNode("role-" + role.id(), role.name(), "JOB_ROLE", role.family(), role.requiredSkills().size(), "PUBLISHED"));
        int edgeIndex = 1;
        for (SkillRequirement requirement : concat(role.requiredSkills(), role.bonusSkills()).stream()
                .filter(requirement -> matchesTechStack(requirement, techStack)).toList()) {
            String skillId = "skill-" + normalizeId(requirement.skill());
            nodes.add(new GraphNode(skillId, requirement.skill(), "SKILL", requirement.category(), requirement.evidenceCount(), "PUBLISHED"));
            edges.add(new GraphEdge("edge-" + edgeIndex++, "role-" + role.id(), skillId,
                    "REQUIRED".equals(requirement.requirementType()) ? "REQUIRES" : "PREFERS", requirement.demandRate(), "PUBLISHED"));
        }
        if (isBlankFilter(techStack)) role.responsibilities().forEach(responsibility -> {
            String id = "resp-" + normalizeId(responsibility);
            nodes.add(new GraphNode(id, responsibility, "RESPONSIBILITY", "职责", 5, "PUBLISHED"));
            edges.add(new GraphEdge("edge-" + (edges.size() + 1), "role-" + role.id(), id, "PERFORMS", 1, "PUBLISHED"));
        });
        dataProvider.findLearningResources().stream()
                .filter(resource -> resource.skills().stream().anyMatch(skill -> role.requiredSkills().stream().anyMatch(item -> item.skill().equals(skill))))
                .limit(5).forEach(resource -> {
                    String id = "resource-" + resource.id();
                    nodes.add(new GraphNode(id, resource.name(), "LEARNING_RESOURCE", resource.type(), resource.skills().size(), "REVIEWED"));
                    resource.skills().stream().filter(skill -> nodes.stream().anyMatch(node -> node.id().equals("skill-" + normalizeId(skill))))
                            .forEach(skill -> edges.add(new GraphEdge("edge-" + (edges.size() + 1), id, "skill-" + normalizeId(skill), "COVERS", 1, "PUBLISHED")));
                });
        return new GraphView(role.releaseVersion(), viewType, List.copyOf(nodes), List.copyOf(edges),
                metadata(role.releaseVersion(), documents.size(), distinctCompanies(documents), confidence(documents), !documents.isEmpty()));
    }

    private boolean matchesRoleFilter(JobRole role, String techStack, String jobLevel,
                                      String roleVersion, String requestedEvidenceLayer) {
        String status = publicRoleStatus(role);
        return (isBlankFilter(techStack) || concat(role.requiredSkills(), role.bonusSkills()).stream()
                        .anyMatch(requirement -> matchesTechStack(requirement, techStack)))
                && matchesText(role.level(), jobLevel)
                && matchesText(role.releaseVersion(), roleVersion)
                && (isBlankFilter(requestedEvidenceLayer)
                        || evidenceLayer(status).equalsIgnoreCase(requestedEvidenceLayer.trim())
                        || status.equalsIgnoreCase(requestedEvidenceLayer.trim()));
    }

    private boolean matchesTechStack(SkillRequirement requirement, String techStack) {
        return isBlankFilter(techStack) || matchesDimension(requirement.skill(), techStack)
                || matchesDimension(requirement.category(), techStack);
    }

    private boolean matchesText(String actual, String expected) {
        return isBlankFilter(expected) || actual != null && actual.toLowerCase().contains(expected.trim().toLowerCase());
    }

    private boolean matchesDimension(String actual, String expected) {
        return isBlankFilter(expected) || actual != null && actual.trim().equalsIgnoreCase(expected.trim());
    }

    private boolean isBlankFilter(String value) {
        return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim());
    }

    private List<String> technologyStack(JobRole role) {
        return concat(role.requiredSkills(), role.bonusSkills()).stream()
                .flatMap(requirement -> java.util.stream.Stream.of(requirement.category(), requirement.skill()))
                .filter(value -> value != null && !value.isBlank()).distinct().limit(12).toList();
    }

    private String evidenceLayer(String status) {
        return "PUBLISHED".equals(status) ? "REAL_EVIDENCE" : status;
    }

    public RoleSkillEvidence skillEvidence(long roleId, String skill) {
        return dataProvider.findRoleSkillEvidence(roleId, skill).orElseGet(() -> fallbackSkillEvidence(roleId, skill));
    }

    private RoleSkillEvidence fallbackSkillEvidence(long roleId, String skill) {
        JobRole role = role(roleId);
        SkillRequirement requirement = concat(role.requiredSkills(), role.bonusSkills()).stream()
                .filter(item -> item.skill().equalsIgnoreCase(skill)).findFirst()
                .orElseThrow(() -> new BusinessException("ROLE_SKILL_NOT_FOUND", "未找到该岗位技能证据"));
        List<RawDocument> documents = roleDocuments(role).stream()
                .filter(document -> document.content().contains(requirement.skill())).toList();
        int companies = distinctCompanies(documents);
        List<RoleSkillEvidenceItem> evidence = documents.stream().limit(8).map(document -> {
            int start = Math.max(0, document.content().indexOf(requirement.skill()));
            int end = Math.min(document.content().length(), start + Math.max(32, requirement.skill().length()));
            return new RoleSkillEvidenceItem(document.id(), document.jobTitle(), document.company(),
                    document.content().substring(start, end), start, end, "");
        }).toList();
        return new RoleSkillEvidence(roleId, role.name(), requirement.skill(), requirement.requirementType(),
                requirement.demandRate(), documents.size(), companies, role.releaseVersion(),
                "DEMO".equals(dataProvider.dataMode()) ? "演示快照" : "当前试验快照", evidence);
    }

    public List<AdjacentRole> adjacentRoles(long roleId) {
        JobRole target = role(roleId);
        Set<String> targetSkills = skills(target);
        return dataProvider.findJobRoles().stream().filter(role -> role.id() != roleId && !role.requiredSkills().isEmpty()).map(role -> {
            Set<String> candidateSkills = skills(role);
            Set<String> intersection = new HashSet<>(targetSkills);
            intersection.retainAll(candidateSkills);
            Set<String> union = new HashSet<>(targetSkills);
            union.addAll(candidateSkills);
            double similarity = union.isEmpty() ? 0 : intersection.size() / (double) union.size();
            Set<String> missing = new HashSet<>(candidateSkills);
            missing.removeAll(intersection);
            String transition = target.code().contains("AGENT") && role.code().contains("JAVA")
                    ? "AI 应用后端工程师" : "";
            return new AdjacentRole(role.id(), role.name(), round(similarity * 100), missing.size(), round(1 - similarity), 0.88,
                    intersection.stream().sorted().toList(), missing.stream().sorted().toList(), transition);
        }).sorted(Comparator.comparingDouble(AdjacentRole::transferDistance)).toList();
    }

    private List<RawDocument> roleDocuments(JobRole role) {
        if (role.requiredSkills().isEmpty()) return List.of();
        return roleDocuments(role, dataProvider.findDocuments());
    }

    private List<RawDocument> roleDocuments(JobRole role, List<RawDocument> documents) {
        if (role.requiredSkills().isEmpty()) return List.of();
        Set<Long> publishedDocumentIds = dataProvider.findRoleDocumentIds(role.id());
        if (!publishedDocumentIds.isEmpty()) {
            return documents.stream().filter(document -> publishedDocumentIds.contains(document.id())).toList();
        }
        return documents.stream()
                .filter(document -> "RECRUITMENT_JD".equals(document.sourceType()) || !document.demoData())
                .filter(document -> hasTargetRoleCode(document.industry())
                        ? role.code().equals(document.industry())
                        : document.jobTitle().contains(role.name().replace("应用", "").replace("开发", ""))
                        || role.requiredSkills().stream().filter(skill -> document.content().contains(skill.skill())).count()
                        >= Math.min(3, role.requiredSkills().size()))
                .toList();
    }

    private boolean hasTargetRoleCode(String value) {
        return value != null && value.matches("[A-Z0-9_]+_ENGINEER");
    }

    private Evidence evidence(RawDocument document, String skill) {
        int position = Math.max(0, document.content().indexOf(skill));
        int end = Math.min(document.content().length(), position + Math.max(skill.length(), 32));
        String quote = document.content().substring(position, end);
        return new Evidence(document.id(), document.sourceName(), document.company(), document.publishedAt(), quote, position, end,
                document.publishedAt() == null ? 1 : round(qualityService.timeWeight(document.publishedAt(), REFERENCE_TIME)),
                "EVIDENCE_VALID");
    }

    private String firstSkill(JobRole role) {
        return role.requiredSkills().isEmpty() ? role.name() : role.requiredSkills().get(0).skill();
    }

    private Map<String, Double> distribution(List<RawDocument> documents, Function<RawDocument, String> classifier) {
        Map<String, Long> counts = documents.stream().map(classifier).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> rate(entry.getValue(), documents.size()),
                (left, right) -> left, LinkedHashMap::new));
    }

    private List<String> commonCombinations(JobRole role) {
        List<String> skills = role.requiredSkills().stream().map(SkillRequirement::skill).toList();
        List<String> combinations = new ArrayList<>();
        for (int index = 0; index + 2 < skills.size() && combinations.size() < 3; index += 2) {
            combinations.add(String.join(" + ", skills.subList(index, Math.min(index + 3, skills.size()))));
        }
        return combinations;
    }

    private double confidence(List<RawDocument> documents) {
        int companies = distinctCompanies(documents);
        return companies == 0 ? 0 : round(Math.min(0.98, 0.52 + companies * 0.015));
    }

    private ResultMetadata metadata(String release, int sampleSize, int companies, double confidence, boolean evidenceAvailable) {
        return metadata(release, sampleSize, companies, confidence, evidenceAvailable, dataProvider.dataMode());
    }

    private ResultMetadata metadata(String release, int sampleSize, int companies, double confidence,
                                    boolean evidenceAvailable, String dataMode) {
        String timeWindow = "DEMO".equals(dataMode) ? "2025-08 至 2026-07" : "当前试验快照";
        return new ResultMetadata(dataMode, release, timeWindow, sampleSize,
                companies, confidence, evidenceAvailable);
    }

    private boolean hasCompletePublicationTime(List<RawDocument> documents) {
        return !documents.isEmpty() && documents.stream().allMatch(document -> document.publishedAt() != null);
    }

    private Map<String, Set<String>> companySkills(List<RawDocument> documents) {
        Map<String, Set<String>> companiesBySkill = new HashMap<>();
        for (RawDocument document : documents) {
            for (ExtractedFact fact : normalizationService.extract(document.content())) {
                if (!"NEGATED".equals(fact.requirementType())) {
                    companiesBySkill.computeIfAbsent(fact.normalizedValue(), ignored -> new HashSet<>()).add(document.company());
                }
            }
        }
        return companiesBySkill;
    }

    private EvolutionPoint point(String period, double rate, int companies) {
        double margin = companies == 0 ? 0 : 1.96 * Math.sqrt(rate * (1 - rate) / companies);
        return new EvolutionPoint(period, round(rate), companies, round(Math.max(0, rate - margin)), round(Math.min(1, rate + margin)));
    }

    private String classifyChange(double previous, double current, double change) {
        if (previous < 0.05 && current >= 0.15) return "新增";
        if (change >= 0.10) return "增强";
        if (change <= -0.10 && current < 0.05) return "候选删除";
        if (change <= -0.10) return "弱化";
        return "稳定";
    }

    private Set<String> skills(JobRole role) {
        return concat(role.requiredSkills(), role.bonusSkills()).stream().map(SkillRequirement::skill).collect(Collectors.toSet());
    }

    private List<SkillRequirement> concat(List<SkillRequirement> left, List<SkillRequirement> right) {
        List<SkillRequirement> result = new ArrayList<>(left.size() + right.size());
        result.addAll(left);
        result.addAll(right);
        return result;
    }

    private int distinctCompanies(List<RawDocument> documents) {
        return (int) documents.stream().map(RawDocument::company).filter(value -> !value.isBlank()).distinct().count();
    }

    private double rate(long value, long total) {
        return total == 0 ? 0 : round(value / (double) total);
    }

    private String normalizeId(String value) {
        return Integer.toHexString(value.hashCode());
    }

    private double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
