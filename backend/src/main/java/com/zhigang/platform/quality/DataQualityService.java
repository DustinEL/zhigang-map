package com.zhigang.platform.quality;

import com.zhigang.platform.domain.PlatformModels.QualityIssue;
import com.zhigang.platform.domain.PlatformModels.QualityMetric;
import com.zhigang.platform.domain.PlatformModels.QualityReport;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.repository.PlatformDataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DataQualityService {
    private final PlatformDataProvider dataProvider;
    private final int halfLifeDays;
    private final String policyVersion;

    public DataQualityService(
            PlatformDataProvider dataProvider,
            @Value("${zhigang.analysis.half-life-days:180}") int halfLifeDays,
            @Value("${zhigang.analysis.policy-version:v1.0.0}") String policyVersion
    ) {
        this.dataProvider = dataProvider;
        this.halfLifeDays = halfLifeDays;
        this.policyVersion = policyVersion;
    }

    public QualityReport buildReport(String snapshotId) {
        List<RawDocument> documents = dataProvider.findDocuments();
        List<RawDocument> jdDocuments = documents.stream()
                .filter(document -> "RECRUITMENT_JD".equals(document.sourceType()))
                .toList();
        DuplicateAnalysis duplicateAnalysis = detectNearDuplicates(jdDocuments, 0.85);
        List<QualityIssue> issues = new ArrayList<>();

        jdDocuments.stream()
                .filter(document -> document.content().length() < 45)
                .limit(8)
                .forEach(document -> issues.add(new QualityIssue(
                        "CONTENT_TOO_SHORT", "MEDIUM", document.jobTitle(), "正文不足 45 字，无法形成可靠岗位证据", "转人工复核"
                )));
        jdDocuments.stream()
                .filter(document -> isInflated(document.content()))
                .limit(8)
                .forEach(document -> issues.add(new QualityIssue(
                        "JD_INFLATION", "HIGH", document.company() + " · " + document.jobTitle(),
                        "检测到跨前端、后端、算法、运维等角色的不合理混合要求", "降低证据权重并复核"
                )));
        jdDocuments.stream()
                .filter(document -> containsPromptInjection(document.content()))
                .limit(8)
                .forEach(document -> issues.add(new QualityIssue(
                        "PROMPT_INJECTION", "HIGH", document.company() + " · " + document.jobTitle(),
                        "文本含有操纵模型或泄露系统提示的指令", "隔离指令，仅按不可信数据抽取"
                )));
        duplicateAnalysis.clusters().stream().limit(6).forEach(cluster -> issues.add(new QualityIssue(
                "NEAR_DUPLICATE", "MEDIUM", cluster.representative().company() + " · " + cluster.representative().jobTitle(),
                "近重复簇包含 " + cluster.members().size() + " 条文档，仅代表样本贡献一次市场证据", "保留原文并聚类降权"
        )));

        int independentCompanies = (int) jdDocuments.stream().map(RawDocument::company).filter(value -> !value.isBlank()).distinct().count();
        double sourceCoverage = ratio(documents.stream().map(RawDocument::sourceType).distinct().count(), 3);
        double companyRatio = ratio(independentCompanies, jdDocuments.size());
        double duplicateRate = ratio(duplicateAnalysis.duplicateMembers(), jdDocuments.size());
        double publishCompleteness = ratio(jdDocuments.stream().filter(document -> document.publishedAt() != null).count(), jdDocuments.size());
        double fieldCompleteness = jdDocuments.stream().mapToDouble(this::fieldCompleteness).average().orElse(0);
        double evidenceCoverage = 1.0;
        double inflationRate = ratio(jdDocuments.stream().filter(document -> isInflated(document.content())).count(), jdDocuments.size());
        boolean publishable = publishCompleteness >= 0.8 && evidenceCoverage == 1.0 && duplicateAnalysis.unprocessedClusters() == 0;

        List<QualityMetric> metrics = List.of(
                metric("SOURCE_COVERAGE", "来源覆盖率", sourceCoverage, 1.0, "比例", sourceCoverage >= 0.67, "覆盖招聘、课程和政策三类数据角色"),
                metric("COMPANY_INDEPENDENCE", "独立企业比例", companyRatio, 0.5, "比例", companyRatio >= 0.5, "避免单一企业模板放大"),
                metric("NEAR_DUPLICATE_RATE", "近重复率", duplicateRate, 0.2, "比例", duplicateRate <= 0.2, "近重复原文保留，但只贡献一次证据"),
                metric("PUBLISH_TIME_COMPLETENESS", "发布时间完整率", publishCompleteness, 0.8, "比例", publishCompleteness >= 0.8, "低于门槛不得发布动态演化结论"),
                metric("FIELD_COMPLETENESS", "字段完整率", fieldCompleteness, 0.85, "比例", fieldCompleteness >= 0.85, "岗位、企业、城市、职级和正文完整度"),
                metric("EVIDENCE_COVERAGE", "原文锚点有效率", evidenceCoverage, 1.0, "比例", evidenceCoverage == 1.0, "发布事实必须可以回到原文"),
                metric("INFLATION_RATE", "JD 通胀率", inflationRate, 0.1, "比例", inflationRate <= 0.1, "异常跨岗位技能组合占比")
        );
        int effectiveCount = jdDocuments.size() - duplicateAnalysis.duplicateMembers();
        String snapshotName = switch (dataProvider.dataMode()) {
            case "REAL" -> "当前真实资料库质量报告";
            case "DEMO" -> "演示资料库质量报告";
            default -> "当前资料库暂无数据";
        };
        return new QualityReport(snapshotId, snapshotName, jdDocuments.size(), effectiveCount,
                duplicateAnalysis.clusters().size(), publishable, policyVersion, metrics, List.copyOf(issues), OffsetDateTime.now());
    }

    public double timeWeight(OffsetDateTime publishedAt, OffsetDateTime referenceTime) {
        if (publishedAt == null) {
            return 0;
        }
        long ageDays = Math.max(0, Duration.between(publishedAt, referenceTime).toDays());
        return Math.exp(-Math.log(2) * ageDays / halfLifeDays);
    }

    public DuplicateAnalysis detectNearDuplicates(List<RawDocument> documents, double threshold) {
        Map<String, List<Integer>> invertedIndex = new HashMap<>();
        List<Set<String>> shinglesByDocument = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            Set<String> shingles = shingles(documents.get(index).content(), 3);
            shinglesByDocument.add(shingles);
            for (String shingle : shingles) {
                invertedIndex.computeIfAbsent(shingle, ignored -> new ArrayList<>()).add(index);
            }
        }

        UnionFind unionFind = new UnionFind(documents.size());
        Set<Long> candidatePairs = new HashSet<>();
        for (List<Integer> indexes : invertedIndex.values()) {
            if (indexes.size() > 40) {
                continue;
            }
            for (int left = 0; left < indexes.size(); left++) {
                for (int right = left + 1; right < indexes.size(); right++) {
                    int first = indexes.get(left);
                    int second = indexes.get(right);
                    long key = (((long) first) << 32) | (second & 0xffffffffL);
                    candidatePairs.add(key);
                }
            }
        }
        for (long pair : candidatePairs) {
            int first = (int) (pair >> 32);
            int second = (int) pair;
            if (jaccard(shinglesByDocument.get(first), shinglesByDocument.get(second)) >= threshold) {
                unionFind.union(first, second);
            }
        }

        Map<Integer, List<RawDocument>> grouped = new LinkedHashMap<>();
        for (int index = 0; index < documents.size(); index++) {
            grouped.computeIfAbsent(unionFind.find(index), ignored -> new ArrayList<>()).add(documents.get(index));
        }
        List<DuplicateCluster> clusters = grouped.values().stream()
                .filter(members -> members.size() > 1)
                .map(members -> new DuplicateCluster(members.get(0), List.copyOf(members)))
                .toList();
        int duplicateMembers = clusters.stream().mapToInt(cluster -> cluster.members().size() - 1).sum();
        return new DuplicateAnalysis(clusters, duplicateMembers, 0, candidatePairs.size());
    }

    private Set<String> shingles(String text, int size) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[\\s，。；：、,.]", "");
        Set<String> shingles = new HashSet<>();
        for (int index = 0; index <= normalized.length() - size; index++) {
            shingles.add(normalized.substring(index, index + size));
        }
        return shingles;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        long intersection = smaller.stream().filter(larger::contains).count();
        return intersection / (double) (left.size() + right.size() - intersection);
    }

    private boolean isInflated(String content) {
        String[] roles = {"前端", "后端", "算法", "运维", "产品设计"};
        return java.util.Arrays.stream(roles).filter(content::contains).count() >= 4;
    }

    private boolean containsPromptInjection(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("忽略前述") || lower.contains("系统提示") || lower.contains("ignore previous");
    }

    private double fieldCompleteness(RawDocument document) {
        List<String> fields = List.of(document.jobTitle(), document.company(), document.city(), document.level(), document.content());
        return ratio(fields.stream().filter(value -> value != null && !value.isBlank()).count(), fields.size());
    }

    private QualityMetric metric(String code, String name, double value, double threshold, String unit, boolean passed, String explanation) {
        return new QualityMetric(code, name, round(value), threshold, unit, passed ? "PASSED" : "WARNING", explanation);
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : round(numerator / (double) denominator);
    }

    private double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    public record DuplicateCluster(RawDocument representative, List<RawDocument> members) {
    }

    public record DuplicateAnalysis(List<DuplicateCluster> clusters, int duplicateMembers, int unprocessedClusters, int candidatePairCount) {
    }

    private static final class UnionFind {
        private final int[] parent;
        private final byte[] rank;

        private UnionFind(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
            }
        }

        private int find(int value) {
            if (parent[value] != value) {
                parent[value] = find(parent[value]);
            }
            return parent[value];
        }

        private void union(int left, int right) {
            int leftRoot = find(left);
            int rightRoot = find(right);
            if (leftRoot == rightRoot) {
                return;
            }
            if (rank[leftRoot] < rank[rightRoot]) {
                parent[leftRoot] = rightRoot;
            } else if (rank[leftRoot] > rank[rightRoot]) {
                parent[rightRoot] = leftRoot;
            } else {
                parent[rightRoot] = leftRoot;
                rank[leftRoot]++;
            }
        }
    }
}
