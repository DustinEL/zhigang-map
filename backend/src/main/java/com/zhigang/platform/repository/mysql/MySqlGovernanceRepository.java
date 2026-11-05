package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.api.Requests.ChangeProposalRequest;
import com.zhigang.platform.api.Requests.GraphReleaseRequest;
import com.zhigang.platform.api.Requests.ReviewRequest;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.ChangeProposal;
import com.zhigang.platform.domain.PlatformModels.GraphRelease;
import com.zhigang.platform.domain.PlatformModels.ReleaseDiff;
import com.zhigang.platform.governance.GovernanceRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Profile("mysql")
@Repository
public class MySqlGovernanceRepository implements GovernanceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MySqlReleaseProtection releaseProtection;

    public MySqlGovernanceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                     MySqlReleaseProtection releaseProtection) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.releaseProtection = releaseProtection;
    }

    @Override
    public List<ChangeProposal> proposals() {
        return jdbcTemplate.query("""
                SELECT cp.*, rr.reviewer_name, rr.reviewed_at
                FROM change_proposal cp
                LEFT JOIN review_record rr ON rr.id=(SELECT MAX(id) FROM review_record WHERE proposal_id=cp.id)
                ORDER BY cp.created_at DESC
                """, (resultSet, rowNum) -> new ChangeProposal(resultSet.getLong("id"), resultSet.getString("title"),
                resultSet.getString("proposal_type"), resultSet.getString("status"), resultSet.getString("creator_name"),
                resultSet.getString("reviewer_name"), resultSet.getString("reason"), proposalChanges(resultSet.getLong("id")),
                dateTime(resultSet.getTimestamp("created_at")), dateTime(resultSet.getTimestamp("reviewed_at"))));
    }

    @Override
    @Transactional
    public ChangeProposal create(ChangeProposalRequest request) {
        Long snapshotId = jdbcTemplate.query("SELECT id FROM dataset_snapshot ORDER BY created_at DESC,id DESC LIMIT 1",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null);
        if (snapshotId == null) throw new BusinessException("SNAPSHOT_NOT_FOUND", "没有可关联的数据快照");
        long proposalId = insert("""
                INSERT INTO change_proposal
                    (proposal_code, proposal_type, title, reason, status, creator_id, creator_name, snapshot_id)
                VALUES (?, ?, ?, ?, 'PENDING_REVIEW', 1, '岗位研究员', ?)
                """, "P-" + UUID.randomUUID(), request.type(), request.title(), request.reason(), snapshotId);
        for (String change : request.changes()) {
            jdbcTemplate.update("""
                    INSERT INTO proposal_item
                        (proposal_id, item_type, change_type, target_type, payload_json, evidence_count)
                    VALUES (?, 'GRAPH_FACT', ?, 'GRAPH', ?, 0)
                    """, proposalId, changeType(change), json(Map.of("change", change)));
        }
        return proposal(proposalId);
    }

    @Override
    @Transactional
    public ChangeProposal review(long proposalId, ReviewRequest request) {
        ChangeProposal current = proposal(proposalId);
        if (current.creator().equals(request.reviewer())) {
            throw new BusinessException("SELF_REVIEW_FORBIDDEN", "创建人不能独立审核自己的提案");
        }
        String status = "APPROVE".equalsIgnoreCase(request.decision()) ? "APPROVED" : "REJECTED";
        jdbcTemplate.update("UPDATE change_proposal SET status=? WHERE id=?", status, proposalId);
        jdbcTemplate.update("""
                INSERT INTO review_record (proposal_id, reviewer_id, reviewer_name, decision, comment)
                VALUES (?, 1, ?, ?, ?)
                """, proposalId, request.reviewer(), request.decision().toUpperCase(), request.comment());
        return proposal(proposalId);
    }

    @Override
    public List<GraphRelease> releases() {
        return jdbcTemplate.query("""
                SELECT gr.*, ds.snapshot_code FROM graph_release gr
                JOIN dataset_snapshot ds ON ds.id=gr.snapshot_id
                ORDER BY COALESCE(gr.published_at, gr.created_at) DESC, gr.id DESC
                """, (resultSet, rowNum) -> release(resultSet.getLong("id"), resultSet.getString("release_version"),
                resultSet.getString("status"), resultSet.getString("snapshot_code"),
                resultSet.getString("policy_version"), resultSet.getString("publisher_name"),
                resultSet.getString("release_note"), dateTime(resultSet.getTimestamp("published_at"))));
    }

    @Override
    public GraphRelease currentRelease() {
        return releases().stream().filter(release -> "PUBLISHED".equals(release.status())).findFirst()
                .orElseThrow(() -> new BusinessException("CURRENT_RELEASE_NOT_FOUND", "当前没有生效版本"));
    }

    @Override
    @Transactional
    public GraphRelease publish(GraphReleaseRequest request, String policyVersion) {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM graph_release WHERE release_version=?",
                Long.class, request.version()) > 0) {
            throw new BusinessException("RELEASE_VERSION_EXISTS", "图谱版本号已存在，请使用新的版本号");
        }
        List<ChangeProposal> selected = request.proposalIds() == null ? List.of()
                : request.proposalIds().stream().map(this::proposal).toList();
        if (selected.stream().anyMatch(proposal -> !"APPROVED".equals(proposal.status()))) {
            throw new BusinessException("PROPOSAL_NOT_APPROVED", "图谱发布只能包含已审核通过的变更提案");
        }
        Long snapshotId = jdbcTemplate.query("SELECT id FROM dataset_snapshot WHERE snapshot_code=?",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null, request.snapshotId());
        if (snapshotId == null) throw new BusinessException("SNAPSHOT_NOT_FOUND", "未找到发布快照");
        Long previousId = jdbcTemplate.query("SELECT id FROM graph_release WHERE status='PUBLISHED'",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null);
        String note = selected.stream().flatMap(item -> item.changes().stream()).reduce((left, right) -> left + "；" + right)
                .orElse("后台受控发布");
        long releaseId = insert("""
                INSERT INTO graph_release
                    (release_version, snapshot_id, policy_version, status, publisher_id, publisher_name, release_note)
                VALUES (?, ?, ?, 'ARCHIVED', 1, ?, ?)
                """, request.version(), snapshotId, policyVersion, request.publisher(), note);
        if (previousId != null) copyRelease(previousId, releaseId);
        releaseProtection.freeze(releaseId);
        releaseProtection.activate(releaseId, request.publisher(), "PUBLISH");
        return releaseById(releaseId);
    }

    @Override
    @Transactional
    public GraphRelease rollback(long releaseId, String publisher) {
        releaseProtection.freeze(releaseId);
        releaseProtection.activate(releaseId, publisher, "ROLLBACK");
        return releaseById(releaseId);
    }

    @Override
    public ReleaseDiff diff(long releaseId) {
        GraphRelease target = releaseById(releaseId);
        Long previousId = jdbcTemplate.query("""
                SELECT id FROM graph_release WHERE id<>? AND created_at < (SELECT created_at FROM graph_release WHERE id=? )
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null, releaseId, releaseId);
        if (previousId == null) return new ReleaseDiff(target.version(), target.version(), List.of(), List.of(), List.of(), List.of());
        GraphRelease previous = releaseById(previousId);
        Set<String> before = facts(previousId);
        Set<String> after = facts(releaseId);
        List<String> added = after.stream().filter(item -> !before.contains(item)).sorted().toList();
        List<String> removed = before.stream().filter(item -> !after.contains(item)).sorted().toList();
        return new ReleaseDiff(previous.version(), target.version(), added, List.of(), List.of(), removed);
    }

    @Override
    public CurrentReleaseSummary currentSummary() {
        return jdbcTemplate.query("""
                SELECT gr.release_version, gr.policy_version, gr.published_at, ds.raw_count, ds.snapshot_name
                FROM graph_release gr JOIN dataset_snapshot ds ON ds.id=gr.snapshot_id
                WHERE gr.status='PUBLISHED' ORDER BY gr.published_at DESC,gr.id DESC LIMIT 1
                """, resultSet -> {
            if (!resultSet.next()) throw new BusinessException("CURRENT_RELEASE_NOT_FOUND", "当前没有生效版本");
            return new CurrentReleaseSummary(resultSet.getString("release_version"),
                    resultSet.getString("snapshot_name"), resultSet.getInt("raw_count"),
                    resultSet.getString("policy_version"), dateTime(resultSet.getTimestamp("published_at")));
        });
    }

    private ChangeProposal proposal(long id) {
        return proposals().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException("PROPOSAL_NOT_FOUND", "未找到变更提案"));
    }

    private List<String> proposalChanges(long proposalId) {
        return jdbcTemplate.queryForList("SELECT payload_json FROM proposal_item WHERE proposal_id=? ORDER BY id",
                String.class, proposalId).stream().map(value -> {
            try {
                return objectMapper.readValue(value, new TypeReference<Map<String, String>>() {}).getOrDefault("change", value);
            } catch (Exception exception) {
                return value;
            }
        }).toList();
    }

    private GraphRelease releaseById(long id) {
        return releases().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException("RELEASE_NOT_FOUND", "未找到图谱版本"));
    }

    private GraphRelease release(long id, String version, String status, String snapshotCode, String policyVersion,
                                 String publisher, String note, OffsetDateTime publishedAt) {
        List<String> changes = note == null || note.isBlank() ? List.of() : List.of(note.split("；"));
        return new GraphRelease(id, version, status, snapshotCode, policyVersion,
                publisher == null ? "系统发布" : publisher, changes, publishedAt);
    }

    private Set<String> facts(long releaseId) {
        Set<String> facts = new HashSet<>();
        facts.addAll(jdbcTemplate.queryForList("SELECT CONCAT('技能:',job_role_id,':',job_level_id,':',skill_id,':',requirement_type) FROM release_job_skill WHERE release_id=?", String.class, releaseId));
        facts.addAll(jdbcTemplate.queryForList("SELECT CONCAT('职责:',job_role_id,':',job_level_id,':',responsibility_id) FROM release_job_responsibility WHERE release_id=?", String.class, releaseId));
        facts.addAll(jdbcTemplate.queryForList("SELECT CONCAT('场景:',job_role_id,':',scenario_id) FROM release_job_scenario WHERE release_id=?", String.class, releaseId));
        return facts;
    }

    private void copyRelease(long previousId, long releaseId) {
        jdbcTemplate.update("""
                INSERT INTO release_job_skill
                    (release_id,job_role_id,job_level_id,skill_id,requirement_type,demand_rate,strong_demand_rate,
                     evidence_count,independent_company_count,trend_type,effective_from)
                SELECT ?,job_role_id,job_level_id,skill_id,requirement_type,demand_rate,strong_demand_rate,
                    evidence_count,independent_company_count,trend_type,effective_from
                FROM release_job_skill WHERE release_id=?
                """, releaseId, previousId);
        jdbcTemplate.update("""
                INSERT INTO release_job_responsibility
                    (release_id,job_role_id,job_level_id,responsibility_id,evidence_count,weight)
                SELECT ?,job_role_id,job_level_id,responsibility_id,evidence_count,weight
                FROM release_job_responsibility WHERE release_id=?
                """, releaseId, previousId);
        jdbcTemplate.update("""
                INSERT INTO release_job_scenario (release_id,job_role_id,scenario_id,evidence_count)
                SELECT ?,job_role_id,scenario_id,evidence_count FROM release_job_scenario WHERE release_id=?
                """, releaseId, previousId);
    }

    private long insert(String sql, Object... arguments) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private String changeType(String value) {
        if (value.startsWith("新增")) return "ADD";
        if (value.startsWith("删除")) return "REMOVE";
        if (value.startsWith("弱化")) return "WEAKEN";
        return "CHANGE";
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("治理数据序列化失败", exception); }
    }

    private OffsetDateTime dateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }
}
