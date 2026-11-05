package com.zhigang.platform.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.evolution.RoleEvolutionModels.ChangeDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.EvidenceDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.Policy;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.SkillAnchor;
import com.zhigang.platform.evolution.RoleEvolutionModels.SourceDocument;
import com.zhigang.platform.evolution.RoleEvolutionModels.WindowDraft;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RoleEvolutionStatisticsEngine {
    private final ObjectMapper objectMapper;
    private final RoleEvolutionPolicyLoader policyLoader;

    public RoleEvolutionStatisticsEngine(ObjectMapper objectMapper, RoleEvolutionPolicyLoader policyLoader) {
        this.objectMapper = objectMapper;
        this.policyLoader = policyLoader;
    }

    public RunDraft analyze(long roleId, String roleCode, String roleName, List<SourceDocument> sourceDocuments) {
        Policy policy = policyLoader.policy();
        List<SourceDocument> dated = sourceDocuments.stream().filter(document -> document.publishedAt() != null).toList();
        if (dated.isEmpty()) {
            return insufficient(roleId, roleCode, roleName, policy, "没有包含明确发布日期的真实 JD");
        }
        LocalDate latest = dated.stream().map(SourceDocument::publishedAt).max(LocalDate::compareTo).orElseThrow();
        YearMonth currentEndMonth = YearMonth.from(latest);
        YearMonth currentStartMonth = currentEndMonth.minusMonths(policy.windowMonths() - 1L);
        YearMonth baselineEndMonth = currentStartMonth.minusMonths(1);
        YearMonth baselineStartMonth = baselineEndMonth.minusMonths(policy.windowMonths() - 1L);
        LocalDate baselineStart = baselineStartMonth.atDay(1);
        LocalDate baselineEnd = baselineEndMonth.atEndOfMonth();
        LocalDate currentStart = currentStartMonth.atDay(1);
        LocalDate currentEnd = currentEndMonth.atEndOfMonth();

        List<SourceDocument> baselineAll = inWindow(dated, baselineStart, baselineEnd);
        List<SourceDocument> currentAll = inWindow(dated, currentStart, currentEnd);
        Set<String> commonSources = sources(baselineAll);
        commonSources.retainAll(sources(currentAll));
        List<SourceDocument> baseline = baselineAll.stream().filter(item -> commonSources.contains(item.source())).toList();
        List<SourceDocument> current = currentAll.stream().filter(item -> commonSources.contains(item.source())).toList();
        WindowDraft baselineWindow = window("BASELINE", baselineStart, baselineEnd, baseline);
        WindowDraft currentWindow = window("CURRENT", currentStart, currentEnd, current);
        boolean sufficient = baselineWindow.documentCount() >= policy.minimumDocumentsPerWindow()
                && currentWindow.documentCount() >= policy.minimumDocumentsPerWindow()
                && baselineWindow.companyCount() >= policy.minimumCompaniesPerWindow()
                && currentWindow.companyCount() >= policy.minimumCompaniesPerWindow()
                && commonSources.size() >= policy.minimumSourcesPerWindow();
        String scope = "同岗位代码 " + roleCode + "；共同来源 " + String.join("、", commonSources)
                + "；全国、全职级；按独立企业去重；候选技能与无效锚点不参与统计";
        if (!sufficient) {
            return run(roleId, roleCode, roleName, policy, "INSUFFICIENT_DATA", "INSUFFICIENT_TIME_DATA", scope,
                    List.of(baselineWindow, currentWindow), List.of(), dated);
        }

        Map<String, SkillMetric> baselineMetrics = metrics(baseline);
        Map<String, SkillMetric> currentMetrics = metrics(current);
        Map<String, SkillMetric> baselineSensitivity = metrics(baselineAll);
        Map<String, SkillMetric> currentSensitivity = metrics(currentAll);
        Set<String> skillNames = new LinkedHashSet<>(baselineMetrics.keySet());
        skillNames.addAll(currentMetrics.keySet());
        int baselineCompanies = baselineWindow.companyCount();
        int currentCompanies = currentWindow.companyCount();
        int baselineAllCompanies = companies(baselineAll);
        int currentAllCompanies = companies(currentAll);
        List<ChangeDraft> changes = new ArrayList<>();
        for (String skill : skillNames) {
            SkillMetric before = baselineMetrics.getOrDefault(skill, SkillMetric.empty());
            SkillMetric after = currentMetrics.getOrDefault(skill, SkillMetric.empty());
            Interval beforeInterval = wilson(before.companies().size(), baselineCompanies, policy.confidenceZ());
            Interval afterInterval = wilson(after.companies().size(), currentCompanies, policy.confidenceZ());
            double beforeRate = rate(before.companies().size(), baselineCompanies);
            double afterRate = rate(after.companies().size(), currentCompanies);
            double difference = afterRate - beforeRate;
            Interval differenceInterval = differenceInterval(beforeRate, beforeInterval, afterRate, afterInterval);
            double sensitivityDifference = rate(currentSensitivity.getOrDefault(skill, SkillMetric.empty()).companies().size(), currentAllCompanies)
                    - rate(baselineSensitivity.getOrDefault(skill, SkillMetric.empty()).companies().size(), baselineAllCompanies);
            String sensitivity = direction(sensitivityDifference);
            boolean directionConsistent = "STABLE".equals(sensitivity) || direction(difference).equals(sensitivity);
            boolean intervalExcludesZero = differenceInterval.lower() > 0 || differenceInterval.upper() < 0;
            boolean signal = Math.abs(difference) >= policy.minimumAbsoluteChange();
            boolean formal = signal && intervalExcludesZero && directionConsistent;
            int absentWindows = consecutiveAbsentWindows(skill, current, currentStart, currentEnd);
            String changeType = classify(before.companies().size(), after.companies().size(), difference, signal,
                    absentWindows, policy);
            boolean publishable = formal && !"STABLE".equals(changeType)
                    && (!"REMOVAL_CANDIDATE".equals(changeType)
                    || absentWindows >= policy.removalConsecutiveWindows());
            String confidence = publishable ? "FORMAL_CHANGE" : signal ? "OBSERVATION_SIGNAL" : "STABLE";
            List<EvidenceDraft> evidence = evidence(before, "BASELINE", policy.evidenceLimitPerWindow());
            evidence = concat(evidence, evidence(after, "CURRENT", policy.evidenceLimitPerWindow()));
            changes.add(new ChangeDraft(firstSkillId(before, after), skill, changeType, confidence,
                    before.documents().size(), before.companies().size(), round(beforeRate), round(beforeInterval.lower()),
                    round(beforeInterval.upper()), after.documents().size(), after.companies().size(), round(afterRate),
                    round(afterInterval.lower()), round(afterInterval.upper()), round(difference),
                    round(differenceInterval.lower()), round(differenceInterval.upper()), sensitivity,
                    absentWindows, publishable, publishable, evidence));
        }
        changes.sort(Comparator.comparing(ChangeDraft::publishable).reversed()
                .thenComparingDouble(item -> -Math.abs(item.absoluteChange())).thenComparing(ChangeDraft::skill));
        long formalCount = changes.stream().filter(ChangeDraft::publishable).count();
        long signalCount = changes.stream().filter(item -> "OBSERVATION_SIGNAL".equals(item.confidenceLevel())).count();
        String conclusion = formalCount > 0 ? "FORMAL_CHANGES" : signalCount > 0 ? "SIGNAL_ONLY" : "STABLE";
        return run(roleId, roleCode, roleName, policy, "DRAFT_READY", conclusion, scope,
                List.of(baselineWindow, currentWindow), changes, dated);
    }

    private RunDraft insufficient(long roleId, String roleCode, String roleName, Policy policy, String reason) {
        return run(roleId, roleCode, roleName, policy, "INSUFFICIENT_DATA", "INSUFFICIENT_TIME_DATA",
                reason, List.of(), List.of(), List.of());
    }

    private RunDraft run(long roleId, String roleCode, String roleName, Policy policy, String status,
                         String conclusion, String sampleControl, List<WindowDraft> windows,
                         List<ChangeDraft> changes, List<SourceDocument> input) {
        String policyJson = json(policy);
        String policyHash = hash(policyJson);
        String inputHash = hash(input.stream().sorted(Comparator.comparingLong(SourceDocument::documentId))
                .map(this::inputFingerprint).collect(Collectors.joining("|")));
        String runCode = "evolution-" + roleCode.toLowerCase() + "-"
                + hash(inputHash + ":" + policyHash).substring(0, 12);
        String scopeJson = json(Map.of("roleCode", roleCode, "sampleControl", sampleControl,
                "windows", windows, "inputDocuments", input.size()));
        return new RunDraft(runCode, roleId, roleCode, roleName, policy.version(), policyHash, inputHash,
                status, conclusion, sampleControl, "Wilson 95% + Newcombe 独立比例差区间 + 全来源敏感性分析",
                scopeJson, windows, changes);
    }

    private WindowDraft window(String type, LocalDate start, LocalDate end, List<SourceDocument> documents) {
        List<String> sourceNames = sources(documents).stream().sorted().toList();
        return new WindowDraft(type, start + " 至 " + end, start, end, documents.size(), companies(documents),
                sourceNames.size(), sourceNames, "全国", "全职级",
                hash(documents.stream().map(SourceDocument::documentId).sorted().map(String::valueOf).collect(Collectors.joining(","))));
    }

    private List<SourceDocument> inWindow(List<SourceDocument> documents, LocalDate start, LocalDate end) {
        return documents.stream().filter(item -> !item.publishedAt().isBefore(start) && !item.publishedAt().isAfter(end)).toList();
    }

    private Map<String, SkillMetric> metrics(List<SourceDocument> documents) {
        Map<String, SkillMetricBuilder> builders = new LinkedHashMap<>();
        for (SourceDocument document : documents) {
            Map<String, SkillAnchor> anchors = document.skills().stream()
                    .filter(SkillAnchor::anchorValid).filter(anchor -> !"OPPOSE".equals(anchor.direction()))
                    .collect(Collectors.toMap(SkillAnchor::skill, anchor -> anchor, (left, right) -> left, LinkedHashMap::new));
            anchors.forEach((skill, anchor) -> builders.computeIfAbsent(skill, ignored -> new SkillMetricBuilder())
                    .add(document, anchor));
        }
        return builders.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().build(), (left, right) -> left, LinkedHashMap::new));
    }

    private int consecutiveAbsentWindows(String skill, List<SourceDocument> current, LocalDate start, LocalDate end) {
        int absentWindows = 0;
        YearMonth firstMonth = YearMonth.from(start);
        for (YearMonth month = YearMonth.from(end); !month.isBefore(firstMonth); month = month.minusMonths(1)) {
            List<SourceDocument> monthDocuments = inWindow(current, month.atDay(1), month.atEndOfMonth());
            if (monthDocuments.isEmpty() || monthDocuments.stream().anyMatch(item -> hasSkill(item, skill))) break;
            absentWindows++;
        }
        return absentWindows;
    }

    private boolean hasSkill(SourceDocument document, String skill) {
        return document.skills().stream().anyMatch(anchor -> anchor.anchorValid() && !"OPPOSE".equals(anchor.direction())
                && skill.equals(anchor.skill()));
    }

    private String classify(int baselineCompanies, int currentCompanies, double difference, boolean signal,
                            int absentWindows, Policy policy) {
        if (!signal) return "STABLE";
        if (baselineCompanies <= policy.newSkillBaselineMaximumCompanies()
                && currentCompanies >= policy.newSkillCurrentMinimumCompanies() && difference > 0) return "NEW";
        if (currentCompanies == 0 && baselineCompanies >= policy.removalBaselineMinimumCompanies()
                && absentWindows >= policy.removalConsecutiveWindows()) return "REMOVAL_CANDIDATE";
        return difference > 0 ? "INCREASED" : "DECREASED";
    }

    private Interval wilson(int successes, int total, double z) {
        if (total <= 0) return new Interval(0, 0);
        double p = successes / (double) total;
        double denominator = 1 + z * z / total;
        double center = (p + z * z / (2 * total)) / denominator;
        double margin = z * Math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator;
        return new Interval(Math.max(0, center - margin), Math.min(1, center + margin));
    }

    private Interval differenceInterval(double beforeRate, Interval before, double afterRate, Interval after) {
        double difference = afterRate - beforeRate;
        double lower = difference - Math.sqrt(Math.pow(afterRate - after.lower(), 2)
                + Math.pow(before.upper() - beforeRate, 2));
        double upper = difference + Math.sqrt(Math.pow(after.upper() - afterRate, 2)
                + Math.pow(beforeRate - before.lower(), 2));
        return new Interval(Math.max(-1, lower), Math.min(1, upper));
    }

    private List<EvidenceDraft> evidence(SkillMetric metric, String window, int limit) {
        return metric.anchors().stream().sorted(Comparator.comparing((AnchoredDocument item) -> item.document().company())
                        .thenComparing(item -> item.document().publishedAt())).filter(item -> item.anchor().anchorValid())
                .limit(limit).map(item -> new EvidenceDraft(window, item.document().documentId(), item.document().title(),
                        item.document().company(), item.document().source(), item.document().sourceUrl(),
                        item.document().publishedAt(), item.anchor().quote(), item.anchor().startOffset(),
                        item.anchor().endOffset(), true, item.anchor().direction())).toList();
    }

    private Long firstSkillId(SkillMetric before, SkillMetric after) {
        return concat(before.anchors(), after.anchors()).stream().map(item -> item.anchor().skillId())
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private Set<String> sources(List<SourceDocument> documents) {
        return documents.stream().map(SourceDocument::source).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
    }

    private int companies(List<SourceDocument> documents) {
        return (int) documents.stream().map(SourceDocument::company).filter(value -> value != null && !value.isBlank()).distinct().count();
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0 : numerator / (double) denominator;
    }

    private String direction(double value) {
        return value > 0.05 ? "UP" : value < -0.05 ? "DOWN" : "STABLE";
    }

    private double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("岗位演化审计数据无法序列化", exception);
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String inputFingerprint(SourceDocument document) {
        String anchors = document.skills().stream().sorted(Comparator.comparing(SkillAnchor::skill)
                        .thenComparingInt(SkillAnchor::startOffset).thenComparingInt(SkillAnchor::endOffset))
                .map(anchor -> safe(anchor.skill()) + ":" + anchor.startOffset() + ":" + anchor.endOffset()
                        + ":" + safe(anchor.quote()) + ":" + anchor.anchorValid() + ":" + safe(anchor.direction()))
                .collect(Collectors.joining(","));
        return document.documentId() + ":" + document.publishedAt() + ":" + safe(document.company())
                + ":" + safe(document.source()) + ":" + anchors;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> concat(List<T> left, List<T> right) {
        List<T> result = new ArrayList<>(left.size() + right.size());
        result.addAll(left);
        result.addAll(right);
        return result;
    }

    private record Interval(double lower, double upper) {
    }

    private record AnchoredDocument(SourceDocument document, SkillAnchor anchor) {
    }

    private record SkillMetric(Set<Long> documents, Set<String> companies, List<AnchoredDocument> anchors) {
        static SkillMetric empty() {
            return new SkillMetric(Set.of(), Set.of(), List.of());
        }
    }

    private static final class SkillMetricBuilder {
        private final Set<Long> documents = new LinkedHashSet<>();
        private final Set<String> companies = new LinkedHashSet<>();
        private final List<AnchoredDocument> anchors = new ArrayList<>();

        void add(SourceDocument document, SkillAnchor anchor) {
            documents.add(document.documentId());
            companies.add(document.company());
            anchors.add(new AnchoredDocument(document, anchor));
        }

        SkillMetric build() {
            return new SkillMetric(Set.copyOf(documents), Set.copyOf(companies), List.copyOf(anchors));
        }
    }
}
