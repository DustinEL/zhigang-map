package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDiscovery;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDraft;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryEvidence;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryFieldEvidence;
import com.zhigang.platform.emerging.EmergingQueryRepository;
import com.zhigang.platform.repository.mysql.MySqlEmergingRolePublisher.PublicationInput;
import com.zhigang.platform.repository.mysql.MySqlEmergingRolePublisher.PublicationResult;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Profile("mysql")
@Repository
public class MySqlEmergingQueryRepository implements EmergingQueryRepository {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MySqlEmergingRolePublisher rolePublisher;

    public MySqlEmergingQueryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                        MySqlEmergingRolePublisher rolePublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rolePublisher = rolePublisher;
    }

    @Override
    public Optional<QueryDiscovery> findByNormalizedQuery(String normalizedQuery) {
        return jdbcTemplate.query("SELECT * FROM emerging_query_discovery WHERE normalized_query=?",
                mapper(), normalizedQuery).stream().findFirst();
    }

    @Override
    public Optional<QueryDiscovery> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM emerging_query_discovery WHERE id=?", mapper(), id).stream().findFirst();
    }

    @Override
    public List<QueryDiscovery> findRecent(int limit) {
        return jdbcTemplate.query("SELECT * FROM emerging_query_discovery ORDER BY updated_at DESC, id DESC LIMIT ?",
                mapper(), Math.max(1, Math.min(limit, 50)));
    }

    @Override
    @Transactional
    public QueryDiscovery save(QueryDraft draft) {
        Optional<QueryDiscovery> existing = findByNormalizedQuery(draft.normalizedQuery());
        long discoveryId = existing.map(QueryDiscovery::id).orElseGet(() -> insertDiscovery(draft));
        if (existing.isPresent()) {
            if (existing.get().publishedRoleId() != null) return existing.get();
            updateDiscovery(discoveryId, draft);
            jdbcTemplate.update("DELETE FROM emerging_query_field_evidence WHERE discovery_id=?", discoveryId);
            jdbcTemplate.update("DELETE FROM emerging_query_evidence WHERE discovery_id=?", discoveryId);
        }
        writeEvidence(discoveryId, draft);
        return findById(discoveryId).orElseThrow();
    }

    private long insertDiscovery(QueryDraft draft) {
        KeyHolder discoveryKeys = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO emerging_query_discovery (
                            query_text, normalized_query, query_hash, status, domain_name, canonical_name,
                            definition_text, responsibilities_json, required_skills_json, bonus_skills_json,
                            scenarios_json, nearest_role_id, nearest_role_name, nearest_role_similarity, novelty_score,
                            evidence_count, source_count, field_evidence_complete, web_search_used, publishable,
                            model_name, model_input_hash, error_code, error_message
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                setDiscoveryValues(statement, draft, 1);
                return statement;
            }, discoveryKeys);
        } catch (DuplicateKeyException duplicate) {
            return findByNormalizedQuery(draft.normalizedQuery()).orElseThrow().id();
        }
        return discoveryKeys.getKey().longValue();
    }

    private void updateDiscovery(long discoveryId, QueryDraft draft) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE emerging_query_discovery SET
                        query_text=?, normalized_query=?, query_hash=?, status=?, domain_name=?, canonical_name=?,
                        definition_text=?, responsibilities_json=?, required_skills_json=?, bonus_skills_json=?,
                        scenarios_json=?, nearest_role_id=?, nearest_role_name=?, nearest_role_similarity=?, novelty_score=?,
                        evidence_count=?, source_count=?, field_evidence_complete=?, web_search_used=?, publishable=?,
                        model_name=?, model_input_hash=?, error_code=?, error_message=?, updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND published_role_id IS NULL
                    """);
            int next = setDiscoveryValues(statement, draft, 1);
            statement.setLong(next, discoveryId);
            return statement;
        });
    }

    private int setDiscoveryValues(PreparedStatement statement, QueryDraft draft, int start) throws java.sql.SQLException {
        int sourceCount = (int) draft.evidence().stream()
                .map(item -> item.domain().toLowerCase(java.util.Locale.ROOT)).distinct().count();
        int index = start;
        statement.setString(index++, draft.query());
        statement.setString(index++, draft.normalizedQuery());
        statement.setString(index++, draft.queryHash());
        statement.setString(index++, draft.status());
        statement.setString(index++, truncate(draft.domain(), 120));
        statement.setString(index++, truncate(draft.name(), 120));
        statement.setString(index++, draft.definition());
        statement.setString(index++, json(draft.responsibilities()));
        statement.setString(index++, json(draft.requiredSkills()));
        statement.setString(index++, json(draft.bonusSkills()));
        statement.setString(index++, json(draft.scenarios()));
        Long persistentNearestRoleId = persistentNearestRoleId(draft.nearestRoleId());
        if (persistentNearestRoleId == null) {
            statement.setNull(index++, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index++, persistentNearestRoleId);
        }
        statement.setString(index++, draft.nearestRoleName());
        statement.setDouble(index++, draft.nearestRoleSimilarity());
        statement.setDouble(index++, draft.noveltyScore());
        statement.setInt(index++, draft.evidence().size());
        statement.setInt(index++, sourceCount);
        statement.setBoolean(index++, draft.fieldEvidenceComplete());
        statement.setBoolean(index++, draft.webSearchUsed());
        statement.setBoolean(index++, draft.publishable());
        statement.setString(index++, truncate(draft.modelName(), 120));
        statement.setString(index++, draft.modelInputHash());
        statement.setString(index++, blankToNull(draft.errorCode()));
        statement.setString(index++, blankToNull(truncate(draft.errorMessage(), 1000)));
        return index;
    }

    Long persistentNearestRoleId(Long roleId) {
        if (roleId == null) return null;
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_role WHERE id=?", Integer.class, roleId);
        return count != null && count > 0 ? roleId : null;
    }

    private void writeEvidence(long discoveryId, QueryDraft draft) {
        Map<String, Long> evidenceIds = new LinkedHashMap<>();
        for (var evidence : draft.evidence()) {
            KeyHolder evidenceKeys = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO emerging_query_evidence (
                            discovery_id, evidence_key, title, source_name, source_domain, source_url,
                            published_at_text, excerpt, url_hash
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, discoveryId);
                statement.setString(2, evidence.key());
                statement.setString(3, truncate(evidence.title(), 500));
                statement.setString(4, truncate(evidence.source(), 250));
                statement.setString(5, truncate(evidence.domain(), 250));
                statement.setString(6, truncate(evidence.url(), 2000));
                statement.setString(7, blankToNull(truncate(evidence.publishedAt(), 60)));
                statement.setString(8, evidence.excerpt());
                statement.setString(9, sha256(evidence.url()));
                return statement;
            }, evidenceKeys);
            evidenceIds.put(evidence.key(), evidenceKeys.getKey().longValue());
        }
        for (var citation : draft.fieldEvidence()) {
            Long evidenceId = evidenceIds.get(citation.evidenceKey());
            if (evidenceId == null) continue;
            jdbcTemplate.update("""
                    INSERT IGNORE INTO emerging_query_field_evidence
                    (discovery_id, field_name, field_value, evidence_id) VALUES (?, ?, ?, ?)
                    """, discoveryId, citation.fieldName(), truncate(citation.fieldValue(), 500), evidenceId);
        }
    }

    @Override
    @Transactional
    public QueryDiscovery publish(long discoveryId, String publisher) {
        QueryDiscovery discovery = findByIdForUpdate(discoveryId)
                .orElseThrow(() -> new BusinessException("QUERY_DISCOVERY_NOT_FOUND", "未找到查询发现结果"));
        if (discovery.publishedRoleId() != null) return discovery;

        if (!"EMERGING_ROLE".equals(discovery.status())) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "只有新兴岗位发现结果可以加入图谱");
        }
        if (!discovery.fieldEvidenceComplete()) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "岗位字段证据不完整，无法加入图谱");
        }
        if (!discovery.publishable()) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "该发现结果未满足发布条件，无法加入图谱");
        }

        // 验证：名称和定义不能为空（领域验证在Service层完成）
        if (discovery.name() == null || discovery.name().isBlank()
                || discovery.definition() == null || discovery.definition().isBlank()) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "岗位名称或定义为空，无法加入图谱");
        }

        Long snapshotId = jdbcTemplate.query("""
                SELECT target_snapshot_id FROM promotion_batch WHERE status='COMPLETED'
                ORDER BY completed_at DESC, id DESC LIMIT 1
                """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null);
        if (snapshotId == null) {
            throw new BusinessException("PUBLISHED_SNAPSHOT_REQUIRED", "当前没有已发布的真实 JD 快照");
        }
        String releaseVersion = EmergingReleaseVersions.next("emerging", discovery.id());
        List<String> technologyStack = java.util.stream.Stream.concat(
                discovery.requiredSkills().stream(), discovery.bonusSkills().stream()).distinct().toList();
        PublicationResult result = rolePublisher.publish(new PublicationInput(discovery.name(), "未明确", technologyStack,
                discovery.definition(),
                discovery.responsibilities(), discovery.requiredSkills(), discovery.bonusSkills(), discovery.scenarios(),
                discovery.evidenceCount(), discovery.sourceCount()), snapshotId, releaseVersion, "query-v1", publisher);
        jdbcTemplate.update("""
                INSERT INTO published_role_query_evidence (release_id, job_role_id, discovery_id, evidence_id)
                SELECT ?, ?, ?, id FROM emerging_query_evidence WHERE discovery_id=?
                """, result.releaseId(), result.roleId(), discoveryId, discoveryId);
        int changed = jdbcTemplate.update("""
                UPDATE emerging_query_discovery SET published_role_id=?, published_release_id=?, published_at=CURRENT_TIMESTAMP
                WHERE id=? AND published_role_id IS NULL
                """, result.roleId(), result.releaseId(), discoveryId);
        if (changed != 1) throw new BusinessException("QUERY_DISCOVERY_PUBLISH_CONFLICT", "发现结果已被其他请求发布");
        return findById(discoveryId).orElseThrow();
    }

    @Override
    @Transactional
    public QueryDiscovery removeFromGraph(long discoveryId, String operator) {
        QueryDiscovery discovery = findByIdForUpdate(discoveryId)
                .orElseThrow(() -> new BusinessException("QUERY_DISCOVERY_NOT_FOUND", "未找到查询发现结果"));
        if (discovery.publishedRoleId() == null) {
            throw new BusinessException("QUERY_DISCOVERY_NOT_PUBLISHED", "该岗位尚未加入图谱");
        }
        rolePublisher.remove(discovery.publishedRoleId(), operator);
        int changed = jdbcTemplate.update("""
                UPDATE emerging_query_discovery
                SET published_role_id=NULL, published_release_id=NULL, published_at=NULL
                WHERE id=? AND published_role_id=?
                """, discoveryId, discovery.publishedRoleId());
        if (changed != 1) throw new BusinessException("QUERY_DISCOVERY_REMOVE_CONFLICT", "岗位图谱状态已被其他请求修改");
        return findById(discoveryId).orElseThrow();
    }

    private Optional<QueryDiscovery> findByIdForUpdate(long discoveryId) {
        return jdbcTemplate.query("SELECT * FROM emerging_query_discovery WHERE id=? FOR UPDATE",
                mapper(), discoveryId).stream().findFirst();
    }

    private void requireFieldEvidence(QueryDiscovery discovery) {
        Map<String, List<String>> required = new LinkedHashMap<>();
        required.put("NAME", List.of(discovery.name()));
        required.put("DEFINITION", List.of(discovery.definition()));
        required.put("RESPONSIBILITY", discovery.responsibilities());
        required.put("REQUIRED_SKILL", discovery.requiredSkills());
        required.put("SCENARIO", discovery.scenarios());
        if (!discovery.bonusSkills().isEmpty()) required.put("BONUS_SKILL", discovery.bonusSkills());
        for (Map.Entry<String, List<String>> field : required.entrySet()) {
            for (String value : field.getValue()) {
                Long count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM emerging_query_field_evidence
                        WHERE discovery_id=? AND field_name=? AND field_value=?
                        """, Long.class, discovery.id(), field.getKey(), truncate(value, 500));
                if (count == null || count == 0) {
                    throw new BusinessException("QUERY_FIELD_EVIDENCE_REQUIRED", "字段缺少联网证据引用：" + value);
                }
            }
        }
    }

    private RowMapper<QueryDiscovery> mapper() {
        return (resultSet, rowNum) -> {
            long id = resultSet.getLong("id");
            List<QueryEvidence> evidence = evidence(id);
            List<QueryFieldEvidence> fieldEvidence = fieldEvidence(id);
            return new QueryDiscovery(id, resultSet.getString("query_text"), resultSet.getString("normalized_query"),
                    resultSet.getString("status"), resultSet.getString("domain_name"), resultSet.getString("canonical_name"),
                    resultSet.getString("definition_text"), strings(resultSet.getString("responsibilities_json")),
                    strings(resultSet.getString("required_skills_json")), strings(resultSet.getString("bonus_skills_json")),
                    strings(resultSet.getString("scenarios_json")), nullableLong(resultSet.getObject("nearest_role_id")),
                    resultSet.getString("nearest_role_name"), resultSet.getDouble("nearest_role_similarity"),
                    resultSet.getDouble("novelty_score"), resultSet.getInt("evidence_count"), resultSet.getInt("source_count"),
                    resultSet.getBoolean("field_evidence_complete"), resultSet.getBoolean("web_search_used"),
                    resultSet.getBoolean("publishable"), resultSet.getString("model_name"), resultSet.getString("error_code"),
                    resultSet.getString("error_message"), nullableLong(resultSet.getObject("published_role_id")),
                    nullableLong(resultSet.getObject("published_release_id")), evidence, fieldEvidence, false, 0, "",
                    dateTime(resultSet.getTimestamp("published_at")), dateTime(resultSet.getTimestamp("created_at")),
                    dateTime(resultSet.getTimestamp("updated_at")));
        };
    }

    private List<QueryEvidence> evidence(long discoveryId) {
        return jdbcTemplate.query("""
                SELECT * FROM emerging_query_evidence WHERE discovery_id=? ORDER BY id
                """, (resultSet, rowNum) -> new QueryEvidence(resultSet.getLong("id"), resultSet.getString("evidence_key"),
                resultSet.getString("title"), resultSet.getString("source_name"), resultSet.getString("source_domain"),
                resultSet.getString("source_url"), resultSet.getString("published_at_text"), resultSet.getString("excerpt")),
                discoveryId);
    }

    private List<QueryFieldEvidence> fieldEvidence(long discoveryId) {
        return jdbcTemplate.query("""
                SELECT qfe.field_name, qfe.field_value, qfe.evidence_id, qe.evidence_key
                FROM emerging_query_field_evidence qfe
                JOIN emerging_query_evidence qe ON qe.id=qfe.evidence_id
                WHERE qfe.discovery_id=? ORDER BY qfe.id
                """, (resultSet, rowNum) -> new QueryFieldEvidence(resultSet.getString("field_name"),
                resultSet.getString("field_value"), resultSet.getLong("evidence_id"),
                resultSet.getString("evidence_key")), discoveryId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化查询发现数据", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String truncate(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private OffsetDateTime dateTime(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(OFFSET);
    }
}
