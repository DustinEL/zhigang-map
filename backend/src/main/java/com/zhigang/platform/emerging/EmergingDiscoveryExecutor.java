package com.zhigang.platform.emerging;

import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateSeed;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.ClusterAssignment;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.ClusterRequest;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.ClusterResponse;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.FieldEvidence;
import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.repository.PlatformDataProvider.DocumentSemanticFacts;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EmergingDiscoveryExecutor {
    private final EmergingDiscoveryRepository repository;
    private final EmergingDiscoveryPolicy policy;
    private final EmbeddingClient embeddingClient;
    private final DiscoveryEmbeddingCache embeddingCache;
    private final ClusterWorkerClient clusterWorker;
    private final SkillNormalizationService normalizationService;
    private final EmergingRoleDefinitionGenerator definitionGenerator;

    public EmergingDiscoveryExecutor(EmergingDiscoveryRepository repository, EmergingDiscoveryPolicy policy,
                                     EmbeddingClient embeddingClient, DiscoveryEmbeddingCache embeddingCache,
                                     ClusterWorkerClient clusterWorker, SkillNormalizationService normalizationService,
                                     EmergingRoleDefinitionGenerator definitionGenerator) {
        this.repository = repository;
        this.policy = policy;
        this.embeddingClient = embeddingClient;
        this.embeddingCache = embeddingCache;
        this.clusterWorker = clusterWorker;
        this.normalizationService = normalizationService;
        this.definitionGenerator = definitionGenerator;
    }

    @Async
    public void execute(long runId, List<RawDocument> documents, List<JobRole> roles,
                        Map<Long, DocumentSemanticFacts> semanticFacts) {
        repository.markRunning(runId);
        try {
            Map<Long, SemanticProfile> profiles = documents.stream().collect(Collectors.toMap(RawDocument::id,
                    document -> semanticProfile(document, semanticFacts.get(document.id())),
                    (left, right) -> left, LinkedHashMap::new));
            List<List<Double>> vectors = vectors(documents, profiles);
            ClusterResponse clustering = clusterWorker.cluster(new ClusterRequest(documents.stream().map(RawDocument::id).toList(),
                    vectors, policy.clustering().minimumClusterSize(), policy.clustering().minimumSamples()));
            Map<Long, List<Double>> vectorByDocument = new LinkedHashMap<>();
            for (int index = 0; index < documents.size(); index++) vectorByDocument.put(documents.get(index).id(), vectors.get(index));
            List<List<Double>> roleVectors = roles.isEmpty() ? List.of()
                    : multiViewVectors(roles.stream().map(this::semanticProfile).toList());
            List<CandidateSeed> seeds = seeds(documents, roles, roleVectors, vectorByDocument,
                    clustering.assignments(), semanticFacts);
            int noise = (int) clustering.assignments().stream().filter(ClusterAssignment::noise).count();
            int candidateCount = (int) seeds.stream().filter(seed -> List.of(
                    "EMERGING_OBSERVATION", "AUTO_PUBLISH_READY", "WAITING_GENERATION", "EVIDENCE_INSUFFICIENT")
                    .contains(seed.status())).count();
            int clusterCount = (int) clustering.assignments().stream().filter(item -> !item.noise())
                    .map(ClusterAssignment::label).distinct().count();
            repository.complete(runId, clustering.vectorDimension(), clustering.algorithm(), clusterCount, candidateCount, noise, seeds);
        } catch (DiscoveryExecutionException exception) {
            repository.fail(runId, exception.code(), safeMessage(exception));
        } catch (Exception exception) {
            repository.fail(runId, "DISCOVERY_EXECUTION_FAILED", safeMessage(exception));
        }
    }

    private List<List<Double>> vectors(List<RawDocument> documents, Map<Long, SemanticProfile> profiles) {
        Map<Long, String> semanticHashes = documents.stream().collect(Collectors.toMap(RawDocument::id,
                document -> semanticHash(profiles.get(document.id()), policy.semanticProfileVersion()),
                (left, right) -> left, LinkedHashMap::new));
        Map<String, List<Double>> cached = new LinkedHashMap<>(embeddingCache.get(
                documents.stream().map(document -> semanticHashes.get(document.id())).toList(), embeddingClient.model()));
        cached.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().size() != embeddingClient.dimension());
        List<RawDocument> missing = documents.stream()
                .filter(document -> !cached.containsKey(semanticHashes.get(document.id()))).toList();
        if (!missing.isEmpty()) {
            List<List<Double>> generated = multiViewVectors(missing.stream().map(document -> profiles.get(document.id())).toList());
            Map<String, List<Double>> additions = new LinkedHashMap<>();
            for (int index = 0; index < missing.size(); index++) {
                additions.put(semanticHashes.get(missing.get(index).id()), generated.get(index));
            }
            embeddingCache.put(additions, embeddingClient.model());
            cached.putAll(additions);
        }
        List<List<Double>> result = documents.stream().map(document -> cached.get(semanticHashes.get(document.id()))).toList();
        if (result.stream().anyMatch(vector -> vector == null || vector.size() != embeddingClient.dimension())) {
            throw new DiscoveryExecutionException("EMBEDDING_DIMENSION_INVALID", "发现运行向量维度契约校验失败");
        }
        return result;
    }

    private List<List<Double>> multiViewVectors(List<SemanticProfile> profiles) {
        List<List<Double>> titleVectors = embeddingClient.embed(profiles.stream().map(SemanticProfile::title).toList());
        List<List<Double>> responsibilityVectors = embeddingClient.embed(profiles.stream().map(SemanticProfile::responsibilities).toList());
        List<List<Double>> skillVectors = embeddingClient.embed(profiles.stream().map(SemanticProfile::skills).toList());
        List<List<Double>> result = new ArrayList<>();
        for (int index = 0; index < profiles.size(); index++) {
            result.add(combineViews(List.of(titleVectors.get(index), responsibilityVectors.get(index), skillVectors.get(index))));
        }
        return List.copyOf(result);
    }

    private List<CandidateSeed> seeds(List<RawDocument> documents, List<JobRole> roles, List<List<Double>> roleVectors,
                                      Map<Long, List<Double>> vectorByDocument, List<ClusterAssignment> assignments,
                                      Map<Long, DocumentSemanticFacts> semanticFacts) {
        Map<Long, RawDocument> byId = documents.stream().collect(Collectors.toMap(RawDocument::id, Function.identity()));
        Map<Integer, List<ClusterAssignment>> grouped = assignments.stream()
                .collect(Collectors.groupingBy(ClusterAssignment::label, LinkedHashMap::new, Collectors.toList()));
        List<CandidateSeed> result = new ArrayList<>();
        for (Map.Entry<Integer, List<ClusterAssignment>> entry : grouped.entrySet()) {
            List<RawDocument> clusterDocuments = entry.getValue().stream().map(item -> byId.get(item.documentId())).filter(java.util.Objects::nonNull).toList();
            result.add(seed(entry.getKey(), clusterDocuments, roles, roleVectors, vectorByDocument, entry.getValue(), semanticFacts));
        }
        return List.copyOf(result);
    }

    private CandidateSeed seed(int label, List<RawDocument> documents, List<JobRole> roles, List<List<Double>> roleVectors,
                               Map<Long, List<Double>> vectorByDocument, List<ClusterAssignment> members,
                               Map<Long, DocumentSemanticFacts> semanticFacts) {
        int companies = distinct(documents, RawDocument::company);
        int sources = distinct(documents, RawDocument::sourceName);
        double median = median(members.stream().map(ClusterAssignment::probability).sorted().toList());
        RoleMatch nearest = nearestRole(documents, roles, roleVectors, vectorByDocument, semanticFacts);
        Map<String, Double> metrics = metrics(documents, nearest);
        double score = policy.score(metrics);
        boolean noise = label == -1;
        boolean candidateGate = documents.size() >= policy.candidateGate().minimumDocuments()
                && companies >= policy.candidateGate().minimumCompanies()
                && sources >= policy.candidateGate().minimumSources()
                && median >= policy.candidateGate().minimumMedianProbability()
                && score >= policy.candidateThreshold();
        boolean existingVariant = nearest.similarity() >= policy.existingRoleSimilarityThreshold();
        List<String> opposing = opposingEvidence(documents, nearest, candidateGate, existingVariant);
        if (noise || !candidateGate || existingVariant) {
            String status = noise ? "NOISE" : existingVariant ? "FILTERED_EXISTING_ROLE" : "CANDIDATE_GATE_FAILED";
            return new CandidateSeed(label, status, documents.size(), companies, sources, median, nearest.roleId(), nearest.name(),
                    nearest.similarity(), score * 100, metrics, opposing, "", uncertainty(score, median), List.of(), List.of(),
                    List.of(), List.of(), "NOT_GENERATED", "", members, basicEvidence(documents));
        }
        List<RawDocument> diverse = diverseDocuments(documents);
        EmergingRoleDefinitionGenerator.GenerationResult generated = definitionGenerator.generate(diverse);
        String status = generated.accepted()
                ? publishGate(documents, companies, sources, score, metrics)
                    ? "AUTO_PUBLISH_READY" : "EMERGING_OBSERVATION"
                : "WAITING_MODEL".equals(generated.mode()) ? "WAITING_GENERATION" : "EVIDENCE_INSUFFICIENT";
        String name = generated.accepted() ? generated.name() : "待生成岗位簇 " + label;
        List<FieldEvidence> evidence = new ArrayList<>(basicEvidence(diverse));
        evidence.addAll(generated.evidence());
        return new CandidateSeed(label, status, documents.size(), companies, sources, median, nearest.roleId(), nearest.name(),
                nearest.similarity(), score * 100, metrics, opposing, name, uncertainty(score, median), generated.responsibilities(),
                generated.requiredSkills(), generated.bonusSkills(), generated.scenarios(), generated.mode(), generated.inputHash(),
                members, List.copyOf(evidence));
    }

    private boolean publishGate(List<RawDocument> documents, int companies, int sources, double score,
                                Map<String, Double> metrics) {
        return meetsAutomaticPublicationGate(policy, documents.size(), companies, sources, score, metrics);
    }

    static boolean meetsAutomaticPublicationGate(EmergingDiscoveryPolicy policy, int documents, int companies,
                                                 int sources, double score, Map<String, Double> metrics) {
        EmergingDiscoveryPolicy.Gate gate = policy.publishGate();
        return documents >= gate.minimumDocuments()
                && companies >= gate.minimumCompanies()
                && sources >= gate.minimumSources()
                && score >= policy.publishThreshold()
                && metrics.getOrDefault("previousWindowCompanies", 0.0) >= gate.minimumCompaniesPerWindow()
                && metrics.getOrDefault("currentWindowCompanies", 0.0) >= gate.minimumCompaniesPerWindow();
    }

    private Map<String, Double> metrics(List<RawDocument> documents, RoleMatch nearest) {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("companySupport", clamp(distinct(documents, RawDocument::company) / (double) policy.publishGate().minimumCompanies()));
        result.put("sourceSupport", clamp(distinct(documents, RawDocument::sourceName) / (double) policy.publishGate().minimumSources()));
        result.put("windowGrowth", windowGrowth(documents));
        result.put("titleNovelty", 1 - nearest.titleSimilarity());
        result.put("responsibilityDifference", 1 - nearest.responsibilitySimilarity());
        result.put("skillCombinationDifference", 1 - nearest.skillSimilarity());
        WindowSupport windows = windowSupport(documents);
        result.put("previousWindowCompanies", (double) windows.previousCompanies());
        result.put("currentWindowCompanies", (double) windows.currentCompanies());
        return Map.copyOf(result);
    }

    private RoleMatch nearestRole(List<RawDocument> documents, List<JobRole> roles, List<List<Double>> roleVectors,
                                  Map<Long, List<Double>> vectors, Map<Long, DocumentSemanticFacts> semanticFacts) {
        if (roles.isEmpty() || roleVectors.isEmpty()) return RoleMatch.none();
        List<Double> centroid = centroid(documents.stream().map(document -> vectors.get(document.id())).toList());
        Set<String> clusterTitles = tokens(documents.stream().map(RawDocument::jobTitle).collect(Collectors.joining(" ")));
        Set<String> clusterResponsibilities = tokens(documents.stream()
                .map(document -> responsibilityText(document, semanticFacts.get(document.id())))
                .collect(Collectors.joining(" ")));
        Set<String> clusterSkills = skills(documents, semanticFacts);
        RoleMatch best = RoleMatch.none();
        for (int index = 0; index < roles.size(); index++) {
            JobRole role = roles.get(index);
            double similarity = cosine(centroid, roleVectors.get(index));
            RoleMatch candidate = new RoleMatch(role.id(), role.name(), similarity,
                    jaccard(clusterTitles, tokens(role.name())),
                    jaccard(clusterResponsibilities, tokens(String.join(" ", role.responsibilities()))),
                    jaccard(clusterSkills, role.requiredSkills().stream().map(item -> item.skill().toLowerCase(Locale.ROOT)).collect(Collectors.toSet())));
            if (candidate.similarity() > best.similarity()) best = candidate;
        }
        return best;
    }

    private double windowGrowth(List<RawDocument> documents) {
        WindowSupport windows = windowSupport(documents);
        int current = windows.currentCompanies();
        int previous = windows.previousCompanies();
        if (current == 0 && previous == 0) return 0;
        return clamp((current - previous) / (double) Math.max(1, current + previous) * 0.5 + 0.5);
    }

    private WindowSupport windowSupport(List<RawDocument> documents) {
        OffsetDateTime maximum = documents.stream().map(RawDocument::publishedAt).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        if (maximum == null) return new WindowSupport(0, 0);
        OffsetDateTime currentStart = maximum.minusDays(policy.timeWindowDays());
        OffsetDateTime previousStart = currentStart.minusDays(policy.timeWindowDays());
        int current = distinct(documents.stream().filter(document -> document.publishedAt() != null
                && !document.publishedAt().isBefore(currentStart)).toList(), RawDocument::company);
        int previous = distinct(documents.stream().filter(document -> document.publishedAt() != null
                && !document.publishedAt().isBefore(previousStart) && document.publishedAt().isBefore(currentStart)).toList(), RawDocument::company);
        return new WindowSupport(previous, current);
    }

    private SemanticProfile semanticProfile(RawDocument document, DocumentSemanticFacts facts) {
        String skills = facts == null || facts.skills().isEmpty()
                ? normalizationService.extract(document.content()).stream().map(ExtractedFact::normalizedValue)
                    .distinct().collect(Collectors.joining("、"))
                : String.join("、", facts.skills());
        return new SemanticProfile("岗位标题：" + document.jobTitle(),
                "岗位职责：" + responsibilityText(document, facts),
                "技能集合：" + skills);
    }

    private SemanticProfile semanticProfile(JobRole role) {
        String skills = role.requiredSkills().stream().map(item -> item.skill()).collect(Collectors.joining("、"));
        return new SemanticProfile("岗位标题：" + role.name(), "岗位职责：" + String.join("；", role.responsibilities()),
                "技能集合：" + skills);
    }

    private String responsibilityText(RawDocument document, DocumentSemanticFacts facts) {
        if (facts != null && !facts.responsibilities().isEmpty()) return String.join("；", facts.responsibilities());
        String value = document.content();
        int responsibilities = Math.max(0, Math.max(value.indexOf("岗位职责"), value.indexOf("职位描述")));
        int requirements = value.indexOf("任职要求", responsibilities);
        int end = requirements > responsibilities ? requirements : Math.min(value.length(), responsibilities + 1600);
        return value.substring(responsibilities, Math.max(responsibilities, end));
    }

    private Set<String> skills(List<RawDocument> documents, Map<Long, DocumentSemanticFacts> semanticFacts) {
        Set<String> result = new LinkedHashSet<>();
        for (RawDocument document : documents) {
            DocumentSemanticFacts facts = semanticFacts.get(document.id());
            if (facts != null && !facts.skills().isEmpty()) {
                facts.skills().stream().map(value -> value.toLowerCase(Locale.ROOT)).forEach(result::add);
            } else {
                normalizationService.extract(document.content()).stream()
                        .map(fact -> fact.normalizedValue().toLowerCase(Locale.ROOT)).forEach(result::add);
            }
        }
        return result;
    }

    private List<RawDocument> diverseDocuments(List<RawDocument> documents) {
        Set<String> sources = new LinkedHashSet<>();
        Set<String> companies = new LinkedHashSet<>();
        List<RawDocument> result = new ArrayList<>();
        documents.stream().sorted(Comparator.comparing(RawDocument::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(document -> {
                    if (result.size() >= 12) return;
                    if (sources.add(document.sourceName()) || companies.add(document.company()) || result.size() < 5) result.add(document);
                });
        return List.copyOf(result);
    }

    private List<FieldEvidence> basicEvidence(List<RawDocument> documents) {
        return documents.stream().limit(12).map(document -> {
            int end = Math.min(document.content().length(), 320);
            return new FieldEvidence("CLUSTER_SUPPORT", document.jobTitle(), document.id(), 0, end,
                    document.content().substring(0, end), "SUPPORT", true);
        }).toList();
    }

    private List<String> opposingEvidence(List<RawDocument> documents, RoleMatch nearest, boolean gate, boolean existingVariant) {
        List<String> result = new ArrayList<>();
        if (!gate) result.add("候选支持度、聚类稳定性或校准分数未达到策略门槛");
        if (existingVariant) result.add("与现有岗位“" + nearest.name() + "”语义相似度达到 " + round(nearest.similarity() * 100) + "%");
        long missingTime = documents.stream().filter(document -> document.publishedAt() == null).count();
        if (missingTime > 0) result.add(missingTime + " 条 JD 缺少发布时间，未进入连续窗口增长计算");
        result.add("岗位标题可能存在同义改名，发布前必须核对字段证据和最近似岗位差异");
        return List.copyOf(result);
    }

    private double median(List<Double> sorted) { if (sorted.isEmpty()) return 0; int middle = sorted.size() / 2; return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2; }
    private int distinct(List<RawDocument> documents, Function<RawDocument, String> mapper) { return (int) documents.stream().map(mapper).filter(value -> value != null && !value.isBlank()).distinct().count(); }
    private List<Double> centroid(List<List<Double>> vectors) { if (vectors.isEmpty()) return List.of(); double[] values = new double[vectors.get(0).size()]; vectors.forEach(vector -> { for (int index = 0; index < values.length; index++) values[index] += vector.get(index); }); List<Double> result = new ArrayList<>(); for (double value : values) result.add(value / vectors.size()); return List.copyOf(result); }
    private double cosine(List<Double> left, List<Double> right) { if (left.size() != right.size() || left.isEmpty()) return 0; double dot=0,a=0,b=0; for(int i=0;i<left.size();i++){dot+=left.get(i)*right.get(i);a+=left.get(i)*left.get(i);b+=right.get(i)*right.get(i);} return a==0||b==0?0:clamp(dot/(Math.sqrt(a)*Math.sqrt(b))); }
    private Set<String> tokens(String value) { String normalized=(value==null?"":value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); Set<String> result=new LinkedHashSet<>(); for(int i=0;i+2<=normalized.length();i++) result.add(normalized.substring(i,i+2)); return result; }
    private double jaccard(Set<String> left, Set<String> right) { if(left.isEmpty()||right.isEmpty()) return 0; Set<String> intersection=new LinkedHashSet<>(left);intersection.retainAll(right);Set<String> union=new LinkedHashSet<>(left);union.addAll(right);return intersection.size()/(double)union.size(); }
    private double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private String uncertainty(double score, double stability) { return score >= policy.publishThreshold() && stability >= .75 ? "低" : score >= policy.candidateThreshold() ? "中" : "高"; }
    private String safeMessage(Exception exception) { String message=exception.getMessage(); return message==null||message.isBlank()?exception.getClass().getSimpleName():message.substring(0,Math.min(900,message.length())); }

    static List<Double> combineViews(List<List<Double>> views) {
        if (views.isEmpty() || views.get(0).isEmpty()) throw new IllegalArgumentException("语义视图向量不能为空");
        int dimension = views.get(0).size();
        double[] combined = new double[dimension];
        for (List<Double> view : views) {
            if (view.size() != dimension) throw new IllegalArgumentException("语义视图向量维度不一致");
            double norm = Math.sqrt(view.stream().mapToDouble(value -> value * value).sum());
            if (norm == 0) throw new IllegalArgumentException("语义视图向量不能为零向量");
            for (int index = 0; index < dimension; index++) combined[index] += view.get(index) / norm;
        }
        double norm = Math.sqrt(java.util.Arrays.stream(combined).map(value -> value * value).sum());
        List<Double> result = new ArrayList<>(dimension);
        for (double value : combined) result.add(value / norm);
        return List.copyOf(result);
    }

    static String semanticHash(SemanticProfile profile, String profileVersion) {
        return hash(profileVersion + "\n" + profile.title() + "\n" + profile.responsibilities() + "\n" + profile.skills());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record RoleMatch(Long roleId, String name, double similarity, double titleSimilarity,
                             double responsibilitySimilarity, double skillSimilarity) {
        static RoleMatch none() { return new RoleMatch(null, "暂无可比岗位", 0, 0, 0, 0); }
    }

    private record WindowSupport(int previousCompanies, int currentCompanies) {
    }

    record SemanticProfile(String title, String responsibilities, String skills) {
    }
}
