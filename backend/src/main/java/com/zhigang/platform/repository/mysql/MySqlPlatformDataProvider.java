package com.zhigang.platform.repository.mysql;

import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.LearningResource;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.domain.PlatformModels.RoleSkillEvidence;
import com.zhigang.platform.domain.PlatformModels.RoleSkillEvidenceItem;
import com.zhigang.platform.domain.PlatformModels.SkillDefinition;
import com.zhigang.platform.domain.PlatformModels.SkillRequirement;
import com.zhigang.platform.domain.RoleClassificationPolicy;
import com.zhigang.platform.repository.PlatformDataProvider;
import com.zhigang.platform.repository.PlatformDataProvider.PublicDataStats;
import com.zhigang.platform.repository.PlatformDataProvider.DocumentSemanticFacts;
import com.zhigang.platform.repository.PlatformDataProvider.DiscoveryEvidence;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordQuery;
import com.zhigang.platform.source.StructuredJdModels.RealRoleSnapshot;
import com.zhigang.platform.source.StructuredJdModels.RealSkillMetric;
import com.zhigang.platform.source.StructuredJdModels.RealSnapshotView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Profile("mysql")
@Repository
public class MySqlPlatformDataProvider implements PlatformDataProvider {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private final MySqlPlatformMapper mapper;
    private final MySqlStructuredJdRepository structuredJdRepository;

    public MySqlPlatformDataProvider(MySqlPlatformMapper mapper,
                                     MySqlStructuredJdRepository structuredJdRepository) {
        this.mapper = mapper;
        this.structuredJdRepository = structuredJdRepository;
    }

    @Override
    public List<JobRole> findJobRoles() {
        RealSnapshotView snapshot = structuredJdRepository.findCurrentRealSnapshot();
        List<JobRole> roles = new ArrayList<>();
        if (!snapshot.roles().isEmpty()) {
            Map<String, Long> roleIds = new LinkedHashMap<>(publishedRoleIds());
            for (RealRoleSnapshot role : snapshot.roles()) {
                String roleCode = normalizeRoleCode(role.roleCode());
                roleIds.computeIfAbsent(roleCode, ignored -> derivedRoleId(roleCode, roleIds.values()));
            }
            Map<String, String> categories = findSkills().stream().collect(java.util.stream.Collectors.toMap(
                    SkillDefinition::name, SkillDefinition::category, (left, right) -> left, LinkedHashMap::new));
            roles.addAll(snapshot.roles().stream()
                    .map(role -> projectRole(snapshot, role, categories, roleIds)).toList());
        }
        roles.addAll(mapper.selectPublishedEmergingRoles().stream().map(this::projectEmergingRole).toList());
        return roles.stream().collect(java.util.stream.Collectors.toMap(JobRole::id, role -> role,
                (left, right) -> right, LinkedHashMap::new)).values().stream()
                .sorted(java.util.Comparator.comparingLong(JobRole::id)).toList();
    }

    private JobRole projectEmergingRole(Map<String, Object> row) {
        List<SkillRequirement> required = new ArrayList<>();
        List<SkillRequirement> bonus = new ArrayList<>();
        for (String encoded : split(row.get("skills"))) {
            String[] values = encoded.split("~~", -1);
            if (values.length < 4) continue;
            SkillRequirement skill = new SkillRequirement(repairMojibake(values[0]), "新兴工程能力", values[1],
                    Double.parseDouble(values[2]), Integer.parseInt(values[3]), "NEW");
            if ("REQUIRED".equals(values[1])) required.add(skill); else bonus.add(skill);
        }
        String roleCode = string(row, "role_code");
        return new JobRole(longValue(row, "id"), roleCode, repairMojibake(string(row, "role_name")),
                repairMojibake(string(row, "family_name")), "通用",
                RoleClassificationPolicy.isEmerging(roleCode, true),
                repairMojibake(string(row, "definition")), "",
                split(row.get("responsibilities")).stream().map(MySqlPlatformDataProvider::repairMojibake).toList(),
                List.copyOf(required), List.copyOf(bonus),
                split(row.get("scenarios")).stream().map(MySqlPlatformDataProvider::repairMojibake).toList(),
                string(row, "release_version"), dateTime(row.get("updated_at")));
    }

    private JobRole projectRole(RealSnapshotView snapshot, RealRoleSnapshot role,
                                Map<String, String> categories, Map<String, Long> roleIds) {
        long id = roleIds.get(normalizeRoleCode(role.roleCode()));
        List<SkillRequirement> requiredCandidates = normalizeRoleSkills(role.requiredSkills(), "REQUIRED", categories);
        List<SkillRequirement> required = requiredCandidates.stream()
                .filter(skill -> skill.demandRate() >= 0.50)
                .sorted(Comparator.comparingDouble(SkillRequirement::demandRate).reversed()
                        .thenComparing(Comparator.comparingInt(SkillRequirement::evidenceCount).reversed())
                        .thenComparing(SkillRequirement::skill))
                .limit(6)
                .toList();
        if (required.isEmpty()) {
            required = requiredCandidates.stream()
                    .sorted(Comparator.comparingDouble(SkillRequirement::demandRate).reversed()
                            .thenComparing(Comparator.comparingInt(SkillRequirement::evidenceCount).reversed())
                            .thenComparing(SkillRequirement::skill))
                    .limit(3).toList();
        }
        Set<String> requiredNames = required.stream().map(SkillRequirement::skill)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<SkillRequirement> bonus = new ArrayList<>();
        requiredCandidates.stream().filter(skill -> !requiredNames.contains(skill.skill()))
                .map(skill -> new SkillRequirement(skill.skill(), skill.category(), "BONUS", skill.demandRate(),
                        skill.evidenceCount(), skill.trend()))
                .forEach(bonus::add);
        bonus.addAll(normalizeRoleSkills(role.bonusSkills(), "BONUS", categories));
        bonus.addAll(normalizeRoleSkills(role.mentionedSkills(), "MENTIONED", categories));
        List<SkillRequirement> distinctBonus = bonus.stream()
                .filter(skill -> !requiredNames.contains(skill.skill()))
                .collect(java.util.stream.Collectors.toMap(SkillRequirement::skill, skill -> skill,
                        (left, right) -> left.demandRate() >= right.demandRate() ? left : right, LinkedHashMap::new))
                .values().stream().toList();
        List<String> publicSkills = java.util.stream.Stream.concat(required.stream(), distinctBonus.stream())
                .map(SkillRequirement::skill)
                .filter(skill -> skill != null && !skill.isBlank())
                .distinct()
                .limit(4)
                .toList();
        String definition = buildPublicRoleDefinition(role.roleName(), role.responsibilities().stream()
                .map(item -> item.responsibility()).toList(), publicSkills);
        return new JobRole(id, role.roleCode(), role.roleName(), "软件与人工智能", "初中级",
                false, definition, "", role.responsibilities().stream()
                .map(item -> item.responsibility()).toList(), required, distinctBonus, List.of(),
                realReleaseVersion(snapshot.snapshotCode()), null);
    }

    private List<SkillRequirement> normalizeRoleSkills(List<RealSkillMetric> metrics, String type,
                                                       Map<String, String> categories) {
        Map<String, SkillRequirement> normalized = new LinkedHashMap<>();
        for (RealSkillMetric metric : metrics) {
            List<String> skills = isSentenceShapedSkill(metric.skill())
                    ? categories.keySet().stream().filter(skill -> containsCanonicalSkill(metric.skill(), skill))
                    .sorted(Comparator.comparingInt(String::length).reversed()).toList()
                    : List.of(metric.skill());
            for (String skill : skills) {
                SkillRequirement requirement = new SkillRequirement(skill,
                        categories.getOrDefault(skill, "工程能力"), type, metric.rate(), metric.documentCount(), "NO_TREND");
                normalized.merge(skill, requirement,
                        (left, right) -> left.demandRate() >= right.demandRate() ? left : right);
            }
        }
        return List.copyOf(normalized.values());
    }

    private boolean isSentenceShapedSkill(String value) {
        if (value == null || value.isBlank()) return true;
        return value.length() > 40 || value.matches(".*(掌握|熟悉|具备|能够|负责|经验|能力|包括|涵盖).*[。，；、].*");
    }

    private boolean containsCanonicalSkill(String sentence, String skill) {
        if ("C".equals(skill)) {
            return java.util.regex.Pattern.compile("(?<![A-Za-z0-9+#])C(?![A-Za-z0-9+#])").matcher(sentence).find();
        }
        return sentence.contains(skill);
    }

    private long derivedRoleId(String roleCode, java.util.Collection<Long> usedIds) {
        long candidate = 1_000_000_000L + Integer.toUnsignedLong(roleCode.hashCode());
        while (usedIds.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private String realReleaseVersion(String snapshotCode) {
        String value = snapshotCode == null || snapshotCode.isBlank() ? "current" : snapshotCode.trim();
        return value.startsWith("real-jd-") ? value : "real-jd-" + value;
    }

    static String buildPublicRoleDefinition(String roleName, List<String> responsibilities, List<String> skills) {
        String safeRoleName = roleName == null || roleName.isBlank() ? "该岗位" : roleName.trim();
        String responsibility = responsibilities == null ? "承担相关产品与工程任务"
                : responsibilities.stream().filter(item -> item != null && !item.isBlank())
                .map(String::trim).findFirst().orElse("承担相关产品与工程任务");
        List<String> coreSkills = skills == null ? List.of() : skills.stream()
                .filter(item -> item != null && !item.isBlank()).map(String::trim).distinct().limit(4).toList();
        if (coreSkills.isEmpty()) {
            return safeRoleName + "主要负责" + responsibility + "。";
        }
        return safeRoleName + "主要负责" + responsibility + "，工作中重点使用"
                + String.join("、", coreSkills) + "等能力。";
    }

    private SkillRequirement skillRequirement(RealSkillMetric metric, String type,
                                              Map<String, String> categories) {
        return new SkillRequirement(metric.skill(), categories.getOrDefault(metric.skill(), "工程能力"),
                type, metric.rate(), metric.documentCount(), "NO_TREND");
    }

    private Map<String, Long> publishedRoleIds() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectPublishedRoleIdentities()) {
            String code = normalizeRoleCode(string(row, "role_code"));
            if (!code.isBlank()) result.put(code, longValue(row, "id"));
        }
        return Map.copyOf(result);
    }

    private String roleCode(long roleId) {
        return mapper.selectPublishedRoleIdentities().stream()
                .filter(row -> longValue(row, "id") == roleId)
                .map(row -> normalizeRoleCode(string(row, "role_code")))
                .findFirst().orElse("");
    }

    private String normalizeRoleCode(String roleCode) {
        return switch (roleCode) {
            case "JAVA" -> "JAVA_ENGINEER";
            case "AI_AGENT" -> "AI_AGENT_ENGINEER";
            default -> roleCode;
        };
    }

    @Override
    public Optional<JobRole> findJobRole(long id) {
        return findJobRoles().stream().filter(role -> role.id() == id).findFirst();
    }

    @Override
    public List<SkillDefinition> findSkills() {
        return mapper.selectSkills().stream().map(row -> new SkillDefinition(
                longValue(row, "id"), repairMojibake(string(row, "skill_name")),
                repairMojibake(string(row, "category_name")),
                split(row.get("aliases")).stream().map(MySqlPlatformDataProvider::repairMojibake).toList(),
                split(row.get("prerequisites")).stream().map(MySqlPlatformDataProvider::repairMojibake).toList()
        )).toList();
    }

    @Override
    public List<RawDocument> findDocuments() {
        return mapper.selectDocuments().stream().map(row -> new RawDocument(
                longValue(row, "id"), string(row, "source_type"), string(row, "source_name"),
                string(row, "license_status"), string(row, "company_name"), string(row, "city"),
                string(row, "industry_name"), string(row, "level_name"), string(row, "document_title"),
                string(row, "salary_range"), string(row, "raw_text"), dateTime(row.get("published_at")),
                string(row, "content_hash"), booleanValue(row, "is_demo")
        )).toList();
    }

    @Override
    public List<RawDocument> findPublishedDiscoveryDocuments() {
        return mapper.selectDiscoveryDocuments().stream().map(row -> new RawDocument(
                longValue(row, "id"), string(row, "source_type"), string(row, "source_name"),
                string(row, "license_status"), string(row, "company_name"), string(row, "city"),
                string(row, "industry_name"), string(row, "level_name"), string(row, "document_title"),
                string(row, "salary_range"), string(row, "raw_text"), dateTime(row.get("published_at")),
                string(row, "content_hash"), booleanValue(row, "is_demo")
        )).toList();
    }

    @Override
    public PublicDataStats publicDataStats() {
        Map<String, Object> row = mapper.selectPublicDataStats();
        return new PublicDataStats(
                ((Number) row.get("sample_count")).intValue(),
                ((Number) row.get("role_count")).intValue(),
                dateTime(row.get("updated_at")),
                string(row, "graph_fingerprint"));
    }

    @Override
    public List<DiscoveryEvidence> findPublishedDiscoveryEvidence() {
        return mapper.selectDiscoveryEvidence().stream().map(row -> new DiscoveryEvidence(
                longValue(row, "id"), string(row, "document_title"), string(row, "source_name"),
                string(row, "company_name"), string(row, "source_url"),
                row.get("published_at") == null ? "" : row.get("published_at").toString(),
                string(row, "raw_text")
        )).toList();
    }

    @Override
    public Map<Long, DocumentSemanticFacts> findDocumentSemanticFacts() {
        Map<Long, List<String>> responsibilities = new LinkedHashMap<>();
        Map<Long, List<String>> skills = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectReviewedSemanticFacts()) {
            long documentId = longValue(row, "document_id");
            String value = string(row, "raw_value");
            if (value.isBlank()) continue;
            Map<Long, List<String>> target = "RESPONSIBILITY".equals(string(row, "fact_type"))
                    ? responsibilities : skills;
            target.computeIfAbsent(documentId, ignored -> new ArrayList<>()).add(value);
        }
        Map<Long, DocumentSemanticFacts> result = new LinkedHashMap<>();
        Set<Long> documentIds = new java.util.LinkedHashSet<>(responsibilities.keySet());
        documentIds.addAll(skills.keySet());
        for (Long documentId : documentIds) {
            result.put(documentId, new DocumentSemanticFacts(
                    distinct(responsibilities.get(documentId)), distinct(skills.get(documentId))));
        }
        return Map.copyOf(result);
    }

    @Override
    public List<JobRole> findPublishedBaselineJobRoles() {
        return mapper.selectPublishedBaselineRoles().stream().map(this::projectBaselineRole).toList();
    }

    private JobRole projectBaselineRole(Map<String, Object> row) {
        List<SkillRequirement> required = new ArrayList<>();
        List<SkillRequirement> bonus = new ArrayList<>();
        for (String encoded : split(row.get("skills"))) {
            String[] values = encoded.split("~~", -1);
            if (values.length < 4) continue;
            SkillRequirement skill = new SkillRequirement(repairMojibake(values[0]), "已发布图谱能力", values[1],
                    Double.parseDouble(values[2]), Integer.parseInt(values[3]), "BASELINE");
            if ("REQUIRED".equals(values[1])) required.add(skill); else bonus.add(skill);
        }
        String roleCode = string(row, "role_code");
        return new JobRole(longValue(row, "id"), roleCode, repairMojibake(string(row, "role_name")),
                repairMojibake(string(row, "family_name")), "通用",
                RoleClassificationPolicy.isEmerging(roleCode, booleanValue(row, "is_emerging")),
                repairMojibake(string(row, "definition")), string(row, "salary_range"),
                split(row.get("responsibilities")).stream().map(MySqlPlatformDataProvider::repairMojibake).toList(),
                List.copyOf(required), List.copyOf(bonus),
                split(row.get("scenarios")).stream().map(MySqlPlatformDataProvider::repairMojibake).toList(),
                string(row, "release_version"), dateTime(row.get("updated_at")));
    }

    @Override
    public List<LearningResource> findLearningResources() {
        return mapper.selectLearningResources().stream().map(row -> new LearningResource(
                longValue(row, "id"), string(row, "resource_name"), string(row, "resource_type"),
                string(row, "provider_name"), split(row.get("skills")), "APPROVED".equals(string(row, "review_status"))
        )).toList();
    }

    @Override
    public String operatingMode() {
        return "MYSQL_FACT_SOURCE";
    }

    @Override
    public String dataMode() {
        RealSnapshotView snapshot = structuredJdRepository.findCurrentRealSnapshot();
        if (snapshot.documentCount() > 0) {
            return "REAL";
        }
        long dynamicRecords = structuredJdRepository.findDynamicRecords(
                new DynamicRecordQuery(null, null, null, null, null, false, 1, 1)).total();
        return dynamicRecords > 0 ? "REAL_PENDING" : "NO_DATA";
    }

    @Override
    public Optional<RoleSkillEvidence> findRoleSkillEvidence(long roleId, String skill) {
        String code = roleCode(roleId);
        if (code.isBlank()) {
            return Optional.empty();
        }
        RealSnapshotView snapshot = structuredJdRepository.findCurrentRealSnapshot();
        return snapshot.roles().stream().filter(role -> code.equals(normalizeRoleCode(role.roleCode()))).findFirst()
                .flatMap(role -> skillEvidence(snapshot, roleId, role, skill));
    }

    @Override
    public Set<Long> findRoleDocumentIds(long roleId) {
        return mapper.selectPublishedRoleDocumentIds(roleId);
    }

    private Optional<RoleSkillEvidence> skillEvidence(RealSnapshotView snapshot, long roleId,
                                                      RealRoleSnapshot role, String skill) {
        for (SkillGroup group : List.of(
                new SkillGroup("REQUIRED", role.requiredSkills()),
                new SkillGroup("BONUS", role.bonusSkills()),
                new SkillGroup("MENTIONED", role.mentionedSkills()))) {
            Optional<RealSkillMetric> metric = group.skills().stream()
                    .filter(item -> item.skill().equalsIgnoreCase(skill)).findFirst();
            if (metric.isPresent()) {
                RealSkillMetric value = metric.get();
                return Optional.of(new RoleSkillEvidence(roleId, role.roleName(), value.skill(), group.type(),
                        value.rate(), value.documentCount(), value.companyCount(), snapshot.snapshotCode(),
                        "当前试验快照 · " + role.sampleSize() + " 条 JD · "
                                + role.independentCompanies() + " 家企业",
                        value.evidence().stream().map(item -> new RoleSkillEvidenceItem(item.documentId(),
                                item.title(), item.company(), item.quote(), item.startOffset(), item.endOffset(),
                                item.sourceUrl())).toList()));
            }
        }
        return Optional.empty();
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static boolean booleanValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean bool ? bool : value instanceof Number number ? number.intValue() != 0 : Boolean.parseBoolean(String.valueOf(value));
    }

    private static OffsetDateTime dateTime(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().atOffset(OFFSET);
        if (value instanceof java.time.LocalDateTime localDateTime) return localDateTime.atOffset(OFFSET);
        return null;
    }

    private static List<String> split(Object value) {
        if (value == null || value.toString().isBlank()) return List.of();
        return List.of(value.toString().split("\\|\\|"));
    }

    private static List<String> distinct(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    static String repairMojibake(String value) {
        if (value == null || value.isBlank()) return value == null ? "" : value;
        String result = value;
        for (int attempt = 0; attempt < 3 && looksMojibake(result); attempt++) {
            String repaired = decodeMojibakeLayer(result);
            if (repaired == null || repaired.contains("�") || repaired.equals(result)) break;
            result = repaired;
        }
        return result;
    }

    private static boolean looksMojibake(String value) {
        return value.matches(".*[ÃÂåæäçèé].*");
    }

    private static String decodeMojibakeLayer(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            int codePoint = value.charAt(index);
            if (codePoint <= 0xff) {
                bytes.write(codePoint);
                continue;
            }
            int windowsByte = windows1252Byte(codePoint);
            if (windowsByte < 0) return null;
            bytes.write(windowsByte);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int windows1252Byte(int codePoint) {
        return switch (codePoint) {
            case 0x20ac -> 0x80; case 0x201a -> 0x82; case 0x0192 -> 0x83; case 0x201e -> 0x84;
            case 0x2026 -> 0x85; case 0x2020 -> 0x86; case 0x2021 -> 0x87; case 0x02c6 -> 0x88;
            case 0x2030 -> 0x89; case 0x0160 -> 0x8a; case 0x2039 -> 0x8b; case 0x0152 -> 0x8c;
            case 0x017d -> 0x8e; case 0x2018 -> 0x91; case 0x2019 -> 0x92; case 0x201c -> 0x93;
            case 0x201d -> 0x94; case 0x2022 -> 0x95; case 0x2013 -> 0x96; case 0x2014 -> 0x97;
            case 0x02dc -> 0x98; case 0x2122 -> 0x99; case 0x0161 -> 0x9a; case 0x203a -> 0x9b;
            case 0x0153 -> 0x9c; case 0x017e -> 0x9e; case 0x0178 -> 0x9f;
            default -> -1;
        };
    }

    private record SkillGroup(String type, List<RealSkillMetric> skills) {
    }
}
