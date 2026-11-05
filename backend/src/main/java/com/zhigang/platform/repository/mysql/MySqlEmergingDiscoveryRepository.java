package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.Evidence;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDraft;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDetail;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateEvidence;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateRevision;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateSeed;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryCandidate;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryRun;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.FieldEvidence;
import com.zhigang.platform.emerging.EmergingDiscoveryPolicy;
import com.zhigang.platform.emerging.EmergingDiscoveryRepository;
import com.zhigang.platform.repository.mysql.MySqlEmergingRolePublisher.PublicationInput;
import com.zhigang.platform.repository.mysql.MySqlEmergingRolePublisher.PublicationResult;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Profile("mysql")
@Repository
public class MySqlEmergingDiscoveryRepository implements EmergingDiscoveryRepository {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MySqlEmergingRolePublisher rolePublisher;
    private final EmergingDiscoveryPolicy policy;

    public MySqlEmergingDiscoveryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                            MySqlEmergingRolePublisher rolePublisher, EmergingDiscoveryPolicy policy) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rolePublisher = rolePublisher;
        this.policy = policy;
    }

    @Override
    public DiscoveryRun createRun(String runCode, String policyVersion, String policyHash, String inputHash,
                                  String embeddingModel, int documentCount, String requestedBy) {
        Long snapshotId = currentSnapshotId();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO emerging_discovery_run (
                        run_code, snapshot_id, status, policy_version, policy_hash, input_hash,
                        embedding_model, document_count, requested_by
                    ) VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, runCode);
            statement.setLong(2, snapshotId);
            statement.setString(3, policyVersion);
            statement.setString(4, policyHash);
            statement.setString(5, inputHash);
            statement.setString(6, embeddingModel);
            statement.setInt(7, documentCount);
            statement.setString(8, requestedBy);
            return statement;
        }, keys);
        return findRun(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public void markRunning(long runId) {
        jdbcTemplate.update("UPDATE emerging_discovery_run SET status='RUNNING', started_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'", runId);
    }

    @Override
    @Transactional
    public void complete(long runId, int embeddingDimension, String clusterModel, int clusterCount,
                         int candidateCount, int noiseCount, List<CandidateSeed> seeds) {
        List<Long> automaticCandidates = new ArrayList<>();
        for (CandidateSeed seed : seeds) {
            Long candidateId = saveSeed(runId, seed);
            if (candidateId != null && "AUTO_PUBLISH_READY".equals(seed.status())) automaticCandidates.add(candidateId);
        }
        jdbcTemplate.update("""
                UPDATE emerging_discovery_run
                SET status='COMPLETED', embedding_dimension=?, cluster_model=?, cluster_count=?, candidate_count=?,
                    noise_count=?, completed_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='RUNNING'
                """, embeddingDimension, clusterModel, clusterCount, candidateCount, noiseCount, runId);
        Long latestCompletedRunId = jdbcTemplate.query("""
                SELECT id FROM emerging_discovery_run
                WHERE status='COMPLETED' ORDER BY id DESC LIMIT 1 FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null);
        if (latestCompletedRunId == null) {
            throw new IllegalStateException("发现运行完成后未找到成功运行记录");
        }
        jdbcTemplate.update("""
                UPDATE change_proposal SET status='SUPERSEDED', updated_at=CURRENT_TIMESTAMP
                WHERE id IN (
                    SELECT proposal_id FROM emerging_candidate ec
                    JOIN emerging_cluster cl ON cl.id=ec.cluster_id
                    JOIN emerging_discovery_run edr ON edr.id=cl.run_id
                    WHERE cl.run_id<>? AND edr.status='COMPLETED' AND ec.status IN (
                        'WAITING_GENERATION','EVIDENCE_INSUFFICIENT','DRAFT','PENDING_REVIEW','APPROVED'
                    ) AND ec.proposal_id IS NOT NULL
                ) AND status IN ('PENDING_REVIEW','APPROVED')
                """, latestCompletedRunId);
        for (Long candidateId : automaticCandidates) publishAutomatically(candidateId);
        jdbcTemplate.update("""
                UPDATE emerging_candidate ec
                JOIN emerging_cluster cl ON cl.id=ec.cluster_id
                JOIN emerging_discovery_run edr ON edr.id=cl.run_id
                SET ec.status='SUPERSEDED'
                WHERE cl.run_id<>? AND edr.status='COMPLETED' AND ec.status IN (
                    'WAITING_GENERATION','EVIDENCE_INSUFFICIENT','DRAFT','PENDING_REVIEW','APPROVED'
                )
                """, latestCompletedRunId);
    }

    @Override
    public void fail(long runId, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE emerging_discovery_run SET status='FAILED', error_code=?, error_message=?, completed_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, errorCode, truncate(errorMessage, 1000), runId);
    }

    @Override
    public List<DiscoveryRun> findRuns() {
        return jdbcTemplate.query("SELECT * FROM emerging_discovery_run ORDER BY id DESC", runMapper());
    }

    @Override
    public Optional<DiscoveryRun> findRun(long runId) {
        return jdbcTemplate.query("SELECT * FROM emerging_discovery_run WHERE id=?", runMapper(), runId).stream().findFirst();
    }

    @Override
    public List<DiscoveryCandidate> findCandidates() {
        return jdbcTemplate.query(candidateSql() + " WHERE ec.status<>'SUPERSEDED' ORDER BY ec.updated_at DESC",
                candidateMapper());
    }

    @Override
    public Optional<DiscoveryCandidate> findCandidate(long candidateId) {
        return jdbcTemplate.query(candidateSql() + " WHERE ec.id=?", candidateMapper(), candidateId).stream().findFirst();
    }

    @Override
    public CandidateDetail findCandidateDetail(long candidateId) {
        DiscoveryCandidate candidate = candidate(candidateId);
        List<CandidateEvidence> evidence = jdbcTemplate.query("""
                SELECT e.id, e.field_name, e.field_value, e.document_id, e.start_offset, e.end_offset,
                       e.evidence_quote, e.direction, e.anchor_valid, ds.source_name, rd.company_name
                FROM emerging_candidate_evidence e
                JOIN raw_document rd ON rd.id=e.document_id
                JOIN data_source ds ON ds.id=rd.source_id
                WHERE e.candidate_id=? ORDER BY e.id
                """, (rs, rowNum) -> new CandidateEvidence(rs.getLong("id"), rs.getString("field_name"),
                rs.getString("field_value"), rs.getLong("document_id"), rs.getInt("start_offset"),
                rs.getInt("end_offset"), rs.getString("evidence_quote"), rs.getString("direction"),
                rs.getBoolean("anchor_valid"), rs.getString("source_name"), rs.getString("company_name")), candidateId);
        List<CandidateRevision> revisions = jdbcTemplate.query("""
                SELECT id, from_version, to_version, before_json, after_json, reason,
                       evidence_ids_json, editor, created_at
                FROM emerging_candidate_revision WHERE candidate_id=? ORDER BY to_version DESC
                """, (rs, rowNum) -> new CandidateRevision(rs.getLong("id"), rs.getInt("from_version"),
                rs.getInt("to_version"), objectMap(rs.getString("before_json")), objectMap(rs.getString("after_json")),
                rs.getString("reason"), longs(rs.getString("evidence_ids_json")), rs.getString("editor"),
                dateTime(rs.getTimestamp("created_at"))), candidateId);
        return new CandidateDetail(candidate, List.copyOf(evidence), List.copyOf(revisions));
    }

    @Override
    @Transactional
    public DiscoveryCandidate updateDraft(long candidateId, CandidateDraft draft) {
        DiscoveryCandidate current = candidate(candidateId);
        requireRevisionEvidence(candidateId, draft.evidenceIds());
        int changed = jdbcTemplate.update("""
                UPDATE emerging_candidate SET candidate_name=?, job_level=?, technology_stack_json=?,
                    responsibilities_json=?, required_skills_json=?, bonus_skills_json=?, scenarios_json=?,
                    version=version+1, creator=?, status='EMERGING_OBSERVATION'
                WHERE id=? AND version=? AND status IN ('DRAFT','EVIDENCE_INSUFFICIENT','EMERGING_OBSERVATION')
                """, draft.name(), draft.jobLevel(), json(draft.technologyStack()), json(draft.responsibilities()),
                json(draft.requiredSkills()), json(draft.bonusSkills()), json(draft.scenarios()), draft.editor(),
                candidateId, draft.expectedVersion());
        if (changed != 1) conflict();
        DiscoveryCandidate updated = candidate(candidateId);
        saveRevisionEvidence(candidateId, draft);
        jdbcTemplate.update("""
                INSERT INTO emerging_candidate_revision (
                    candidate_id, from_version, to_version, before_json, after_json, reason, evidence_ids_json, editor
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, candidateId, current.version(), updated.version(), json(snapshot(current)), json(snapshot(updated)),
                draft.reason(), json(draft.evidenceIds()), draft.editor());
        return updated;
    }

    @Override
    @Transactional
    public DiscoveryCandidate submit(long candidateId, int expectedVersion, String creator) {
        DiscoveryCandidate current = candidate(candidateId);
        if (!"RAG_SCHEMA_EVIDENCE".equals(current.generationMode())) {
            throw new BusinessException("CANDIDATE_GENERATION_REQUIRED", "候选尚未完成受证据约束的岗位定义生成");
        }
        requireCurrentFieldEvidence(current);
        long invalidEvidence = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM emerging_candidate_evidence WHERE candidate_id=? AND anchor_valid=0
                """, Long.class, candidateId);
        if (invalidEvidence > 0) throw new BusinessException("CANDIDATE_EVIDENCE_INVALID", "候选仍有无效证据锚点");
        int changed = jdbcTemplate.update("""
                UPDATE emerging_candidate SET status='PENDING_REVIEW', creator=?, submitted_at=CURRENT_TIMESTAMP
                WHERE id=? AND version=? AND status='DRAFT'
                """, creator, candidateId, expectedVersion);
        if (changed != 1) conflict();
        long proposalId = createProposal(current, creator);
        jdbcTemplate.update("UPDATE emerging_candidate SET proposal_id=? WHERE id=?", proposalId, candidateId);
        return candidate(candidateId);
    }

    @Override
    @Transactional
    public DiscoveryCandidate review(long candidateId, int expectedVersion, String decision, String reviewer, String comment) {
        DiscoveryCandidate current = candidate(candidateId);
        if (reviewer.equals(current.creator())) throw new BusinessException("SELF_REVIEW_FORBIDDEN", "创建人不能审核自己的候选");
        String status = "APPROVE".equalsIgnoreCase(decision) ? "APPROVED" : "REJECTED";
        int changed = jdbcTemplate.update("""
                UPDATE emerging_candidate SET status=?, reviewer=?, review_comment=?, reviewed_at=CURRENT_TIMESTAMP
                WHERE id=? AND version=? AND status='PENDING_REVIEW'
                """, status, reviewer, comment, candidateId, expectedVersion);
        if (changed != 1) conflict();
        jdbcTemplate.update("""
                INSERT INTO emerging_candidate_review (candidate_id, candidate_version, decision, reviewer, comment)
                VALUES (?, ?, ?, ?, ?)
                """, candidateId, expectedVersion, decision.toUpperCase(), reviewer, comment);
        jdbcTemplate.update("""
                UPDATE change_proposal SET status=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=(SELECT proposal_id FROM emerging_candidate WHERE id=?)
                """, status, candidateId);
        Long proposalId = jdbcTemplate.queryForObject("SELECT proposal_id FROM emerging_candidate WHERE id=?", Long.class, candidateId);
        if (proposalId != null) jdbcTemplate.update("""
                INSERT INTO review_record (proposal_id, reviewer_id, decision, comment) VALUES (?, 1, ?, ?)
                """, proposalId, decision.toUpperCase(), comment);
        return candidate(candidateId);
    }

    @Override
    @Transactional
    public DiscoveryCandidate publish(long candidateId, int expectedVersion, String version, String publisher,
                                      EmergingDiscoveryPolicy policy) {
        DiscoveryCandidate candidate = candidate(candidateId);
        if (candidate.version() != expectedVersion || !"APPROVED".equals(candidate.status())) conflict();
        EmergingDiscoveryPolicy.Gate gate = policy.publishGate();
        if (candidate.documentCount() < gate.minimumDocuments() || candidate.companyCount() < gate.minimumCompanies()
                || candidate.sourceCount() < gate.minimumSources() || candidate.emergenceScore() / 100 < policy.publishThreshold()
                || candidate.metrics().getOrDefault("previousWindowCompanies", 0.0) < gate.minimumCompaniesPerWindow()
                || candidate.metrics().getOrDefault("currentWindowCompanies", 0.0) < gate.minimumCompaniesPerWindow()) {
            throw new BusinessException("CANDIDATE_PUBLISH_GATE_FAILED", "候选未达到发布支持度、连续窗口或校准分数门槛");
        }
        Long snapshotId = jdbcTemplate.queryForObject("""
                SELECT edr.snapshot_id FROM emerging_candidate ec JOIN emerging_cluster cl ON cl.id=ec.cluster_id
                JOIN emerging_discovery_run edr ON edr.id=cl.run_id WHERE ec.id=?
                """, Long.class, candidateId);
        PublicationResult publication = rolePublisher.publish(new PublicationInput(candidate.name(), candidate.jobLevel(),
                candidate.technologyStack(),
                "由真实 JD 语义聚类发现，岗位职责、技能与场景均绑定原文证据。",
                candidate.responsibilities(), candidate.requiredSkills(), candidate.bonusSkills(), candidate.scenarios(),
                candidate.documentCount(), candidate.companyCount()), snapshotId, version, policy.version(), publisher);
        long roleId = publication.roleId();
        long releaseId = publication.releaseId();
        jdbcTemplate.update("""
                INSERT INTO published_role_document (release_id, job_role_id, document_id, candidate_id)
                SELECT ?, ?, ecm.document_id, ? FROM emerging_candidate ec
                JOIN emerging_cluster_member ecm ON ecm.cluster_id=ec.cluster_id WHERE ec.id=? AND ecm.noise=0
                """, releaseId, roleId, candidateId, candidateId);
        jdbcTemplate.update("""
                UPDATE emerging_candidate SET status='PUBLISHED', published_role_id=?, published_release_id=?, published_at=CURRENT_TIMESTAMP
                WHERE id=? AND version=? AND status='APPROVED'
                """, roleId, releaseId, candidateId, expectedVersion);
        return candidate(candidateId);
    }

    private Long saveSeed(long runId, CandidateSeed seed) {
        KeyHolder clusterKeys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO emerging_cluster (
                        run_id, cluster_label, status, document_count, company_count, source_count,
                        median_probability, nearest_role_id, nearest_role_name, nearest_role_similarity,
                        emergence_score, metric_json, opposing_evidence_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, runId); statement.setInt(2, seed.clusterLabel()); statement.setString(3, seed.status());
            statement.setInt(4, seed.documentCount()); statement.setInt(5, seed.companyCount()); statement.setInt(6, seed.sourceCount());
            statement.setDouble(7, seed.medianProbability());
            if (seed.nearestRoleId() == null) statement.setNull(8, java.sql.Types.BIGINT); else statement.setLong(8, seed.nearestRoleId());
            statement.setString(9, seed.nearestRoleName()); statement.setDouble(10, seed.nearestRoleSimilarity());
            statement.setDouble(11, seed.emergenceScore()); statement.setString(12, json(seed.metrics()));
            statement.setString(13, json(seed.opposingEvidence()));
            return statement;
        }, clusterKeys);
        long clusterId = clusterKeys.getKey().longValue();
        seed.members().forEach(member -> jdbcTemplate.update("""
                INSERT INTO emerging_cluster_member (cluster_id, document_id, membership_probability, noise)
                VALUES (?, ?, ?, ?)
                """, clusterId, member.documentId(), member.probability(), member.noise()));
        if (!List.of("EMERGING_OBSERVATION", "AUTO_PUBLISH_READY", "DRAFT", "WAITING_GENERATION", "EVIDENCE_INSUFFICIENT")
                .contains(seed.status())) return null;
        KeyHolder candidateKeys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO emerging_candidate (
                        cluster_id, candidate_name, job_level, technology_stack_json, status, uncertainty,
                        responsibilities_json, required_skills_json, bonus_skills_json, scenarios_json,
                        generation_mode, generation_input_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            List<String> technologyStack = java.util.stream.Stream.concat(
                    seed.requiredSkills().stream(), seed.bonusSkills().stream()).distinct().toList();
            statement.setLong(1, clusterId); statement.setString(2, seed.candidateName()); statement.setString(3, "未明确");
            statement.setString(4, json(technologyStack)); statement.setString(5, seed.status());
            statement.setString(6, seed.uncertainty()); statement.setString(7, json(seed.responsibilities()));
            statement.setString(8, json(seed.requiredSkills())); statement.setString(9, json(seed.bonusSkills()));
            statement.setString(10, json(seed.scenarios())); statement.setString(11, seed.generationMode());
            statement.setString(12, seed.generationInputHash()); return statement;
        }, candidateKeys);
        long candidateId = candidateKeys.getKey().longValue();
        seed.evidence().forEach(evidence -> saveEvidence(candidateId, evidence));
        return candidateId;
    }

    private void publishAutomatically(long candidateId) {
        DiscoveryCandidate candidate = candidate(candidateId);
        if (!"AUTO_PUBLISH_READY".equals(candidate.status())) return;
        EmergingDiscoveryPolicy.Gate gate = policy.publishGate();
        if (candidate.documentCount() < gate.minimumDocuments() || candidate.companyCount() < gate.minimumCompanies()
                || candidate.sourceCount() < gate.minimumSources()
                || candidate.emergenceScore() / 100 < policy.publishThreshold()
                || candidate.metrics().getOrDefault("previousWindowCompanies", 0.0) < gate.minimumCompaniesPerWindow()
                || candidate.metrics().getOrDefault("currentWindowCompanies", 0.0) < gate.minimumCompaniesPerWindow()) {
            throw new BusinessException("CANDIDATE_AUTO_PUBLISH_GATE_FAILED", "候选未达到自动入图门槛");
        }
        if (!"RAG_SCHEMA_EVIDENCE".equals(candidate.generationMode())) {
            throw new BusinessException("CANDIDATE_GENERATION_REQUIRED", "候选缺少受证据约束的岗位定义");
        }
        requireCurrentFieldEvidence(candidate);
        Long snapshotId = jdbcTemplate.queryForObject("""
                SELECT edr.snapshot_id FROM emerging_candidate ec JOIN emerging_cluster cl ON cl.id=ec.cluster_id
                JOIN emerging_discovery_run edr ON edr.id=cl.run_id WHERE ec.id=?
                """, Long.class, candidateId);
        PublicationResult publication = rolePublisher.publish(new PublicationInput(candidate.name(), candidate.jobLevel(),
                candidate.technologyStack(),
                "由真实 JD 语义聚类自动发现；岗位职责、技能与场景均绑定原文证据。",
                candidate.responsibilities(), candidate.requiredSkills(), candidate.bonusSkills(), candidate.scenarios(),
                candidate.documentCount(), candidate.companyCount()), snapshotId,
                "AUTO-DISCOVERY-" + candidateId, policy.version(), "自动发现引擎");
        jdbcTemplate.update("""
                INSERT INTO published_role_document (release_id, job_role_id, document_id, candidate_id)
                SELECT ?, ?, ecm.document_id, ? FROM emerging_candidate ec
                JOIN emerging_cluster_member ecm ON ecm.cluster_id=ec.cluster_id
                WHERE ec.id=? AND ecm.noise=0
                """, publication.releaseId(), publication.roleId(), candidateId, candidateId);
        int changed = jdbcTemplate.update("""
                UPDATE emerging_candidate
                SET status='AUTO_PUBLISHED', published_role_id=?, published_release_id=?, published_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='AUTO_PUBLISH_READY'
                """, publication.roleId(), publication.releaseId(), candidateId);
        if (changed != 1) conflict();
    }

    private void saveEvidence(long candidateId, FieldEvidence evidence) {
        jdbcTemplate.update("""
                INSERT INTO emerging_candidate_evidence (
                    candidate_id, field_name, field_value, document_id, start_offset, end_offset,
                    evidence_quote, direction, anchor_valid
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, candidateId, evidence.fieldName(), truncate(evidence.fieldValue(), 500), evidence.documentId(),
                evidence.startOffset(), evidence.endOffset(), truncate(evidence.quote(), 1000), evidence.direction(), evidence.anchorValid());
    }

    private long createProposal(DiscoveryCandidate candidate, String creator) {
        Long snapshotId = jdbcTemplate.queryForObject("""
                SELECT edr.snapshot_id FROM emerging_candidate ec JOIN emerging_cluster cl ON cl.id=ec.cluster_id
                JOIN emerging_discovery_run edr ON edr.id=cl.run_id WHERE ec.id=?
                """, Long.class, candidate.id());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_proposal (proposal_code, proposal_type, title, reason, status, creator_id, snapshot_id)
                    VALUES (?, 'EMERGING_ROLE', ?, ?, 'PENDING_REVIEW', 1, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "EMERGING-" + candidate.id() + "-V" + candidate.version());
            statement.setString(2, "新岗位提案：" + candidate.name());
            statement.setString(3, "基于 " + candidate.documentCount() + " 条 JD、" + candidate.companyCount() + " 家企业和 " + candidate.sourceCount() + " 个来源");
            statement.setLong(4, snapshotId); return statement;
        }, keys);
        long proposalId = keys.getKey().longValue();
        jdbcTemplate.update("""
                INSERT INTO proposal_item (proposal_id, item_type, change_type, target_type, payload_json, evidence_count)
                VALUES (?, 'JOB_ROLE', 'ADD', 'JOB_ROLE', ?, ?)
                """, proposalId, json(snapshot(candidate)), candidate.supportingEvidence().size());
        return proposalId;
    }

    private String candidateSql() {
        return """
                SELECT ec.*, cl.run_id, cl.document_count, cl.company_count, cl.source_count,
                       cl.median_probability, cl.nearest_role_name, cl.nearest_role_similarity,
                       cl.emergence_score, cl.metric_json, cl.opposing_evidence_json
                FROM emerging_candidate ec JOIN emerging_cluster cl ON cl.id=ec.cluster_id
                """;
    }

    private RowMapper<DiscoveryRun> runMapper() {
        return (rs, rowNum) -> new DiscoveryRun(rs.getLong("id"), rs.getString("run_code"), rs.getString("status"),
                rs.getString("policy_version"), rs.getString("policy_hash"), rs.getString("input_hash"),
                rs.getString("embedding_model"), integer(rs.getObject("embedding_dimension")), rs.getString("cluster_model"),
                rs.getInt("document_count"), rs.getInt("cluster_count"), rs.getInt("candidate_count"), rs.getInt("noise_count"),
                rs.getString("error_code"), rs.getString("error_message"), rs.getString("requested_by"),
                dateTime(rs.getTimestamp("started_at")), dateTime(rs.getTimestamp("completed_at")), dateTime(rs.getTimestamp("created_at")));
    }

    private RowMapper<DiscoveryCandidate> candidateMapper() {
        return (rs, rowNum) -> new DiscoveryCandidate(rs.getLong("id"), rs.getLong("run_id"), rs.getInt("version"),
                rs.getString("candidate_name"), rs.getString("job_level"), strings(rs.getString("technology_stack_json")),
                rs.getString("nearest_role_name"), rs.getDouble("nearest_role_similarity"),
                rs.getDouble("emergence_score"), rs.getString("uncertainty"), rs.getInt("document_count"),
                rs.getInt("company_count"), rs.getInt("source_count"), rs.getDouble("median_probability"),
                rs.getString("status"), rs.getString("generation_mode"), map(rs.getString("metric_json")),
                strings(rs.getString("responsibilities_json")), strings(rs.getString("required_skills_json")),
                strings(rs.getString("bonus_skills_json")), strings(rs.getString("scenarios_json")),
                evidence(rs.getLong("id")), strings(rs.getString("opposing_evidence_json")), rs.getString("creator"),
                rs.getString("reviewer"), dateTime(rs.getTimestamp("updated_at")));
    }

    private List<Evidence> evidence(long candidateId) {
        return jdbcTemplate.query("""
                SELECT e.document_id, ds.source_name, rd.company_name, rd.published_at, e.evidence_quote,
                       e.start_offset, e.end_offset, e.anchor_valid
                FROM emerging_candidate_evidence e JOIN raw_document rd ON rd.id=e.document_id
                JOIN data_source ds ON ds.id=rd.source_id
                WHERE e.candidate_id=? AND e.direction='SUPPORT' ORDER BY e.id LIMIT 24
                """, (rs, rowNum) -> new Evidence(rs.getLong("document_id"), rs.getString("source_name"),
                rs.getString("company_name"), dateTime(rs.getTimestamp("published_at")), rs.getString("evidence_quote"),
                rs.getInt("start_offset"), rs.getInt("end_offset"), 1, rs.getBoolean("anchor_valid") ? "EVIDENCE_VALID" : "EVIDENCE_INVALID"), candidateId);
    }

    private Long currentSnapshotId() {
        Long id = jdbcTemplate.query("""
                SELECT target_snapshot_id FROM promotion_batch WHERE status='COMPLETED'
                ORDER BY completed_at DESC, id DESC LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (id == null) throw new BusinessException("PUBLISHED_SNAPSHOT_REQUIRED", "当前没有已发布的真实 JD 快照");
        return id;
    }

    private DiscoveryCandidate candidate(long id) { return findCandidate(id).orElseThrow(() -> new BusinessException("EMERGING_CANDIDATE_NOT_FOUND", "未找到新岗位候选")); }
    private void conflict() { throw new BusinessException("CANDIDATE_VERSION_CONFLICT", "候选状态或版本已变化，请刷新后重试"); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("无法序列化发现数据", exception); } }
    private List<String> strings(String json) { try { return json == null ? List.of() : objectMapper.readValue(json, new TypeReference<>() {}); } catch (Exception exception) { return List.of(); } }
    private Map<String, Double> map(String json) { try { return json == null ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {}); } catch (Exception exception) { return Map.of(); } }
    private Map<String, Object> objectMap(String json) { try { return json == null ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {}); } catch (Exception exception) { return Map.of(); } }
    private List<Long> longs(String json) { try { return json == null ? List.of() : objectMapper.readValue(json, new TypeReference<>() {}); } catch (Exception exception) { return List.of(); } }

    private void requireRevisionEvidence(long candidateId, List<Long> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            throw new BusinessException("CANDIDATE_REVISION_EVIDENCE_REQUIRED", "字段修改必须选择至少一条有效原文证据");
        }
        String placeholders = evidenceIds.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> arguments = new ArrayList<>();
        arguments.add(candidateId);
        arguments.addAll(evidenceIds);
        Long valid = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM emerging_candidate_evidence "
                        + "WHERE candidate_id=? AND anchor_valid=1 AND id IN (" + placeholders + ")",
                Long.class, arguments.toArray());
        if (valid == null || valid != evidenceIds.stream().distinct().count()) {
            throw new BusinessException("CANDIDATE_REVISION_EVIDENCE_INVALID", "所选证据不属于当前候选或锚点无效");
        }
    }

    private void saveRevisionEvidence(long candidateId, CandidateDraft draft) {
        Map<String, List<String>> fields = new java.util.LinkedHashMap<>();
        fields.put("NAME", List.of(draft.name()));
        fields.put("JOB_LEVEL", List.of(draft.jobLevel()));
        fields.put("TECHNOLOGY_STACK", draft.technologyStack());
        fields.put("RESPONSIBILITY", draft.responsibilities());
        fields.put("REQUIRED_SKILL", draft.requiredSkills());
        fields.put("BONUS_SKILL", draft.bonusSkills());
        fields.put("SCENARIO", draft.scenarios());
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            for (String value : field.getValue()) {
                for (Long evidenceId : draft.evidenceIds().stream().distinct().toList()) {
                    jdbcTemplate.update("""
                            INSERT INTO emerging_candidate_evidence (
                                candidate_id, field_name, field_value, document_id, start_offset, end_offset,
                                evidence_quote, direction, anchor_valid
                            )
                            SELECT ?, ?, ?, document_id, start_offset, end_offset, evidence_quote, 'SUPPORT', anchor_valid
                            FROM emerging_candidate_evidence
                            WHERE id=? AND candidate_id=? AND anchor_valid=1
                            """, candidateId, "USER_REVISION_" + field.getKey(), truncate(value, 500), evidenceId, candidateId);
                }
            }
        }
    }

    private void requireCurrentFieldEvidence(DiscoveryCandidate candidate) {
        List<String> requiredValues = new ArrayList<>();
        requiredValues.add(candidate.name());
        if (!"未明确".equals(candidate.jobLevel())) requiredValues.add(candidate.jobLevel());
        requiredValues.addAll(candidate.technologyStack());
        requiredValues.addAll(candidate.responsibilities());
        requiredValues.addAll(candidate.requiredSkills());
        requiredValues.addAll(candidate.bonusSkills());
        requiredValues.addAll(candidate.scenarios());
        for (String value : requiredValues.stream().distinct().toList()) {
            Long count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM emerging_candidate_evidence
                    WHERE candidate_id=? AND field_value=? AND direction='SUPPORT' AND anchor_valid=1
                    """, Long.class, candidate.id(), truncate(value, 500));
            if (count == null || count == 0) {
                throw new BusinessException("CANDIDATE_FIELD_EVIDENCE_REQUIRED", "当前字段缺少有效原文证据：" + value);
            }
        }
    }
    private Map<String, Object> snapshot(DiscoveryCandidate value) { return Map.of("name", value.name(), "jobLevel", value.jobLevel(), "technologyStack", value.technologyStack(), "responsibilities", value.responsibilities(), "requiredSkills", value.requiredSkills(), "bonusSkills", value.bonusSkills(), "scenarios", value.scenarios()); }
    private Integer integer(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private OffsetDateTime dateTime(Timestamp value) { return value == null ? null : value.toInstant().atOffset(OFFSET); }
    private String truncate(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max); }
}
