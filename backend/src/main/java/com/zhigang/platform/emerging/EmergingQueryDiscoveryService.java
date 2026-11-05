package com.zhigang.platform.emerging;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.emerging.EmergingQueryGenerator.GenerationResult;
import com.zhigang.platform.emerging.EmergingQueryGenerator.RoleMatch;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDiscovery;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDraft;
import com.zhigang.platform.repository.PlatformDataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class EmergingQueryDiscoveryService {
    private static final List<String> OUT_OF_SCOPE_TERMS = List.of(
            // 中文关键词
            "厨师", "餐饮", "菜品", "医生", "护士", "会计", "律师", "土木", "建筑", "教师", "销售", "金融交易",
            // 英文关键词
            "chef", "cook", "restaurant", "cuisine", "doctor", "physician", "surgeon", "nurse",
            "accountant", "lawyer", "attorney", "civil", "construction", "architect", "teacher",
            "sales", "finance", "trading", "driver", "logistics", "warehouse"
    );
    private static final List<String> MOJIBAKE_MARKERS = List.of(
            "�", "锟斤拷", "烫烫烫", "ï¿½", "â€™", "â€œ", "â€", "Ã", "Â");
    private static final Pattern QUESTION_PLACEHOLDER = Pattern.compile("[?？]{3,}");
    private static final List<String> ROLE_TITLE_SUFFIXES = List.of(
            "高级开发工程师", "资深开发工程师", "开发工程师", "算法工程师", "测试工程师",
            "运维工程师", "数据工程师", "工程师", "架构师", "科学家", "专家", "顾问", "经理",
            "岗位", "职位", "方向"
    );

    private final EmergingQueryRepository repository;
    private final PlatformDataProvider dataProvider;
    private final EmbeddingClient embeddingClient;
    private final EmergingQueryVectorCache vectorCache;
    private final EmergingQueryGenerator generator;
    private final double cacheSimilarity;
    private final double existingRoleSimilarity;
    private final int minimumEvidence;
    private final int minimumSources;

    public EmergingQueryDiscoveryService(EmergingQueryRepository repository,
                                         PlatformDataProvider dataProvider,
                                         EmbeddingClient embeddingClient,
                                         EmergingQueryVectorCache vectorCache,
                                         EmergingQueryGenerator generator,
                                         @Value("${zhigang.analysis.emerging.query-cache-similarity:0.93}") double cacheSimilarity,
                                         @Value("${zhigang.analysis.emerging.existing-role-similarity:0.88}") double existingRoleSimilarity,
                                         @Value("${zhigang.analysis.emerging.query-min-evidence:3}") int minimumEvidence,
                                         @Value("${zhigang.analysis.emerging.query-min-sources:2}") int minimumSources) {
        this.repository = repository;
        this.dataProvider = dataProvider;
        this.embeddingClient = embeddingClient;
        this.vectorCache = vectorCache;
        this.generator = generator;
        this.cacheSimilarity = cacheSimilarity;
        this.existingRoleSimilarity = existingRoleSimilarity;
        this.minimumEvidence = minimumEvidence;
        this.minimumSources = minimumSources;
    }

    public QueryDiscovery discover(String submittedQuery) {
        String query = cleanQuery(submittedQuery);
        String normalized = normalize(query);
        var exact = repository.findByNormalizedQuery(normalized);
        if (exact.isPresent() && exact.get().publishedRoleId() != null && !publishedRoleStillInGraph(exact.get())) {
            return repository.save(unpublishedReplay(exact.get()));
        }
        if (exact.isPresent() && reusableCache(exact.get()) && existingCacheStillValid(exact.get())
                && publishedRoleStillInGraph(exact.get())) {
            return cached(exact.get(), 1, exact.get().query());
        }

        List<Double> queryVector = embed(query);
        if (!queryVector.isEmpty()) {
            var similar = vectorCache.search(queryVector, embeddingClient.model(), cacheSimilarity);
            if (similar.isPresent()) {
                var cached = repository.findById(similar.get().discoveryId());
                if (cached.isPresent() && cached.get().publishedRoleId() != null
                        && !publishedRoleStillInGraph(cached.get())) {
                    return repository.save(unpublishedReplay(cached.get()));
                }
                if (cached.isPresent() && reusableCache(cached.get()) && existingCacheStillValid(cached.get())
                        && publishedRoleStillInGraph(cached.get())) {
                    return cached(cached.get(), similar.get().similarity(), cached.get().query());
                }
            }
        }

        if (explicitlyOutOfScope(normalized)) {
            QueryDiscovery saved = repository.save(new QueryDraft(query, normalized, sha256(normalized), "OUT_OF_SCOPE",
                    "非目标领域", "", "", List.of(), List.of(), List.of(), List.of(), null,
                    "暂无可比岗位", 0, 1, false, false, false, embeddingClient.model(), sha256(query),
                    "QUERY_OUT_OF_SCOPE", "当前仅支持计算机、人工智能和新一代信息技术领域", List.of(), List.of()));
            return saved;
        }

        List<JobRole> roles = dataProvider.findJobRoles();
        RoleMatch nearest = nearestRole(query, queryVector, roles);
        JobRole nearestRole = roles.stream().filter(role -> nearest.id() != null && role.id() == nearest.id()).findFirst().orElse(null);
        // Emerging roles are discovery candidates, not valid existing-role matches.
        // This also prevents an archived/removed role lingering in a snapshot from
        // suppressing the "加入图谱" action on a subsequent search.
        if (nearestRole != null && !nearestRole.emerging() && nearest.similarity() >= existingRoleSimilarity) {
            return saveExistingRoleMatch(query, normalized, queryVector, nearest, nearestRole);
        }
        GenerationResult generated = generator.generate(query, nearest);
        boolean disputedExistingClassification = "EXISTING_ROLE".equals(generated.classification());
        String status = switch (generated.mode()) {
            case "WAITING_GENERATION" -> "WAITING_GENERATION";
            case "OUT_OF_SCOPE" -> "OUT_OF_SCOPE";
            case "EVIDENCE_INSUFFICIENT" -> "EVIDENCE_INSUFFICIENT";
            case "REASONING" -> "EMERGING_ROLE"; // 推理模式结果标记为新兴岗位，但带特殊标识
            default -> disputedExistingClassification ? "EVIDENCE_INSUFFICIENT" : "EMERGING_ROLE";
        };
        int sourceCount = (int) generated.evidence().stream().map(item -> item.domain().toLowerCase(Locale.ROOT)).distinct().count();
        // 只要大模型能推理出完整字段，就允许发布，不再限制证据数量
        boolean publishable = "EMERGING_ROLE".equals(status) && generated.fieldEvidenceComplete();
        // 不再因为证据不足而改变状态

        String name = generated.name();
        String definition = generated.definition();
        List<String> responsibilities = generated.responsibilities();
        List<String> requiredSkills = generated.requiredSkills();
        List<String> bonusSkills = generated.bonusSkills();
        List<String> scenarios = generated.scenarios();
        if (name.isBlank()) name = query;
        requirePublicTextQuality(List.of(query, normalized, safe(generated.domain()), safe(name), safe(definition)),
                responsibilities, requiredSkills, bonusSkills, scenarios);
        String errorCode = disputedExistingClassification ? "MODEL_ROLE_MATCH_CONFLICT"
                : generated.errorCode();
        String errorMessage = disputedExistingClassification
                ? "模型认为属于已有岗位，但本地岗位相似度未达到阈值，暂不创建或发布"
                : generated.errorMessage();

        QueryDraft draft = new QueryDraft(query, normalized, sha256(normalized), status, generated.domain(), name,
                definition, responsibilities, requiredSkills, bonusSkills, scenarios, nearest.id(), nearest.name(),
                nearest.similarity(), round(1 - nearest.similarity()), generated.fieldEvidenceComplete(),
                !generated.evidence().isEmpty(), publishable, generated.model(), generated.inputHash(),
                errorCode, errorMessage, generated.evidence(), generated.fieldEvidence());
        QueryDiscovery saved = repository.save(draft);
        if (!queryVector.isEmpty() && reusableCache(saved)) {
            vectorCache.put(saved.id(), normalized, queryVector, embeddingClient.model());
        }
        return saved;
    }

    private QueryDiscovery saveExistingRoleMatch(String query, String normalized, List<Double> queryVector,
                                                  RoleMatch nearest, JobRole role) {
        requirePublicTextQuality(List.of(query, normalized, safe(role.family()), safe(role.name()), safe(role.definition())),
                role.responsibilities(), role.requiredSkills().stream().map(item -> item.skill()).toList(),
                role.bonusSkills().stream().map(item -> item.skill()).toList(), role.scenarios());
        QueryDraft draft = new QueryDraft(query, normalized, sha256(normalized), "EXISTING_ROLE", role.family(),
                role.name(), role.definition(), role.responsibilities(),
                role.requiredSkills().stream().map(item -> item.skill()).toList(),
                role.bonusSkills().stream().map(item -> item.skill()).toList(), role.scenarios(), role.id(), role.name(),
                nearest.similarity(), round(1 - nearest.similarity()), false, false, false,
                queryVector.isEmpty() ? "LOCAL_TITLE_MATCH" : embeddingClient.model(),
                sha256("existing-role:" + normalized + ":" + role.code()), "EXISTING_ROLE_MATCH",
                "已匹配现有岗位，无需联网创建新岗位", List.of(), List.of());
        QueryDiscovery saved = repository.save(draft);
        if (!queryVector.isEmpty()) {
            vectorCache.put(saved.id(), normalized, queryVector, embeddingClient.model());
        }
        return saved;
    }

    public List<QueryDiscovery> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 30));
        return repository.findRecent(50).stream()
                .filter(this::publicTextQualityValid)
                .filter(value -> !"EMERGING_ROLE".equals(value.status()) || hasPublicRoleName(value.name()))
                .limit(safeLimit)
                .toList();
    }

    public QueryDiscovery get(long id) {
        QueryDiscovery discovery = repository.findById(id)
                .orElseThrow(() -> new BusinessException("QUERY_DISCOVERY_NOT_FOUND", "未找到查询发现结果"));
        if (!publicTextQualityValid(discovery)) {
            throw new BusinessException("QUERY_DISCOVERY_CONTENT_INVALID", "该查询结果存在空查询或乱码，已禁止公开输出");
        }
        return discovery;
    }

    public QueryDiscovery publish(long id) {
        QueryDiscovery current = get(id);
        if (current.publishedRoleId() != null) return current;

        if (!("EMERGING_ROLE".equals(current.status()) || "EVIDENCE_INSUFFICIENT".equals(current.status()))) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "只有新兴岗位发现结果可以加入图谱");
        }
        if (!current.fieldEvidenceComplete()) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "岗位字段证据不完整，无法加入图谱");
        }
        if (!current.publishable()) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "该发现结果未满足发布条件，无法加入图谱");
        }
        if (current.evidenceCount() < 1) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "至少需要 1 条有效证据才能加入图谱");
        }

        // 验证1：名称和定义不能为空
        if (current.name() == null || current.name().isBlank()
                || current.definition() == null || current.definition().isBlank()) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "岗位名称或定义为空，无法加入图谱");
        }
        if (!hasPublicRoleName(current.name())) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "岗位名称必须使用规范中文岗位名称");
        }

        // 验证2：必须属于IT/计算机领域
        if ("OUT_OF_SCOPE".equals(current.status())) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED",
                "当前仅支持计算机、人工智能和新一代信息技术领域的新岗位发现");
        }

        // 验证3：检查领域字段是否为IT相关
        if (current.domain() != null && !current.domain().isBlank()
                && !isItDomain(current.domain())) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED",
                "该岗位不属于计算机、人工智能或新一代信息技术领域，无法加入图谱");
        }

        return repository.publish(id, "用户手动加入图谱");
    }

    public QueryDiscovery removeFromGraph(long id) {
        QueryDiscovery current = get(id);
        if (current.publishedRoleId() == null) {
            throw new BusinessException("QUERY_DISCOVERY_NOT_PUBLISHED", "该岗位尚未加入图谱");
        }
        return repository.removeFromGraph(id, "用户手动移出图谱");
    }

    private boolean isItDomain(String domain) {
        String normalized = normalize(domain);
        // IT相关领域关键词
        List<String> itKeywords = List.of(
            "人工智能", "软件", "大数据", "物联网", "智能系统", "新一代信息技术",
            "计算机", "信息", "互联网", "云计算", "区块链", "网络", "数据",
            "ai", "it", "cs", "computerscience", "informationtechnology"
        );
        return itKeywords.stream()
            .map(EmergingQueryDiscoveryService::normalize)
            .anyMatch(normalized::contains);
    }

    private boolean hasPublicRoleName(String value) {
        if (value == null || value.isBlank()) return false;
        boolean containsChinese = value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN);
        if (containsChinese) return true;
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        return Set.of("aiagent", "rag", "llmops").contains(normalized);
    }

    private RoleMatch nearestRole(String query, List<Double> queryVector, List<JobRole> roles) {
        if (roles.isEmpty()) return RoleMatch.none();
        String normalizedQuery = normalize(query);
        JobRole exact = roles.stream().filter(role -> normalize(role.name()).equals(normalizedQuery)).findFirst().orElse(null);
        if (exact != null) return new RoleMatch(exact.id(), exact.name(), 1);
        if (queryVector.isEmpty()) {
            RoleMatch localTitleMatch = localTitleMatch(normalizedQuery, roles);
            if (localTitleMatch.id() != null) return localTitleMatch;
        }
        if (!queryVector.isEmpty()) {
            try {
                List<String> profiles = roles.stream().map(this::roleProfile).toList();
                List<List<Double>> roleVectors = embeddingClient.embed(profiles);
                RoleMatch best = RoleMatch.none();
                for (int index = 0; index < roles.size(); index++) {
                    double similarity = cosine(queryVector, roleVectors.get(index));
                    if (similarity > best.similarity()) {
                        best = new RoleMatch(roles.get(index).id(), roles.get(index).name(), similarity);
                    }
                }
                return best;
            } catch (RuntimeException ignored) {
                // 本地向量服务暂不可用时，退化为标题字符相似度，不阻断联网发现。
            }
        }
        return roles.stream().map(role -> new RoleMatch(role.id(), role.name(), jaccard(query, role.name())))
                .max(Comparator.comparingDouble(RoleMatch::similarity)).orElse(RoleMatch.none());
    }

    private RoleMatch localTitleMatch(String normalizedQuery, List<JobRole> roles) {
        String queryCore = roleTitleCore(normalizedQuery);
        if (queryCore.length() < 4) return RoleMatch.none();
        List<RoleTitleCandidate> candidates = roles.stream()
                .map(role -> new RoleTitleCandidate(role, roleTitleCore(normalize(role.name()))))
                .filter(candidate -> !candidate.core().isBlank())
                .filter(candidate -> candidate.core().equals(queryCore)
                        || candidate.core().startsWith(queryCore)
                        && candidate.core().length() - queryCore.length() <= 4)
                .sorted(Comparator.comparingInt(candidate -> candidate.core().length() - queryCore.length()))
                .toList();
        if (candidates.isEmpty()) return RoleMatch.none();
        int minimumExtension = candidates.get(0).core().length() - queryCore.length();
        List<RoleTitleCandidate> best = candidates.stream()
                .filter(candidate -> candidate.core().length() - queryCore.length() == minimumExtension)
                .toList();
        if (best.stream().map(candidate -> candidate.role().id()).distinct().count() != 1) {
            return RoleMatch.none();
        }
        RoleTitleCandidate selected = best.get(0);
        return new RoleMatch(selected.role().id(), selected.role().name(), minimumExtension == 0 ? 1 : .95);
    }

    private String roleTitleCore(String value) {
        String result = normalize(value);
        boolean changed;
        do {
            changed = false;
            for (String suffix : ROLE_TITLE_SUFFIXES) {
                String normalizedSuffix = normalize(suffix);
                if (result.endsWith(normalizedSuffix) && result.length() > normalizedSuffix.length()) {
                    result = result.substring(0, result.length() - normalizedSuffix.length());
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return result;
    }

    private record RoleTitleCandidate(JobRole role, String core) {
    }

    private List<Double> embed(String query) {
        try {
            return embeddingClient.embed(List.of("岗位查询：" + query)).get(0);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private String roleProfile(JobRole role) {
        return "岗位名称：" + role.name() + "\n职责：" + String.join("；", role.responsibilities())
                + "\n技能：" + role.requiredSkills().stream().map(item -> item.skill()).collect(java.util.stream.Collectors.joining("、"));
    }

    private QueryDiscovery cached(QueryDiscovery value, double similarity, String matchedQuery) {
        return new QueryDiscovery(value.id(), value.query(), value.normalizedQuery(), value.status(), value.domain(),
                value.name(), value.definition(), value.responsibilities(), value.requiredSkills(), value.bonusSkills(),
                value.scenarios(), value.nearestRoleId(), value.nearestRoleName(), value.nearestRoleSimilarity(),
                value.noveltyScore(), value.evidenceCount(), value.sourceCount(), value.fieldEvidenceComplete(),
                value.webSearchUsed(), value.publishable(), value.modelName(), value.errorCode(), value.errorMessage(),
                value.publishedRoleId(), value.publishedReleaseId(), value.evidence(), value.fieldEvidence(), true,
                round(similarity), matchedQuery, value.publishedAt(), value.createdAt(), value.updatedAt());
    }

    private boolean reusableCache(QueryDiscovery value) {
        return publicTextQualityValid(value) && ("EXISTING_ROLE".equals(value.status()) && value.nearestRoleId() != null
                && value.nearestRoleSimilarity() >= existingRoleSimilarity
                || "OUT_OF_SCOPE".equals(value.status())
                || "EMERGING_ROLE".equals(value.status()) && value.fieldEvidenceComplete()
                && hasPublicRoleName(value.name()));
    }

    private boolean publishedRoleStillInGraph(QueryDiscovery value) {
        if (value.publishedRoleId() == null) return true;
        return dataProvider.findJobRoles().stream().anyMatch(role -> role.id() == value.publishedRoleId());
    }

    private boolean existingCacheStillValid(QueryDiscovery value) {
        if (!"EXISTING_ROLE".equals(value.status())) return true;
        if (value.nearestRoleId() == null) return false;
        return dataProvider.findJobRoles().stream()
                .anyMatch(role -> role.id() == value.nearestRoleId() && !role.emerging());
    }

    private QueryDraft unpublishedReplay(QueryDiscovery value) {
        return new QueryDraft(value.query(), value.normalizedQuery(), sha256(value.normalizedQuery()),
                "EMERGING_ROLE", value.domain(), value.name(), value.definition(), value.responsibilities(),
                value.requiredSkills(), value.bonusSkills(), value.scenarios(), value.nearestRoleId(),
                value.nearestRoleName(), value.nearestRoleSimilarity(), value.noveltyScore(),
                true, value.webSearchUsed(), true, value.modelName(), value.errorCode(), value.errorCode(), value.errorMessage(),
                value.evidence().stream().map(item -> new EmergingQueryModels.QueryEvidenceDraft(
                        item.key(), item.title(), item.source(), item.domain(), item.url(), item.publishedAt(), item.excerpt())).toList(),
                value.fieldEvidence().stream().map(item -> new EmergingQueryModels.QueryFieldEvidenceDraft(
                        item.fieldName(), item.fieldValue(), item.evidenceKey())).toList());
    }

    private String cleanQuery(String value) {
        if (value == null) throw new BusinessException("QUERY_REQUIRED", "请输入要发现的岗位或方向");
        String cleaned = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.length() < 2 || cleaned.length() > 80) {
            throw new BusinessException("QUERY_LENGTH_INVALID", "岗位查询长度需为 2 至 80 个字符");
        }
        if (containsMojibake(cleaned)) {
            throw new BusinessException("QUERY_ENCODING_INVALID", "岗位查询包含明显乱码，请重新输入");
        }
        if (normalize(cleaned).isBlank()) {
            throw new BusinessException("QUERY_NORMALIZED_EMPTY", "岗位查询不能只包含空白或标点符号");
        }
        return cleaned;
    }

    private boolean publicTextQualityValid(QueryDiscovery value) {
        if (value == null || value.query() == null || value.query().isBlank()
                || value.normalizedQuery() == null || value.normalizedQuery().isBlank()
                || normalize(value.query()).isBlank()) {
            return false;
        }
        List<String> scalarFields = List.of(value.query(), value.normalizedQuery(), safe(value.domain()),
                safe(value.name()), safe(value.definition()));
        if (scalarFields.stream().anyMatch(this::containsMojibake)) return false;
        if (java.util.stream.Stream.of(value.responsibilities(), value.requiredSkills(), value.bonusSkills(), value.scenarios())
                .filter(java.util.Objects::nonNull).flatMap(List::stream).anyMatch(this::containsMojibake)) return false;
        boolean evidenceValid = value.evidence() == null || value.evidence().stream().noneMatch(evidence ->
                containsMojibake(safe(evidence.title())) || containsMojibake(safe(evidence.source()))
                        || containsMojibake(safe(evidence.excerpt())));
        boolean fieldEvidenceValid = value.fieldEvidence() == null || value.fieldEvidence().stream()
                .noneMatch(evidence -> containsMojibake(safe(evidence.fieldName()))
                        || containsMojibake(safe(evidence.fieldValue())));
        return evidenceValid && fieldEvidenceValid;
    }

    @SafeVarargs
    private final void requirePublicTextQuality(List<String> scalarFields, List<String>... listFields) {
        boolean invalid = scalarFields.stream().anyMatch(this::containsMojibake)
                || java.util.Arrays.stream(listFields).filter(java.util.Objects::nonNull)
                .flatMap(List::stream).anyMatch(this::containsMojibake);
        if (invalid) {
            throw new BusinessException("QUERY_DISCOVERY_CONTENT_INVALID", "查询发现结果包含明显乱码，已阻止写入公开缓存");
        }
    }

    private boolean containsMojibake(String value) {
        if (value == null || value.isBlank()) return false;
        return QUESTION_PLACEHOLDER.matcher(value).find()
                || MOJIBAKE_MARKERS.stream().anyMatch(value::contains);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private boolean explicitlyOutOfScope(String normalizedQuery) {
        return OUT_OF_SCOPE_TERMS.stream().map(EmergingQueryDiscoveryService::normalize).anyMatch(normalizedQuery::contains);
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : Math.max(0, Math.min(1, dot / Math.sqrt(leftNorm * rightNorm)));
    }

    private double jaccard(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0;
        Set<String> intersection = new LinkedHashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        return intersection.size() / (double) union.size();
    }

    private Set<String> tokens(String value) {
        String normalized = normalize(value);
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 2 <= normalized.length(); index++) result.add(normalized.substring(index, index + 2));
        return result;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private double round(double value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 10000.0) / 10000.0;
    }
}
