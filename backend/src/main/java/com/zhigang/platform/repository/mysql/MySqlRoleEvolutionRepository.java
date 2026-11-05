package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.RoleClassificationPolicy;
import com.zhigang.platform.evolution.RoleEvolutionModels.ChangeDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.ChangeView;
import com.zhigang.platform.evolution.RoleEvolutionModels.DraftUpdate;
import com.zhigang.platform.evolution.RoleEvolutionModels.EvidenceDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.EvidenceView;
import com.zhigang.platform.evolution.RoleEvolutionModels.PointView;
import com.zhigang.platform.evolution.RoleEvolutionModels.RoleIdentity;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunView;
import com.zhigang.platform.evolution.RoleEvolutionModels.SkillAnchor;
import com.zhigang.platform.evolution.RoleEvolutionModels.SourceDocument;
import com.zhigang.platform.evolution.RoleEvolutionModels.WindowDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.WindowView;
import com.zhigang.platform.evolution.RoleEvolutionPolicyLoader;
import com.zhigang.platform.evolution.RoleEvolutionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Profile("mysql")
@Repository
public class MySqlRoleEvolutionRepository implements RoleEvolutionRepository {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RoleEvolutionPolicyLoader policyLoader;
    private final MySqlReleaseProtection releaseProtection;

    public MySqlRoleEvolutionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                        RoleEvolutionPolicyLoader policyLoader,
                                        MySqlReleaseProtection releaseProtection) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.policyLoader = policyLoader;
        this.releaseProtection = releaseProtection;
    }

    @Override
    public RoleIdentity role(long roleId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,role_code,role_name,is_emerging FROM job_role WHERE id=? AND status='PUBLISHED'", roleId);
        if (rows.isEmpty()) throw new BusinessException("ROLE_NOT_FOUND", "未找到已发布目标岗位");
        Map<String, Object> row = rows.get(0);
        String roleCode = string(row, "role_code");
        if (RoleClassificationPolicy.isEmerging(roleCode, booleanValue(row, "is_emerging"))) {
            throw new BusinessException("EVOLUTION_REQUIRES_EXISTING_ROLE", "岗位演化只分析既有岗位");
        }
        return new RoleIdentity(longValue(row, "id"), roleCode,
                MySqlPlatformDataProvider.repairMojibake(string(row, "role_name")));
    }

    @Override
    public List<SourceDocument> sourceDocuments(String roleCode) {
        List<Map<String, Object>> documents = jdbcTemplate.queryForList("""
                WITH ranked_documents AS (
                  SELECT rd.id,rd.document_title,rd.company_name,rd.raw_text,ds.source_name,
                         COALESCE(pdm.canonical_url,pdm.source_url,'') source_url,
                         rd.city,rd.level_name,rd.published_at,
                         ROW_NUMBER() OVER (PARTITION BY rd.content_hash ORDER BY rd.collected_at DESC,rd.id DESC) row_rank
                  FROM raw_document rd
                  JOIN data_source ds ON ds.id=rd.source_id
                  JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
                  JOIN dynamic_record dr ON dr.id=pdm.dynamic_record_id
                  WHERE rd.deleted_at IS NULL AND rd.is_demo=0
                    AND dr.status='PROMOTED' AND pdm.data_mode='REAL' AND pdm.trend_eligible=1
                    AND dr.status_reason LIKE ?
                )
                SELECT id,document_title,company_name,raw_text,source_name,source_url,city,level_name,published_at
                FROM ranked_documents WHERE row_rank=1 ORDER BY published_at,id
                """, "TARGET_ROLE:" + roleCode + "%");
        if (documents.isEmpty()) return List.of();
        List<Long> ids = documents.stream().map(row -> longValue(row, "id")).toList();
        Map<Long, List<SkillAnchor>> skills = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(ids, 200)) {
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Map<String, Object>> facts = jdbcTemplate.queryForList("""
                    SELECT ef.document_id,s.id skill_id,s.skill_name,fe.evidence_quote,fe.start_offset,fe.end_offset,
                           fe.anchor_valid,fe.evidence_direction
                    FROM extracted_fact ef
                    JOIN skill s ON s.id=ef.normalized_id
                    JOIN fact_evidence fe ON fe.fact_id=ef.id
                    WHERE ef.fact_type='SKILL' AND ef.gate_status='REVIEWED'
                      AND ef.document_id IN (""" + placeholders + ") ORDER BY ef.document_id,ef.id", chunk.toArray());
            for (Map<String, Object> fact : facts) {
                long documentId = longValue(fact, "document_id");
                skills.computeIfAbsent(documentId, ignored -> new ArrayList<>()).add(new SkillAnchor(
                        longValue(fact, "skill_id"), string(fact, "skill_name"), string(fact, "evidence_quote"),
                        intValue(fact, "start_offset"), intValue(fact, "end_offset"),
                        booleanValue(fact, "anchor_valid"), string(fact, "evidence_direction")));
            }
        }
        return documents.stream().map(row -> {
            String rawText = string(row, "raw_text");
            List<SkillAnchor> verifiedSkills = skills.getOrDefault(longValue(row, "id"), List.of()).stream()
                    .map(anchor -> new SkillAnchor(anchor.skillId(), anchor.skill(), anchor.quote(),
                            anchor.startOffset(), anchor.endOffset(), anchor.anchorValid() && matchesAnchor(
                            rawText, anchor.startOffset(), anchor.endOffset(), anchor.quote()), anchor.direction()))
                    .toList();
            return new SourceDocument(longValue(row, "id"), string(row, "document_title"),
                    string(row, "company_name"), string(row, "source_name"), string(row, "source_url"),
                    string(row, "city"), string(row, "level_name"), localDate(row.get("published_at")), verifiedSkills);
        }).toList();
    }

    @Override
    @Transactional
    public RunView save(RunDraft draft) {
        List<Long> existing = jdbcTemplate.queryForList("SELECT id FROM role_evolution_run WHERE run_code=?", Long.class, draft.runCode());
        if (!existing.isEmpty()) return find(existing.get(0)).orElseThrow();
        long runId = insert("""
                INSERT INTO role_evolution_run
                (run_code,job_role_id,policy_version,policy_hash,input_hash,status,conclusion_level,
                 sample_control,statistics_method,scope_json)
                VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS JSON))
                """, draft.runCode(), draft.roleId(), draft.policyVersion(), draft.policyHash(), draft.inputHash(),
                draft.status(), draft.conclusionLevel(), draft.sampleControl(), draft.statisticsMethod(), draft.scopeJson());
        for (WindowDraft window : draft.windows()) {
            jdbcTemplate.update("""
                    INSERT INTO role_evolution_window
                    (run_id,window_type,window_label,start_date,end_date,document_count,independent_company_count,
                     source_count,source_names_json,city_scope,level_scope,sample_hash)
                    VALUES (?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?,?)
                    """, runId, window.type(), window.label(), window.startDate(), window.endDate(),
                    window.documentCount(), window.companyCount(), window.sourceCount(), json(window.sourceNames()),
                    window.cityScope(), window.levelScope(), window.sampleHash());
        }
        for (ChangeDraft change : draft.changes()) saveChange(runId, change);
        return find(runId).orElseThrow();
    }

    private void saveChange(long runId, ChangeDraft change) {
        long changeId = insert("""
                INSERT INTO role_evolution_skill_change
                (run_id,skill_id,skill_name,change_type,confidence_level,baseline_document_count,
                 baseline_company_count,baseline_rate,baseline_lower_bound,baseline_upper_bound,
                 current_document_count,current_company_count,current_rate,current_lower_bound,current_upper_bound,
                 absolute_change,difference_lower_bound,difference_upper_bound,sensitivity_direction,
                 consecutive_absent_windows,publishable,included_in_draft)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, runId, change.skillId(), change.skill(), change.changeType(), change.confidenceLevel(),
                change.baselineDocumentCount(), change.baselineCompanyCount(), change.baselineRate(),
                change.baselineLowerBound(), change.baselineUpperBound(), change.currentDocumentCount(),
                change.currentCompanyCount(), change.currentRate(), change.currentLowerBound(), change.currentUpperBound(),
                change.absoluteChange(), change.differenceLowerBound(), change.differenceUpperBound(),
                change.sensitivityDirection(), change.consecutiveAbsentWindows(), change.publishable(), change.includedInDraft());
        for (EvidenceDraft evidence : change.evidence()) {
            jdbcTemplate.update("""
                    INSERT INTO role_evolution_evidence
                    (change_id,window_type,document_id,document_title,company_name,source_name,source_url,
                     published_at,evidence_quote,start_offset,end_offset,anchor_valid,evidence_direction)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, changeId, evidence.windowType(), evidence.documentId(), truncate(evidence.title(), 255),
                    truncate(evidence.company(), 255), truncate(evidence.source(), 255), truncate(evidence.sourceUrl(), 2048),
                    evidence.publishedAt(), truncate(evidence.quote(), 1024), evidence.startOffset(), evidence.endOffset(),
                    evidence.anchorValid(), evidence.direction());
        }
    }

    @Override
    public Optional<RunView> latest(long roleId) {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT id FROM role_evolution_run WHERE job_role_id=? ORDER BY created_at DESC,id DESC LIMIT 1
                """, Long.class, roleId);
        return ids.isEmpty() ? Optional.empty() : find(ids.get(0));
    }

    @Override
    public Optional<RunView> find(long runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT rer.*,jr.role_code,jr.role_name,gr.release_version published_release_version
                FROM role_evolution_run rer JOIN job_role jr ON jr.id=rer.job_role_id
                LEFT JOIN graph_release gr ON gr.id=rer.published_release_id WHERE rer.id=?
                """, runId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        List<WindowView> windows = windows(runId);
        Map<String, WindowView> byType = windows.stream().collect(java.util.stream.Collectors.toMap(WindowView::type, item -> item));
        List<ChangeView> changes = changes(runId, byType);
        return Optional.of(new RunView(runId, string(row, "run_code"), longValue(row, "job_role_id"),
                string(row, "role_code"), MySqlPlatformDataProvider.repairMojibake(string(row, "role_name")), string(row, "policy_version"),
                string(row, "policy_hash"), string(row, "input_hash"), string(row, "status"),
                string(row, "conclusion_level"), string(row, "sample_control"), string(row, "statistics_method"),
                intValue(row, "version"), string(row, "draft_note"), nullableLong(row.get("published_release_id")),
                string(row, "published_release_version"), windows, changes, dateTime(row.get("created_at")),
                dateTime(row.get("updated_at")), dateTime(row.get("published_at"))));
    }

    private List<WindowView> windows(long runId) {
        return jdbcTemplate.queryForList("SELECT * FROM role_evolution_window WHERE run_id=? ORDER BY start_date", runId)
                .stream().map(row -> new WindowView(longValue(row, "id"), string(row, "window_type"),
                        string(row, "window_label"), localDate(row.get("start_date")), localDate(row.get("end_date")),
                        intValue(row, "document_count"), intValue(row, "independent_company_count"),
                        intValue(row, "source_count"), jsonList(string(row, "source_names_json")),
                        string(row, "city_scope"), string(row, "level_scope"), string(row, "sample_hash"))).toList();
    }

    private List<ChangeView> changes(long runId, Map<String, WindowView> windows) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT * FROM role_evolution_skill_change WHERE run_id=?
                ORDER BY publishable DESC,ABS(absolute_change) DESC,skill_name
                """, runId);
        return rows.stream().map(row -> {
            long id = longValue(row, "id");
            List<PointView> points = new ArrayList<>();
            if (windows.containsKey("BASELINE")) points.add(point(windows.get("BASELINE"), row, "baseline"));
            if (windows.containsKey("CURRENT")) points.add(point(windows.get("CURRENT"), row, "current"));
            return new ChangeView(id, nullableLong(row.get("skill_id")), string(row, "skill_name"),
                    string(row, "change_type"), string(row, "confidence_level"), doubleValue(row, "absolute_change"),
                    doubleValue(row, "difference_lower_bound"), doubleValue(row, "difference_upper_bound"),
                    string(row, "sensitivity_direction"), intValue(row, "consecutive_absent_windows"),
                    booleanValue(row, "publishable"), booleanValue(row, "included_in_draft"), List.copyOf(points), evidence(id));
        }).toList();
    }

    private PointView point(WindowView window, Map<String, Object> row, String prefix) {
        return new PointView(window.label(), intValue(row, prefix + "_document_count"),
                intValue(row, prefix + "_company_count"), doubleValue(row, prefix + "_rate"),
                doubleValue(row, prefix + "_lower_bound"), doubleValue(row, prefix + "_upper_bound"));
    }

    private List<EvidenceView> evidence(long changeId) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM role_evolution_evidence WHERE change_id=?
                ORDER BY window_type,published_at,company_name,id
                """, changeId).stream().map(row -> new EvidenceView(longValue(row, "id"), string(row, "window_type"),
                longValue(row, "document_id"), string(row, "document_title"), string(row, "company_name"),
                string(row, "source_name"), string(row, "source_url"), localDate(row.get("published_at")),
                string(row, "evidence_quote"), intValue(row, "start_offset"), intValue(row, "end_offset"),
                booleanValue(row, "anchor_valid"), string(row, "evidence_direction"))).toList();
    }

    @Override
    @Transactional
    public RunView updateDraft(long runId, DraftUpdate update) {
        RunView current = required(runId);
        requireVersion(current, update.expectedVersion());
        if (current.publishedReleaseId() != null) throw new BusinessException("EVOLUTION_ALREADY_PUBLISHED", "已发布演化运行不能继续编辑");
        Set<Long> publishable = current.changes().stream().filter(ChangeView::publishable).map(ChangeView::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> requested = update.includedChangeIds() == null ? Set.of() : new LinkedHashSet<>(update.includedChangeIds());
        if (!publishable.containsAll(requested)) throw new BusinessException("EVOLUTION_CHANGE_NOT_PUBLISHABLE", "只能选择通过可信门禁的变化");
        String before = json(Map.of("note", current.draftNote(), "includedChangeIds", included(current)));
        jdbcTemplate.update("UPDATE role_evolution_skill_change SET included_in_draft=0 WHERE run_id=?", runId);
        if (!requested.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(requested.size(), "?"));
            List<Object> arguments = new ArrayList<>();
            arguments.add(runId);
            arguments.addAll(requested);
            jdbcTemplate.update("UPDATE role_evolution_skill_change SET included_in_draft=1 WHERE run_id=? AND id IN ("
                    + placeholders + ")", arguments.toArray());
        }
        int updated = jdbcTemplate.update("""
                UPDATE role_evolution_run SET draft_note=?,version=version+1 WHERE id=? AND version=?
                """, truncate(update.note(), 2048), runId, update.expectedVersion());
        if (updated != 1) throw optimistic();
        String after = json(Map.of("note", safe(update.note()), "includedChangeIds", requested));
        jdbcTemplate.update("""
                INSERT INTO role_evolution_revision
                (run_id,from_version,to_version,editor,reason,before_json,after_json)
                VALUES (?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON))
                """, runId, current.version(), current.version() + 1, truncate(update.editor(), 128),
                truncate(update.reason(), 1024), before, after);
        return required(runId);
    }

    @Override
    @Transactional
    public RunView publish(long runId, int expectedVersion, String releaseVersion, String publisher) {
        RunView current = required(runId);
        requireVersion(current, expectedVersion);
        if (current.publishedReleaseId() != null) return current;
        List<ChangeView> selected = current.changes().stream().filter(ChangeView::includedInDraft).toList();
        if (selected.isEmpty()) throw new BusinessException("EVOLUTION_NO_PUBLISHABLE_CHANGES", "没有可发布的可信岗位变化");
        if (selected.stream().anyMatch(change -> !validPublishableChange(change)
                || change.evidence().isEmpty() || change.evidence().stream().anyMatch(evidence -> !validEvidence(evidence)))) {
            throw new BusinessException("EVOLUTION_EVIDENCE_GATE_FAILED", "岗位变化存在无效或缺失证据");
        }
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM graph_release WHERE release_version=?", Long.class, releaseVersion) > 0) {
            throw new BusinessException("RELEASE_VERSION_EXISTS", "图谱版本号已存在");
        }
        Map<String, Object> previous = single("SELECT * FROM graph_release WHERE status='PUBLISHED' ORDER BY published_at DESC,id DESC LIMIT 1");
        long previousId = longValue(previous, "id");
        long releaseId = insert("""
                INSERT INTO graph_release
                (release_version,snapshot_id,policy_version,status,publisher_id,publisher_name,release_note)
                VALUES (?,?,?,'ARCHIVED',1,?,?)
                """, truncate(releaseVersion, 32), longValue(previous, "snapshot_id"), current.policyVersion(),
                truncate(publisher, 128),
                truncate("既有岗位演化：" + current.roleName() + "；可信变化 " + selected.size() + " 项；操作来源：" + publisher, 2048));
        copyRelease(previousId, releaseId);
        for (ChangeView change : selected) applyChange(releaseId, current.roleId(), change);
        for (ChangeView change : selected) {
            jdbcTemplate.update("""
                    INSERT INTO published_role_evolution (release_id,run_id,job_role_id,change_id) VALUES (?,?,?,?)
                    """, releaseId, runId, current.roleId(), change.id());
        }
        int updated = jdbcTemplate.update("""
                UPDATE role_evolution_run SET status='PUBLISHED',published_release_id=?,published_at=CURRENT_TIMESTAMP,
                    version=version+1 WHERE id=? AND version=?
                """, releaseId, runId, expectedVersion);
        if (updated != 1) throw optimistic();
        releaseProtection.freeze(releaseId);
        releaseProtection.activate(releaseId, publisher, "PUBLISH_ROLE_EVOLUTION");
        return required(runId);
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

    private boolean validPublishableChange(ChangeView change) {
        return change.publishable() && "FORMAL_CHANGE".equals(change.confidenceLevel())
                && !"STABLE".equals(change.changeType()) && change.points().size() == 2
                && (change.differenceLowerBound() > 0 || change.differenceUpperBound() < 0)
                && (!"REMOVAL_CANDIDATE".equals(change.changeType())
                || change.consecutiveAbsentWindows() >= policyLoader.policy().removalConsecutiveWindows());
    }

    private boolean validEvidence(EvidenceView evidence) {
        if (!evidence.anchorValid()) return false;
        List<String> texts = jdbcTemplate.queryForList("""
                SELECT raw_text FROM raw_document WHERE id=? AND deleted_at IS NULL AND is_demo=0
                """, String.class, evidence.documentId());
        return texts.size() == 1 && matchesAnchor(texts.get(0), evidence.startOffset(),
                evidence.endOffset(), evidence.quote());
    }

    private boolean matchesAnchor(String rawText, int start, int end, String quote) {
        return rawText != null && quote != null && start >= 0 && end > start && end <= rawText.length()
                && rawText.substring(start, end).equals(quote);
    }

    private void applyChange(long releaseId, long roleId, ChangeView change) {
        Long skillId = change.skillId();
        if (skillId == null) throw new BusinessException("EVOLUTION_SKILL_NOT_NORMALIZED", "岗位变化技能未进入技能本体");
        if ("REMOVAL_CANDIDATE".equals(change.changeType())) {
            if (change.consecutiveAbsentWindows() < policyLoader.policy().removalConsecutiveWindows()) {
                throw new BusinessException("EVOLUTION_REMOVAL_GATE_FAILED", "候选删除未经过连续窗口确认");
            }
            jdbcTemplate.update("DELETE FROM release_job_skill WHERE release_id=? AND job_role_id=? AND skill_id=?",
                    releaseId, roleId, skillId);
            return;
        }
        Long levelId = jdbcTemplate.query("""
                SELECT job_level_id FROM release_job_skill WHERE release_id=? AND job_role_id=? ORDER BY id LIMIT 1
                """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null, releaseId, roleId);
        if (levelId == null) levelId = jdbcTemplate.queryForObject("SELECT id FROM job_level ORDER BY sort_order,id LIMIT 1", Long.class);
        PointView current = change.points().get(change.points().size() - 1);
        jdbcTemplate.update("""
                INSERT INTO release_job_skill
                (release_id,job_role_id,job_level_id,skill_id,requirement_type,demand_rate,evidence_count,
                 independent_company_count,trend_type,effective_from)
                VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE demand_rate=VALUES(demand_rate),evidence_count=VALUES(evidence_count),
                  independent_company_count=VALUES(independent_company_count),trend_type=VALUES(trend_type),
                  effective_from=CURRENT_TIMESTAMP
                """, releaseId, roleId, levelId, skillId, "REQUIRED", current.demandRate(),
                current.documentCount(), current.companyCount(), change.changeType());
    }

    private RunView required(long runId) {
        return find(runId).orElseThrow(() -> new BusinessException("ROLE_EVOLUTION_RUN_NOT_FOUND", "未找到岗位演化运行"));
    }

    private void requireVersion(RunView current, int expected) {
        if (current.version() != expected) throw optimistic();
    }

    private BusinessException optimistic() {
        return new BusinessException("OPTIMISTIC_LOCK_FAILED", "岗位演化草稿已被更新，请刷新后重试");
    }

    private List<Long> included(RunView run) {
        return run.changes().stream().filter(ChangeView::includedInDraft).map(ChangeView::id).toList();
    }

    private long insert(String sql, Object... arguments) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new IllegalStateException("数据库未返回新增记录 ID");
        return holder.getKey().longValue();
    }

    private Map<String, Object> single(String sql, Object... arguments) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, arguments);
        if (rows.isEmpty()) throw new BusinessException("DATABASE_RECORD_NOT_FOUND", "数据库记录不存在");
        return rows.get(0);
    }

    private List<List<Long>> chunks(List<Long> values, int size) {
        List<List<Long>> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index += size) result.add(values.subList(index, Math.min(values.size(), index + size)));
        return result;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("演化审计 JSON 无法写入", exception); }
    }

    private List<String> jsonList(String value) {
        try { return value == null || value.isBlank() ? List.of() : objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("演化窗口来源无法解析", exception); }
    }

    private String truncate(String value, int max) { String safe = safe(value); return safe.length() <= max ? safe : safe.substring(0, max); }
    private String safe(String value) { return value == null ? "" : value; }
    private String string(Map<String, Object> row, String key) { return row.get(key) == null ? "" : row.get(key).toString(); }
    private long longValue(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private int intValue(Map<String, Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    private double doubleValue(Map<String, Object> row, String key) { return ((Number) row.get(key)).doubleValue(); }
    private boolean booleanValue(Map<String, Object> row, String key) { Object value=row.get(key); return value instanceof Boolean bool ? bool : ((Number) value).intValue()!=0; }
    private LocalDate localDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.time.LocalDateTime dateTime) return dateTime.toLocalDate();
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return ((Timestamp) value).toLocalDateTime().toLocalDate();
    }

    private OffsetDateTime dateTime(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime dateTime) return dateTime;
        if (value instanceof java.time.LocalDateTime dateTime) return dateTime.atOffset(OFFSET);
        return ((Timestamp) value).toInstant().atOffset(OFFSET);
    }
}
