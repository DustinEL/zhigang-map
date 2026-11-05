package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillEvidence;
import com.zhigang.platform.source.StructuredJdModels;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillSummary;
import com.zhigang.platform.source.StructuredJdModels.BulkCandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.BulkDynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.DynamicFact;
import com.zhigang.platform.source.StructuredJdModels.DynamicQualityIssue;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordDetail;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordPage;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordSummary;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordQuery;
import com.zhigang.platform.source.StructuredJdModels.DynamicReview;
import com.zhigang.platform.source.StructuredJdModels.DynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.FactReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchView;
import com.zhigang.platform.source.StructuredJdModels.PromotionItemView;
import com.zhigang.platform.source.StructuredJdModels.PromotionPublishCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.RealEvidence;
import com.zhigang.platform.source.StructuredJdModels.RealResponsibilityMetric;
import com.zhigang.platform.source.StructuredJdModels.RealRoleSnapshot;
import com.zhigang.platform.source.StructuredJdModels.RealSkillMetric;
import com.zhigang.platform.source.StructuredJdModels.RealSnapshotView;
import com.zhigang.platform.source.StructuredJdModels.ReviewQueueSummary;
import com.zhigang.platform.source.StructuredJdModels.StructuredImportResult;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdBatch;
import com.zhigang.platform.source.StructuredJdModels.SourceLedgerItem;
import com.zhigang.platform.source.StructuredJdModels.ValidatedDocument;
import com.zhigang.platform.source.StructuredJdModels.TriageAssignment;
import com.zhigang.platform.source.StructuredJdModels.TriageCandidate;
import com.zhigang.platform.source.StructuredJdRepository;
import com.zhigang.platform.source.CandidateSkillAdvisor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Profile("mysql")
@Repository
public class MySqlStructuredJdRepository implements StructuredJdRepository {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private static final String TARGET_ROLE_PREFIX = "TARGET_ROLE:";
    private static final Map<String, String> ROLE_NAMES = Map.ofEntries(
            Map.entry("AI_AGENT_ENGINEER", "AI Agent 开发工程师"),
            Map.entry("JAVA_ENGINEER", "Java 开发工程师"),
            Map.entry("RAG_APP_ENGINEER", "RAG 应用开发工程师"),
            Map.entry("AI_APP_BACKEND_ENGINEER", "AI 应用后端工程师"),
            Map.entry("LLMOPS_ENGINEER", "LLMOps / 模型服务工程师"),
            Map.entry("LLM_EVALUATION_ENGINEER", "大模型评测工程师"),
            Map.entry("LLM_APP_ENGINEER", "大模型应用开发工程师"),
            Map.entry("AI_PLATFORM_ENGINEER", "AI 平台开发工程师"),
            Map.entry("AI_DATA_ENGINEER", "AI 数据工程师"),
            Map.entry("KNOWLEDGE_ENGINEER", "知识工程师"),
            Map.entry("VECTOR_RETRIEVAL_ENGINEER", "向量检索工程师"),
            Map.entry("AI_SECURITY_TEST_ENGINEER", "AI 安全测试工程师")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public MySqlStructuredJdRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public StructuredImportResult saveImport(StructuredJdBatch batch, List<ValidatedDocument> documents) {
        var first = documents.get(0).document();
        String sourceCode = first.source().sourceId();
        jdbcTemplate.update("""
                INSERT INTO data_source
                    (source_code, source_name, source_type, license_status, license_note, connector_type, enabled)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE source_name=VALUES(source_name), source_type=VALUES(source_type),
                    license_status=VALUES(license_status), license_note=VALUES(license_note),
                    connector_type=VALUES(connector_type), updated_at=CURRENT_TIMESTAMP
                """, sourceCode, first.source().name(), first.source().sourceTier(), first.source().licenseStatus(),
                first.source().licenseNote(), first.source().adapter());
        long sourceId = requiredLong("SELECT id FROM data_source WHERE source_code=?", sourceCode);
        String timeScope = first.timeScope();
        String jobCode = "JD_IMPORT_" + shortHash(sourceCode + "|" + timeScope).substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO crawler_job
                    (job_code, job_name, source_id, schedule_cron, target_type, parser_version, enabled)
                VALUES (?, ?, ?, 'MANUAL', ?, ?, 0)
                ON DUPLICATE KEY UPDATE source_id=VALUES(source_id), parser_version=VALUES(parser_version),
                    updated_at=CURRENT_TIMESTAMP
                """, jobCode, first.source().name() + " · " + timeScope, sourceId,
                "JD_" + timeScope, batch.processingPolicy().version());
        long jobId = requiredLong("SELECT id FROM crawler_job WHERE job_code=?", jobCode);
        String runCode = runCode(batch.batchKey());
        long runId = insertAndGetId("""
                INSERT INTO crawler_run
                    (run_code, crawler_job_id, source_file_name, source_file_hash, schema_version, policy_version,
                     status, fetched_count, started_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?, CURRENT_TIMESTAMP)
                """, runCode, jobId, batch.sourceWorkbook().fileName(), batch.sourceWorkbook().sha256(),
                batch.schemaVersion(), batch.processingPolicy().version(), documents.size());

        int accepted = 0;
        int duplicates = 0;
        int quarantined = 0;
        Map<String, RegistryDecision> registry = loadCandidateRegistry(documents);
        List<Object[]> skillRows = new ArrayList<>();
        List<Object[]> responsibilityRows = new ArrayList<>();
        List<Object[]> issueRows = new ArrayList<>();
        for (ValidatedDocument validated : documents) {
            var document = validated.document();
            Integer existing = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dynamic_record
                    WHERE content_hash=?
                    """, Integer.class, document.contentHash());
            if (existing != null && existing > 0) {
                duplicates++;
                continue;
            }
            long recordId = insertAndGetId("""
                    INSERT INTO dynamic_record (
                        crawler_run_id, source_id, source_record_key, source_url, canonical_url, title,
                        company_name, city, experience_requirement, education_requirement, salary_raw,
                        salary_min_monthly, salary_max_monthly, salary_months, currency, published_at,
                        collected_at, raw_payload, raw_text, content_hash, parser_version, data_mode,
                        status, quality_score, status_reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, ?, ?)
                    """, runId, sourceId, document.sourceRecordKey(), document.source().originalUrl(),
                    document.source().canonicalUrl(), document.jobTitle(), document.company(), document.city(),
                    document.experienceRequirement(), document.educationRequirement(), document.salary().raw(),
                    document.salary().minMonthly(), document.salary().maxMonthly(), document.salary().salaryMonths(),
                    document.salary().currency(), timestamp(document.publishedAt()),
                    Timestamp.valueOf(document.collectedAt().atStartOfDay()), json(document), document.rawText(),
                    document.contentHash(), batch.processingPolicy().version(), document.dataMode(),
                    validated.status(), validated.qualityScore(),
                    document.statusReasons() == null ? "" : String.join("；", document.statusReasons()));
            collectFacts(recordId, document, registry, skillRows, responsibilityRows);
            validated.issues().forEach(issue -> issueRows.add(new Object[]{recordId, issue.type(),
                    issue.severity(), issue.detail(), issue.blocking()}));
            accepted++;
            if ("QUARANTINED".equals(validated.status())) {
                quarantined++;
            }
        }
        saveFacts(skillRows, responsibilityRows);
        if (!issueRows.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO dynamic_quality_issue
                        (dynamic_record_id, issue_type, severity, detail, blocking)
                    VALUES (?, ?, ?, ?, ?)
                    """, issueRows);
        }
        jdbcTemplate.update("""
                UPDATE crawler_run
                SET status='COMPLETED', accepted_count=?, duplicate_count=?, rejected_count=?,
                    completed_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, accepted, duplicates, quarantined, runId);
        return findImport(runId);
    }

    @Override
    public Optional<StructuredImportResult> findImportByBatchKey(String batchKey) {
        List<Long> ids = jdbcTemplate.query("SELECT id FROM crawler_run WHERE run_code=?",
                (rs, row) -> rs.getLong("id"), runCode(batchKey));
        return ids.isEmpty() ? Optional.empty() : Optional.of(findImport(ids.get(0)));
    }

    private void collectFacts(long recordId, StructuredJdModels.StructuredJdDocument document,
                              Map<String, RegistryDecision> registry, List<Object[]> skillRows,
                              List<Object[]> responsibilityRows) {
        document.facts().skills().forEach(fact -> {
            RegistryDecision review = candidateDecision(fact, registry);
            skillRows.add(new Object[]{recordId, fact.ontologyStatus(), fact.canonicalSkill(),
                    fact.suggestedCategory(), fact.rawValue(), fact.requirementType(), fact.startOffset(),
                    fact.endOffset(), fact.evidenceQuote(), fact.confidence(), fact.offsetVerified(),
                    review.decision(), review.correctedValue(), review.note(), review.reviewer(), review.reviewedAt()});
        });
        document.facts().responsibilities().forEach(fact -> responsibilityRows.add(new Object[]{recordId,
                fact.normalizedValue(), fact.rawValue(), fact.startOffset(), fact.endOffset(),
                fact.evidenceQuote(), fact.confidence(), fact.offsetVerified()}));
    }

    private void saveFacts(List<Object[]> skillRows, List<Object[]> responsibilityRows) {
        if (!skillRows.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                INSERT INTO dynamic_fact (
                    dynamic_record_id, fact_type, ontology_status, canonical_value, suggested_category,
                    raw_value, requirement_type, start_offset, end_offset, evidence_quote, confidence,
                    offset_verified, review_decision,
                    corrected_value, review_note, reviewed_by, reviewed_at
                ) VALUES (?, 'SKILL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, skillRows);
        }
        if (!responsibilityRows.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                INSERT INTO dynamic_fact (
                    dynamic_record_id, fact_type, ontology_status, canonical_value, raw_value,
                    start_offset, end_offset, evidence_quote, confidence, offset_verified, review_decision
                ) VALUES (?, 'RESPONSIBILITY', 'STANDARD', ?, ?, ?, ?, ?, ?, ?, 'APPROVED_EXISTING')
                """, responsibilityRows);
        }
    }

    @Override
    public StructuredImportResult findImport(long id) {
        List<StructuredImportResult> results = jdbcTemplate.query("""
                SELECT id, run_code, source_file_name, source_file_hash, fetched_count, accepted_count,
                       duplicate_count, rejected_count, status, policy_version, created_at,
                       (SELECT COUNT(*) FROM dynamic_record dr WHERE dr.crawler_run_id=cr.id AND dr.review_route='AUTO_APPROVED') auto_approved_count,
                       (SELECT COUNT(*) FROM dynamic_record dr WHERE dr.crawler_run_id=cr.id AND dr.review_route='SAMPLE_REVIEW') sampled_count,
                       (SELECT COUNT(*) FROM dynamic_record dr WHERE dr.crawler_run_id=cr.id AND dr.review_route='EXCEPTION_REVIEW') exception_count,
                       (SELECT COUNT(*) FROM dynamic_record dr WHERE dr.crawler_run_id=cr.id AND dr.review_route='WAITING_SKILL') waiting_skill_count
                FROM crawler_run cr WHERE id=?
                """, (rs, row) -> new StructuredImportResult(rs.getLong("id"), rs.getString("run_code"),
                rs.getString("source_file_name"), rs.getString("source_file_hash"),
                rs.getInt("fetched_count"), rs.getInt("accepted_count"), rs.getInt("duplicate_count"),
                rs.getInt("rejected_count"), rs.getInt("auto_approved_count"), rs.getInt("sampled_count"),
                rs.getInt("exception_count"), rs.getInt("waiting_skill_count"),
                rs.getString("status"), rs.getString("policy_version"),
                dateTime(rs.getTimestamp("created_at"))), id);
        if (results.isEmpty()) {
            throw missing("IMPORT_NOT_FOUND", "未找到导入批次");
        }
        return results.get(0);
    }

    @Override
    public List<SourceLedgerItem> findSourceLedger() {
        return jdbcTemplate.query("""
                SELECT ds.source_code, ds.source_name, ds.source_type, ds.connector_type,
                       CASE WHEN cj.target_type IN ('JD_CURRENT', 'JD_HISTORICAL', 'JD_UNKNOWN_TIME')
                            THEN SUBSTRING(cj.target_type, 4) ELSE 'UNKNOWN_TIME' END AS time_scope,
                       COUNT(cr.id) AS batch_count,
                       COALESCE(SUM(cr.fetched_count), 0) AS collected_count,
                       COALESCE(SUM(cr.duplicate_count), 0) AS duplicate_count,
                       COALESCE(SUM(cr.accepted_count), 0) AS accepted_count,
                       COALESCE(SUM(cr.rejected_count), 0) AS exception_count,
                       MAX(cr.created_at) AS updated_at
                FROM data_source ds
                JOIN crawler_job cj ON cj.source_id=ds.id
                JOIN crawler_run cr ON cr.crawler_job_id=cj.id
                GROUP BY ds.source_code, ds.source_name, ds.source_type, ds.connector_type, time_scope
                ORDER BY ds.source_name, time_scope
                """, (rs, row) -> new SourceLedgerItem(
                rs.getString("source_code"), rs.getString("source_name"), rs.getString("source_type"),
                rs.getString("time_scope"), rs.getString("connector_type"), rs.getInt("batch_count"),
                rs.getInt("collected_count"), rs.getInt("duplicate_count"), rs.getInt("accepted_count"),
                rs.getInt("exception_count"), dateTime(rs.getTimestamp("updated_at"))));
    }

    @Override
    public DynamicRecordPage findDynamicRecords(DynamicRecordQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> arguments = new ArrayList<>();
        if (query.importId() != null) {
            where.append(" AND dr.crawler_run_id=?");
            arguments.add(query.importId());
        }
        if (query.status() != null) {
            where.append(" AND dr.status=?");
            arguments.add(query.status());
        }
        if (query.keyword() != null) {
            where.append(" AND (dr.title LIKE ? OR dr.company_name LIKE ? OR dr.city LIKE ?)");
            String keyword = "%" + query.keyword().replace("%", "\\%").replace("_", "\\_") + "%";
            arguments.add(keyword);
            arguments.add(keyword);
            arguments.add(keyword);
        }
        if (query.riskLevel() != null) {
            where.append(" AND dr.risk_level=?");
            arguments.add(query.riskLevel());
        }
        if (query.reviewRoute() != null) {
            where.append(" AND dr.review_route=?");
            arguments.add(query.reviewRoute());
        }
        if (query.onlyExceptions()) {
            where.append(" AND dr.review_route IN ('SAMPLE_REVIEW','EXCEPTION_REVIEW','WAITING_SKILL')");
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dynamic_record dr" + where,
                Long.class, arguments.toArray());
        arguments.add(query.size());
        arguments.add((query.page() - 1) * query.size());
        List<DynamicRecordSummary> items = jdbcTemplate.query("""
                SELECT dr.id, dr.crawler_run_id, dr.source_record_key, dr.title, dr.company_name, dr.city,
                       dr.content_hash, dr.data_mode, dr.status, dr.quality_score, dr.collected_at,
                       dr.risk_level, dr.review_route, dr.review_reasons, dr.review_source,
                       dr.triage_policy_version, dr.triaged_at,
                       SUM(CASE WHEN df.fact_type='SKILL' THEN 1 ELSE 0 END) skill_count,
                       SUM(CASE WHEN df.ontology_status='CANDIDATE' THEN 1 ELSE 0 END) candidate_count,
                       (SELECT COUNT(*) FROM dynamic_quality_issue qi WHERE qi.dynamic_record_id=dr.id) issue_count,
                       COALESCE((SELECT rr.decision FROM dynamic_review_record rr
                                 WHERE rr.dynamic_record_id=dr.id ORDER BY rr.id DESC LIMIT 1), 'PENDING_REVIEW') review_decision
                FROM dynamic_record dr
                LEFT JOIN dynamic_fact df ON df.dynamic_record_id=dr.id
                """ + where + "\n" + """
                GROUP BY dr.id
                ORDER BY dr.id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new DynamicRecordSummary(rs.getLong("id"), rs.getLong("crawler_run_id"),
                rs.getString("source_record_key"), rs.getString("title"), rs.getString("company_name"),
                rs.getString("city"), rs.getString("content_hash"), rs.getString("data_mode"),
                rs.getString("status"), rs.getDouble("quality_score"), rs.getInt("skill_count"),
                rs.getInt("candidate_count"), rs.getInt("issue_count"), rs.getString("review_decision"),
                rs.getString("risk_level"), rs.getString("review_route"), jsonList(rs.getString("review_reasons")),
                rs.getString("review_source"), rs.getString("triage_policy_version"),
                dateTime(rs.getTimestamp("triaged_at")),
                dateTime(rs.getTimestamp("collected_at"))), arguments.toArray());
        return new DynamicRecordPage(items, query.page(), query.size(), total == null ? 0 : total);
    }

    @Override
    public DynamicRecordDetail findDynamicRecord(long id) {
        List<DynamicRecordDetail> details = jdbcTemplate.query("""
                SELECT dr.*, ds.source_name, ds.license_status,
                       COALESCE((SELECT rr.decision FROM dynamic_review_record rr
                                 WHERE rr.dynamic_record_id=dr.id ORDER BY rr.id DESC LIMIT 1), 'PENDING_REVIEW') review_decision,
                       (SELECT COUNT(*) FROM dynamic_fact df WHERE df.dynamic_record_id=dr.id AND df.fact_type='SKILL') skill_count,
                       (SELECT COUNT(*) FROM dynamic_fact df WHERE df.dynamic_record_id=dr.id AND df.ontology_status='CANDIDATE') candidate_count,
                       (SELECT COUNT(*) FROM dynamic_quality_issue qi WHERE qi.dynamic_record_id=dr.id) issue_count
                FROM dynamic_record dr
                JOIN data_source ds ON ds.id=dr.source_id
                WHERE dr.id=?
                """, (rs, row) -> {
            DynamicRecordSummary summary = new DynamicRecordSummary(rs.getLong("id"),
                    rs.getLong("crawler_run_id"), rs.getString("source_record_key"), rs.getString("title"),
                    rs.getString("company_name"), rs.getString("city"), rs.getString("content_hash"),
                    rs.getString("data_mode"), rs.getString("status"), rs.getDouble("quality_score"),
                    rs.getInt("skill_count"), rs.getInt("candidate_count"), rs.getInt("issue_count"),
                    rs.getString("review_decision"), rs.getString("risk_level"), rs.getString("review_route"),
                    jsonList(rs.getString("review_reasons")), rs.getString("review_source"),
                    rs.getString("triage_policy_version"), dateTime(rs.getTimestamp("triaged_at")),
                    dateTime(rs.getTimestamp("collected_at")));
            return new DynamicRecordDetail(summary, rs.getString("source_name"), rs.getString("license_status"),
                    rs.getString("source_url"), rs.getString("canonical_url"), rs.getString("salary_raw"),
                    rs.getString("experience_requirement"), rs.getString("education_requirement"),
                    dateTime(rs.getTimestamp("published_at")), rs.getString("raw_text"),
                    findFacts(id), findIssues(id), findReviews(id));
        }, id);
        if (details.isEmpty()) {
            throw missing("DYNAMIC_RECORD_NOT_FOUND", "未找到动态资料");
        }
        return details.get(0);
    }

    private List<DynamicFact> findFacts(long recordId) {
        return jdbcTemplate.query("""
                SELECT id, fact_type, ontology_status, canonical_value, suggested_category, raw_value,
                       requirement_type, start_offset, end_offset, evidence_quote, confidence,
                       offset_verified, review_decision, corrected_value, review_note
                FROM dynamic_fact WHERE dynamic_record_id=? ORDER BY fact_type DESC, start_offset
                """, (rs, row) -> new DynamicFact(rs.getLong("id"), rs.getString("fact_type"),
                rs.getString("ontology_status"), rs.getString("canonical_value"),
                rs.getString("suggested_category"), rs.getString("raw_value"),
                rs.getString("requirement_type"), rs.getInt("start_offset"), rs.getInt("end_offset"),
                rs.getString("evidence_quote"), rs.getDouble("confidence"), rs.getBoolean("offset_verified"),
                rs.getString("review_decision"), rs.getString("corrected_value"),
                rs.getString("review_note")), recordId);
    }

    private List<DynamicQualityIssue> findIssues(long recordId) {
        return jdbcTemplate.query("""
                SELECT id, issue_type, severity, detail, blocking
                FROM dynamic_quality_issue WHERE dynamic_record_id=? ORDER BY blocking DESC, id
                """, (rs, row) -> new DynamicQualityIssue(rs.getLong("id"), rs.getString("issue_type"),
                rs.getString("severity"), rs.getString("detail"), rs.getBoolean("blocking")), recordId);
    }

    private List<DynamicReview> findReviews(long recordId) {
        return jdbcTemplate.query("""
                SELECT id, decision, reviewer, reason, policy_version, review_source, reviewed_at
                FROM dynamic_review_record WHERE dynamic_record_id=? ORDER BY id DESC
                """, (rs, row) -> new DynamicReview(rs.getLong("id"), rs.getString("decision"),
                rs.getString("reviewer"), rs.getString("reason"), rs.getString("policy_version"),
                rs.getString("review_source"), dateTime(rs.getTimestamp("reviewed_at"))), recordId);
    }

    @Override
    @Transactional
    public DynamicRecordDetail reviewDynamicRecord(long id, DynamicReviewCommand command, String policyVersion) {
        findDynamicRecord(id);
        if (command.factReviews() != null) {
            for (FactReviewCommand fact : command.factReviews()) {
                int updated = jdbcTemplate.update("""
                        UPDATE dynamic_fact
                        SET review_decision=?, corrected_value=?, review_note=?, reviewed_by=?,
                            reviewed_at=CURRENT_TIMESTAMP
                        WHERE id=? AND dynamic_record_id=?
                        """, fact.decision(), fact.correctedValue(), fact.note(), command.reviewer(), fact.factId(), id);
                if (updated == 0) {
                    throw missing("DYNAMIC_FACT_NOT_FOUND", "未找到待审核事实");
                }
            }
        }
        String status = switch (command.decision()) {
            case "APPROVE" -> "ELIGIBLE";
            case "QUARANTINE" -> "QUARANTINED";
            default -> "REJECTED";
        };
        jdbcTemplate.update("""
                UPDATE dynamic_record
                SET status=?,
                    status_reason=CASE WHEN status_reason LIKE 'TARGET_ROLE:%'
                        THEN CONCAT(status_reason, '，', ?) ELSE ? END,
                    review_source='HUMAN'
                WHERE id=?
                """, status, command.reason(), command.reason(), id);
        jdbcTemplate.update("""
                INSERT INTO dynamic_review_record
                    (dynamic_record_id, decision, reviewer, reason, policy_version, review_source)
                VALUES (?, ?, ?, ?, ?, 'HUMAN')
                """, id, command.decision(), command.reviewer(), command.reason(), policyVersion);
        return findDynamicRecord(id);
    }

    @Override
    @Transactional
    public List<DynamicRecordDetail> bulkReviewDynamicRecords(BulkDynamicReviewCommand command,
                                                                String policyVersion) {
        List<Long> recordIds = command.recordIds().stream().distinct().toList();
        String placeholders = placeholders(recordIds.size());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dynamic_record WHERE id IN (" + placeholders + ")",
                Integer.class, recordIds.toArray());
        if (count == null || count != recordIds.size()) {
            throw missing("DYNAMIC_RECORD_NOT_FOUND", "部分动态记录不存在");
        }
        String status = switch (command.decision()) {
            case "APPROVE" -> "ELIGIBLE";
            case "QUARANTINE" -> "QUARANTINED";
            default -> "REJECTED";
        };
        List<Object> updateArguments = new ArrayList<>();
        updateArguments.add(status);
        updateArguments.add(command.reason());
        updateArguments.add(command.reason());
        updateArguments.addAll(recordIds);
        jdbcTemplate.update("UPDATE dynamic_record SET status=?, status_reason=CASE "
                + "WHEN status_reason LIKE 'TARGET_ROLE:%' THEN CONCAT(status_reason, '，', ?) ELSE ? END, "
                + "review_source='HUMAN' WHERE id IN (" + placeholders + ")", updateArguments.toArray());
        jdbcTemplate.batchUpdate("""
                        INSERT INTO dynamic_review_record
                            (dynamic_record_id, decision, reviewer, reason, policy_version, review_source)
                        VALUES (?, ?, ?, ?, ?, 'HUMAN')
                        """, recordIds.stream().map(id -> new Object[]{id, command.decision(), command.reviewer(),
                        command.reason(), policyVersion}).toList());
        return recordIds.stream().map(this::findDynamicRecord).toList();
    }

    @Override
    public List<CandidateSkillSummary> findCandidateSkills() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT df.id fact_id, df.canonical_value, df.suggested_category, df.review_decision,
                       df.corrected_value, df.requirement_type, df.evidence_quote, df.confidence,
                       df.offset_verified, dr.id record_id, dr.title, dr.company_name,
                       csr.candidate_name registry_candidate, alias_skill.skill_name known_alias_target
                FROM dynamic_fact df
                JOIN dynamic_record dr ON dr.id=df.dynamic_record_id
                LEFT JOIN candidate_skill_registry csr ON csr.candidate_name=df.canonical_value
                LEFT JOIN skill_alias sa ON sa.alias_name=df.canonical_value AND sa.status='APPROVED'
                LEFT JOIN skill alias_skill ON alias_skill.id=sa.skill_id
                WHERE df.fact_type='SKILL' AND df.ontology_status='CANDIDATE'
                  AND dr.data_mode='REAL' AND dr.status<>'ARCHIVED'
                ORDER BY df.canonical_value, df.confidence DESC, df.id
                """);
        return rows.stream().collect(Collectors.groupingBy(row -> string(row, "canonical_value"),
                        LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> candidateSummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CandidateSkillSummary::skill)).toList();
    }

    private CandidateSkillSummary candidateSummary(String skill, List<Map<String, Object>> rows) {
        List<Map<String, Object>> uniqueFacts = rows.stream().collect(Collectors.toMap(
                row -> longValue(row, "fact_id"), row -> row, (left, right) -> left, LinkedHashMap::new))
                .values().stream().toList();
        Set<String> companies = uniqueFacts.stream().map(row -> string(row, "company_name"))
                .filter(value -> !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> decisions = uniqueFacts.stream().map(row -> string(row, "review_decision"))
                .distinct().toList();
        String decision = decisions.size() == 1 ? decisions.get(0) : "MIXED";
        String corrected = uniqueFacts.stream().map(row -> nullableString(row, "corrected_value"))
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        List<CandidateSkillEvidence> evidence = uniqueFacts.stream().collect(Collectors.toMap(
                        row -> string(row, "company_name"), row -> row,
                        (left, right) -> doubleValue(left, "confidence") >= doubleValue(right, "confidence") ? left : right,
                        LinkedHashMap::new))
                .values().stream().limit(3).map(row -> new CandidateSkillEvidence(
                        longValue(row, "record_id"), string(row, "title"), string(row, "company_name"),
                        string(row, "evidence_quote"), nullableString(row, "requirement_type"),
                        doubleValue(row, "confidence"), booleanValue(row, "offset_verified"))).toList();
        String knownAliasTarget = uniqueFacts.stream().map(row -> nullableString(row, "known_alias_target"))
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        var advice = CandidateSkillAdvisor.recommend(skill, companies.size(), decision, corrected,
                knownAliasTarget, evidence);
        return new CandidateSkillSummary(skill, string(uniqueFacts.get(0), "suggested_category"),
                uniqueFacts.size(), companies.size(), decision, corrected,
                uniqueFacts.stream().anyMatch(row -> row.get("registry_candidate") != null) ? "REUSED" : "NEW",
                uniqueFacts.stream().map(row -> nullableString(row, "requirement_type"))
                        .filter(value -> value != null && !value.isBlank()).distinct().toList(),
                advice.decision(), advice.target(), advice.reasons(), evidence);
    }

    @Override
    @Transactional
    public List<CandidateSkillSummary> reviewCandidateSkill(String skill, CandidateSkillReviewCommand command) {
        int updated = jdbcTemplate.update("""
                UPDATE dynamic_fact
                SET review_decision=?, corrected_value=?, review_note=?, reviewed_by=?, reviewed_at=CURRENT_TIMESTAMP
                WHERE fact_type='SKILL' AND ontology_status='CANDIDATE' AND canonical_value=?
                """, command.decision(), command.correctedValue(), command.note(), command.reviewer(), skill);
        if (updated == 0) {
            throw missing("CANDIDATE_SKILL_NOT_FOUND", "未找到候选技能");
        }
        saveCandidateRegistry(skill, command);
        return findCandidateSkills();
    }

    @Override
    @Transactional
    public List<CandidateSkillSummary> bulkReviewCandidateSkills(BulkCandidateSkillReviewCommand command) {
        List<String> skills = command.skills().stream().distinct().toList();
        String placeholders = placeholders(skills.size());
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(DISTINCT canonical_value) FROM dynamic_fact
                        WHERE fact_type='SKILL' AND ontology_status='CANDIDATE'
                          AND canonical_value IN (""" + placeholders + ")", Integer.class, skills.toArray());
        if (count == null || count != skills.size()) {
            throw missing("CANDIDATE_SKILL_NOT_FOUND", "部分候选技能不存在");
        }
        jdbcTemplate.batchUpdate("""
                        UPDATE dynamic_fact
                        SET review_decision=?, corrected_value=?, review_note=?, reviewed_by=?,
                            reviewed_at=CURRENT_TIMESTAMP
                        WHERE fact_type='SKILL' AND ontology_status='CANDIDATE' AND canonical_value=?
                        """, skills.stream().map(skill -> new Object[]{command.decision(), command.correctedValue(),
                        command.note(), command.reviewer(), skill}).toList());
        CandidateSkillReviewCommand single = new CandidateSkillReviewCommand(command.decision(),
                command.correctedValue(), command.reviewer(), command.note());
        skills.forEach(skill -> saveCandidateRegistry(skill, single));
        return findCandidateSkills();
    }

    @Override
    public List<TriageCandidate> findTriageCandidates(Long importId) {
        String importFilter = importId == null ? "" : " AND dr.crawler_run_id=?";
        List<Object> arguments = importId == null ? List.of() : List.of(importId);
        return jdbcTemplate.query("""
                SELECT dr.id, dr.crawler_run_id, dr.status, dr.quality_score, dr.content_hash,
                       (SELECT COUNT(*) FROM dynamic_fact df
                        WHERE df.dynamic_record_id=dr.id AND df.ontology_status='CANDIDATE'
                          AND df.review_decision='PENDING_REVIEW') unresolved_candidate_count,
                       (SELECT COUNT(*) FROM dynamic_quality_issue qi
                        WHERE qi.dynamic_record_id=dr.id AND qi.blocking=1) blocking_issue_count,
                       (SELECT COUNT(*) FROM dynamic_quality_issue qi
                        WHERE qi.dynamic_record_id=dr.id AND qi.severity IN ('HIGH','MEDIUM')) elevated_issue_count,
                       COALESCE((SELECT MIN(df.confidence) FROM dynamic_fact df
                                 WHERE df.dynamic_record_id=dr.id), 1) minimum_confidence,
                       COALESCE((SELECT rr.decision FROM dynamic_review_record rr
                                 WHERE rr.dynamic_record_id=dr.id ORDER BY rr.id DESC LIMIT 1),
                                'PENDING_REVIEW') latest_decision,
                       COALESCE((SELECT rr.review_source FROM dynamic_review_record rr
                                 WHERE rr.dynamic_record_id=dr.id ORDER BY rr.id DESC LIMIT 1),
                                'NONE') latest_review_source
                FROM dynamic_record dr
                WHERE dr.status<>'PROMOTED'
                """ + importFilter + " ORDER BY dr.crawler_run_id, dr.id", (rs, row) -> new TriageCandidate(
                rs.getLong("id"), rs.getLong("crawler_run_id"), rs.getString("status"),
                rs.getDouble("quality_score"), rs.getString("content_hash"),
                rs.getInt("unresolved_candidate_count"), rs.getInt("blocking_issue_count"),
                rs.getInt("elevated_issue_count"), rs.getDouble("minimum_confidence"),
                rs.getString("latest_decision"), rs.getString("latest_review_source")), arguments.toArray());
    }

    @Override
    @Transactional
    public void saveTriageAssignments(List<TriageAssignment> assignments, String policyVersion) {
        if (assignments.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        UPDATE dynamic_record
                        SET risk_level=?, review_route=?, review_reasons=CAST(? AS JSON),
                            review_source=CASE WHEN ?='NONE' THEN review_source ELSE ? END,
                            triage_policy_version=?, triaged_at=CURRENT_TIMESTAMP
                        WHERE id=?
                        """, assignments.stream().map(assignment -> new Object[]{assignment.riskLevel(),
                        assignment.reviewRoute(), json(assignment.reasons()), assignment.reviewSource(),
                        assignment.reviewSource(), policyVersion, assignment.recordId()}).toList());
        List<TriageAssignment> approvals = assignments.stream().filter(TriageAssignment::createAutoApproval).toList();
        if (!approvals.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                            INSERT INTO dynamic_review_record
                                (dynamic_record_id, decision, reviewer, reason, policy_version, review_source)
                            VALUES (?, 'APPROVE', 'system:auto-triage', '低风险记录通过自动门禁', ?, 'SYSTEM')
                            """, approvals.stream().map(assignment ->
                            new Object[]{assignment.recordId(), policyVersion}).toList());
            jdbcTemplate.batchUpdate("""
                            UPDATE dynamic_record
                            SET status='ELIGIBLE', review_source='SYSTEM'
                            WHERE id=?
                            """, approvals.stream().map(assignment -> new Object[]{assignment.recordId()}).toList());
        }
    }

    @Override
    public ReviewQueueSummary reviewQueueSummary(Long importId) {
        String recordFilter = importId == null ? "" : " WHERE crawler_run_id=?";
        List<Object> arguments = importId == null ? List.of() : List.of(importId);
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) total_records,
                       COALESCE(SUM(review_route='AUTO_APPROVED'), 0) auto_approved_count,
                       COALESCE(SUM(review_route='SAMPLE_REVIEW'), 0) sampled_count,
                       COALESCE(SUM(review_route='EXCEPTION_REVIEW'), 0) exception_count,
                       COALESCE(SUM(review_route='WAITING_SKILL'), 0) waiting_skill_count,
                       COALESCE(SUM(review_route='HUMAN_APPROVED'), 0) human_approved_count,
                       COALESCE(SUM(review_route='BLOCKED'), 0) blocked_count
                FROM dynamic_record
                """ + recordFilter, arguments.toArray());
        String candidateFilter = importId == null ? "" : " AND dr.crawler_run_id=?";
        Long pendingSkills = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT df.canonical_value)
                FROM dynamic_fact df JOIN dynamic_record dr ON dr.id=df.dynamic_record_id
                WHERE df.fact_type='SKILL' AND df.ontology_status='CANDIDATE'
                  AND df.review_decision='PENDING_REVIEW'
                """ + candidateFilter, Long.class, arguments.toArray());
        long sampled = longValue(counts, "sampled_count");
        long exception = longValue(counts, "exception_count");
        long candidates = pendingSkills == null ? 0 : pendingSkills;
        return new ReviewQueueSummary(importId, longValue(counts, "total_records"),
                longValue(counts, "auto_approved_count"), sampled, exception,
                longValue(counts, "waiting_skill_count"), longValue(counts, "human_approved_count"),
                longValue(counts, "blocked_count"), candidates, sampled + exception + candidates);
    }

    @Override
    @Transactional
    public PromotionBatchView createPromotionBatch(PromotionBatchCommand command, String policyVersion) {
        List<Long> recordIds;
        if ("ALL_ELIGIBLE".equals(command.selectionMode())) {
            String importFilter = command.importId() == null ? "" : " AND dr.crawler_run_id=?";
            List<Object> arguments = command.importId() == null ? List.of() : List.of(command.importId());
            recordIds = jdbcTemplate.queryForList("""
                    SELECT dr.id FROM dynamic_record dr
                    WHERE dr.status='ELIGIBLE'
                      AND dr.review_route IN ('AUTO_APPROVED','HUMAN_APPROVED')
                      AND COALESCE((SELECT rr.decision FROM dynamic_review_record rr
                                   WHERE rr.dynamic_record_id=dr.id ORDER BY rr.id DESC LIMIT 1),
                                   'PENDING_REVIEW')='APPROVE'
                    """ + importFilter + " ORDER BY dr.id FOR UPDATE", Long.class, arguments.toArray());
        } else {
            recordIds = command.recordIds().stream().distinct().toList();
        }
        if (recordIds.isEmpty()) {
            throw new BusinessException("PROMOTION_RECORDS_EMPTY", "没有可晋级记录");
        }
        String placeholders = placeholders(recordIds.size());
        List<Object> states = jdbcTemplate.query("""
                SELECT dr.id, dr.status, dr.review_route,
                       COALESCE((SELECT rr.decision FROM dynamic_review_record rr
                                 WHERE rr.dynamic_record_id=dr.id ORDER BY rr.id DESC LIMIT 1), 'PENDING_REVIEW') review_decision,
                       (SELECT COUNT(*) FROM dynamic_fact df WHERE df.dynamic_record_id=dr.id
                         AND df.ontology_status='CANDIDATE' AND df.review_decision='PENDING_REVIEW') pending_facts
                FROM dynamic_record dr WHERE dr.id IN (""" + placeholders + ") FOR UPDATE",
                (rs, row) -> Map.of("id", rs.getLong("id"), "status", rs.getString("status"),
                        "route", rs.getString("review_route"),
                        "review", rs.getString("review_decision"), "pending", rs.getInt("pending_facts")),
                recordIds.toArray());
        if (states.size() != recordIds.size()) {
            throw missing("DYNAMIC_RECORD_NOT_FOUND", "部分动态记录不存在");
        }
        for (Object value : states) {
            Map<?, ?> state = (Map<?, ?>) value;
            if (!"ELIGIBLE".equals(state.get("status")) || !"APPROVE".equals(state.get("review"))) {
                throw new BusinessException("PROMOTION_RECORD_NOT_APPROVED", "只有已通过审核的 ELIGIBLE 记录可以晋级");
            }
            if (((Number) state.get("pending")).intValue() > 0) {
                throw new BusinessException("CANDIDATE_SKILL_REVIEW_PENDING", "候选技能尚未全部完成人工审核");
            }
            if (!Set.of("AUTO_APPROVED", "HUMAN_APPROVED").contains(state.get("route"))) {
                throw new BusinessException("PROMOTION_RECORD_REVIEW_PENDING", "抽检和异常记录完成人工审核后才能晋级");
            }
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String snapshotCode = "real-jd-pilot-" + OffsetDateTime.now().toLocalDate() + "-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO dataset_snapshot
                    (snapshot_code, snapshot_name, status, policy_version, raw_count, effective_count, publishable)
                VALUES (?, '首批真实 JD 当前技能快照', 'DRAFT', ?, ?, 0, 0)
                """, snapshotCode, policyVersion, recordIds.size());
        long snapshotId = requiredLong("SELECT id FROM dataset_snapshot WHERE snapshot_code=?", snapshotCode);
        String batchCode = "promotion-" + OffsetDateTime.now().toLocalDate() + "-" + suffix;
        long batchId = insertAndGetId("""
                INSERT INTO promotion_batch
                    (batch_code, target_snapshot_id, policy_version, status, candidate_count, requested_by)
                VALUES (?, ?, ?, 'DRAFT', ?, ?)
                """, batchCode, snapshotId, policyVersion, recordIds.size(), command.requestedBy());
        recordIds.forEach(recordId -> jdbcTemplate.update("""
                INSERT INTO promotion_item (promotion_batch_id, dynamic_record_id)
                VALUES (?, ?)
                """, batchId, recordId));
        return findPromotionBatch(batchId);
    }

    @Override
    public PromotionBatchView findPromotionBatch(long id) {
        List<PromotionBatchView> batches = jdbcTemplate.query("""
                SELECT pb.*, ds.snapshot_code
                FROM promotion_batch pb
                JOIN dataset_snapshot ds ON ds.id=pb.target_snapshot_id
                WHERE pb.id=?
                """, (rs, row) -> new PromotionBatchView(rs.getLong("id"), rs.getString("batch_code"),
                rs.getString("snapshot_code"), rs.getString("policy_version"), rs.getString("status"),
                rs.getInt("candidate_count"), rs.getInt("promoted_count"), rs.getInt("rejected_count"),
                rs.getString("requested_by"), rs.getString("approved_by"), rs.getString("review_note"),
                dateTime(rs.getTimestamp("created_at")), dateTime(rs.getTimestamp("completed_at")),
                findPromotionItems(id)), id);
        if (batches.isEmpty()) {
            throw missing("PROMOTION_BATCH_NOT_FOUND", "未找到晋级批次");
        }
        return batches.get(0);
    }

    private List<PromotionItemView> findPromotionItems(long batchId) {
        return jdbcTemplate.query("""
                SELECT pi.dynamic_record_id, dr.title, dr.company_name, pi.decision,
                       pi.target_document_id, pi.decision_reason
                FROM promotion_item pi
                JOIN dynamic_record dr ON dr.id=pi.dynamic_record_id
                WHERE pi.promotion_batch_id=? ORDER BY pi.id
                """, (rs, row) -> new PromotionItemView(rs.getLong("dynamic_record_id"),
                rs.getString("title"), rs.getString("company_name"), rs.getString("decision"),
                nullableLong(rs.getObject("target_document_id")), rs.getString("decision_reason")), batchId);
    }

    @Override
    @Transactional
    public PromotionBatchView reviewPromotionBatch(long id, PromotionReviewCommand command) {
        PromotionBatchView batch = findPromotionBatch(id);
        if (batch.requestedBy().equals(command.reviewer())) {
            throw new BusinessException("SEPARATION_OF_DUTIES_REQUIRED", "创建人不能审核自己的晋级批次");
        }
        if (!"DRAFT".equals(batch.status())) {
            throw new BusinessException("PROMOTION_BATCH_STATE_INVALID", "当前批次不能重复审核");
        }
        String status = "APPROVE".equals(command.decision()) ? "APPROVED" : "REJECTED";
        jdbcTemplate.update("""
                UPDATE promotion_batch
                SET status=?, approved_by=?, review_note=?, reviewed_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, status, command.reviewer(), command.note(), id);
        if ("REJECTED".equals(status)) {
            jdbcTemplate.update("""
                    UPDATE promotion_item SET decision='REJECTED', decision_reason=?
                    WHERE promotion_batch_id=?
                    """, command.note(), id);
        }
        return findPromotionBatch(id);
    }

    @Override
    @Transactional
    public PromotionBatchView publishPromotionBatch(long id, PromotionPublishCommand command) {
        Map<String, Object> batch = singleRow("""
                SELECT pb.*, ds.snapshot_code
                FROM promotion_batch pb JOIN dataset_snapshot ds ON ds.id=pb.target_snapshot_id
                WHERE pb.id=? FOR UPDATE
                """, id);
        String status = string(batch, "status");
        if ("COMPLETED".equals(status)) {
            return findPromotionBatch(id);
        }
        if (!"APPROVED".equals(status)) {
            throw new BusinessException("PROMOTION_BATCH_NOT_APPROVED", "晋级批次必须先由另一位审核人通过");
        }
        long snapshotId = longValue(batch, "target_snapshot_id");
        List<Long> recordIds = jdbcTemplate.queryForList("""
                SELECT dynamic_record_id FROM promotion_item
                WHERE promotion_batch_id=? ORDER BY id FOR UPDATE
                """, Long.class, id);
        int promoted = 0;
        for (Long recordId : recordIds) {
            Map<String, Object> record = singleRow("SELECT * FROM dynamic_record WHERE id=? FOR UPDATE", recordId);
            if ("PROMOTED".equals(string(record, "status"))) {
                continue;
            }
            long documentId = insertAndGetId("""
                    INSERT INTO raw_document (
                        source_id, snapshot_id, document_title, company_name, city, level_name,
                        salary_range, raw_text, content_hash, published_at, collected_at,
                        field_completeness, time_weight, is_demo
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1.000, 1.000000, 0)
                    """, longValue(record, "source_id"), snapshotId, string(record, "title"),
                    string(record, "company_name"), string(record, "city"),
                    string(record, "experience_requirement"), string(record, "salary_raw"),
                    string(record, "raw_text"), string(record, "content_hash"),
                    record.get("published_at"), record.get("collected_at"));
            jdbcTemplate.update("""
                    INSERT INTO promoted_document_metadata (
                        document_id, dynamic_record_id, source_record_key, source_url, canonical_url, data_mode,
                        experience_requirement, education_requirement, salary_min_monthly, salary_max_monthly,
                        salary_months, current_snapshot_eligible, trend_eligible
                    ) VALUES (?, ?, ?, ?, ?, 'REAL', ?, ?, ?, ?, ?, 1, ?)
                    """, documentId, recordId, string(record, "source_record_key"), string(record, "source_url"),
                    string(record, "canonical_url"), string(record, "experience_requirement"),
                    string(record, "education_requirement"), record.get("salary_min_monthly"),
                    record.get("salary_max_monthly"), record.get("salary_months"),
                    record.get("published_at") != null);
            publishFacts(recordId, documentId, string(record, "content_hash"), string(record, "parser_version"));
            jdbcTemplate.update("""
                    UPDATE promotion_item SET decision='PROMOTED', target_document_id=?
                    WHERE promotion_batch_id=? AND dynamic_record_id=?
                    """, documentId, id, recordId);
            jdbcTemplate.update("UPDATE dynamic_record SET status='PROMOTED' WHERE id=?", recordId);
            promoted++;
        }
        jdbcTemplate.update("""
                UPDATE dataset_snapshot
                SET status='FROZEN', raw_count=?, effective_count=?, quality_score=(
                    SELECT AVG(dr.quality_score) FROM promotion_item pi
                    JOIN dynamic_record dr ON dr.id=pi.dynamic_record_id
                    WHERE pi.promotion_batch_id=?
                ), publishable=1, frozen_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, recordIds.size(), recordIds.size(), id, snapshotId);
        jdbcTemplate.update("""
                UPDATE promotion_batch
                SET status='COMPLETED', promoted_count=?, completed_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, promoted, id);
        return findPromotionBatch(id);
    }

    private void publishFacts(long recordId, long documentId, String contentHash, String parserVersion) {
        List<Map<String, Object>> facts = jdbcTemplate.queryForList("""
                SELECT * FROM dynamic_fact WHERE dynamic_record_id=? ORDER BY id
                """, recordId);
        for (Map<String, Object> fact : facts) {
            String type = string(fact, "fact_type");
            String review = string(fact, "review_decision");
            if ("SKILL".equals(type) && "CANDIDATE".equals(string(fact, "ontology_status"))
                    && !Set.of("APPROVED_STANDARD", "APPROVED_ALIAS").contains(review)) {
                continue;
            }
            Long normalizedId = null;
            if ("SKILL".equals(type)) {
                String skillName = "APPROVED_ALIAS".equals(review)
                        ? string(fact, "corrected_value") : string(fact, "canonical_value");
                normalizedId = ensureSkill(skillName, string(fact, "suggested_category"));
                if ("APPROVED_ALIAS".equals(review)) {
                    jdbcTemplate.update("""
                            INSERT INTO skill_alias (skill_id, alias_name, match_type, confidence, status)
                            VALUES (?, ?, 'MANUAL', 1, 'APPROVED')
                            ON DUPLICATE KEY UPDATE status='APPROVED', confidence=1
                            """, normalizedId, string(fact, "canonical_value"));
                }
            }
            long factId = insertAndGetId("""
                    INSERT INTO extracted_fact (
                        document_id, fact_type, raw_value, normalized_type, normalized_id, requirement_type,
                        confidence, gate_status, extractor_type, prompt_version, input_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'REVIEWED', 'RULE', ?, ?)
                    """, documentId, type, string(fact, "raw_value"), "SKILL".equals(type) ? "SKILL" : "TEXT",
                    normalizedId, nullableString(fact, "requirement_type"), doubleValue(fact, "confidence"),
                    parserVersion, contentHash);
            jdbcTemplate.update("""
                    INSERT INTO fact_evidence (
                        fact_id, document_id, start_offset, end_offset, evidence_quote,
                        evidence_direction, anchor_valid
                    ) VALUES (?, ?, ?, ?, ?, ?, 1)
                    """, factId, documentId, intValue(fact, "start_offset"), intValue(fact, "end_offset"),
                    string(fact, "evidence_quote"),
                    "NEGATED".equals(nullableString(fact, "requirement_type")) ? "OPPOSE" : "SUPPORT");
        }
    }

    private long ensureSkill(String skillName, String categoryName) {
        List<Long> existing = jdbcTemplate.queryForList("SELECT id FROM skill WHERE skill_name=?", Long.class, skillName);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String category = categoryName == null || categoryName.isBlank() ? "其他工程能力" : categoryName;
        List<Long> capabilities = jdbcTemplate.queryForList(
                "SELECT id FROM capability WHERE capability_name=? LIMIT 1", Long.class, category);
        long capabilityId;
        if (capabilities.isEmpty()) {
            String code = "CAP_" + shortHash(category);
            jdbcTemplate.update("""
                    INSERT INTO capability (capability_code, capability_name, sort_order)
                    VALUES (?, ?, 999)
                    ON DUPLICATE KEY UPDATE capability_name=VALUES(capability_name)
                    """, code, category);
            capabilityId = requiredLong("SELECT id FROM capability WHERE capability_code=?", code);
        } else {
            capabilityId = capabilities.get(0);
        }
        String skillCode = "SKILL_" + shortHash(skillName);
        jdbcTemplate.update("""
                INSERT INTO skill (capability_id, skill_code, skill_name, status)
                VALUES (?, ?, ?, 'PUBLISHED')
                ON DUPLICATE KEY UPDATE status='PUBLISHED'
                """, capabilityId, skillCode, skillName);
        return requiredLong("SELECT id FROM skill WHERE skill_name=?", skillName);
    }

    @Override
    public RealSnapshotView findCurrentRealSnapshot() {
        List<Map<String, Object>> snapshots = jdbcTemplate.queryForList("""
                SELECT ds.id, ds.snapshot_code
                FROM dataset_snapshot ds
                JOIN promotion_batch pb ON pb.target_snapshot_id=ds.id
                WHERE pb.status='COMPLETED'
                ORDER BY pb.completed_at DESC, pb.id DESC LIMIT 1
                """);
        if (snapshots.isEmpty()) {
            return new RealSnapshotView("", "REAL", "CURRENT_SNAPSHOT_ONLY", 0, 0, false, List.of());
        }
        long snapshotId = longValue(snapshots.get(0), "id");
        String snapshotCode = string(snapshots.get(0), "snapshot_code");
        List<Map<String, Object>> documents = jdbcTemplate.queryForList("""
                SELECT rd.id, rd.document_title, rd.company_name, rd.published_at, pdm.source_url,
                       dr.status_reason
                FROM raw_document rd
                JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
                JOIN dynamic_record dr ON dr.id=pdm.dynamic_record_id
                WHERE rd.snapshot_id=? AND pdm.data_mode='REAL' AND pdm.current_snapshot_eligible=1
                """, snapshotId);
        List<Map<String, Object>> facts = jdbcTemplate.queryForList("""
                SELECT ef.id, ef.document_id, ef.requirement_type, s.skill_name,
                       rd.document_title, rd.company_name, fe.evidence_quote,
                       fe.start_offset, fe.end_offset, pdm.source_url
                FROM extracted_fact ef
                JOIN skill s ON s.id=ef.normalized_id
                JOIN fact_evidence fe ON fe.fact_id=ef.id AND fe.anchor_valid=1
                JOIN raw_document rd ON rd.id=ef.document_id
                JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
                WHERE rd.snapshot_id=? AND ef.fact_type='SKILL' AND ef.gate_status='REVIEWED'
                """, snapshotId);
        List<Map<String, Object>> responsibilities = jdbcTemplate.queryForList("""
                SELECT ef.document_id, ef.raw_value responsibility,
                       rd.document_title, rd.company_name, fe.evidence_quote,
                       fe.start_offset, fe.end_offset, pdm.source_url
                FROM extracted_fact ef
                JOIN fact_evidence fe ON fe.fact_id=ef.id AND fe.anchor_valid=1
                JOIN raw_document rd ON rd.id=ef.document_id
                JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
                WHERE rd.snapshot_id=? AND ef.fact_type='RESPONSIBILITY' AND ef.gate_status='REVIEWED'
                """, snapshotId);
        Map<String, List<Map<String, Object>>> roleDocuments = documents.stream().collect(Collectors.groupingBy(
                this::roleCode, LinkedHashMap::new, Collectors.toList()));
        List<RealRoleSnapshot> roles = roleDocuments.entrySet().stream()
                .map(entry -> realRole(entry.getKey(), entry.getValue(), facts, responsibilities)).toList();
        int companies = (int) documents.stream().map(row -> string(row, "company_name")).distinct().count();
        boolean publishedCoverage = documents.stream().allMatch(row -> row.get("published_at") != null);
        return new RealSnapshotView(snapshotCode, "REAL", "CURRENT_SNAPSHOT_ONLY",
                documents.size(), companies, publishedCoverage, roles);
    }

    private RealRoleSnapshot realRole(String roleCode, List<Map<String, Object>> documents,
                                      List<Map<String, Object>> allFacts,
                                      List<Map<String, Object>> allResponsibilities) {
        Set<Long> documentIds = documents.stream().map(row -> longValue(row, "id")).collect(Collectors.toSet());
        List<Map<String, Object>> facts = allFacts.stream()
                .filter(row -> documentIds.contains(longValue(row, "document_id"))).toList();
        List<Map<String, Object>> responsibilities = allResponsibilities.stream()
                .filter(row -> documentIds.contains(longValue(row, "document_id"))).toList();
        int denominator = (int) documents.stream().map(row -> string(row, "company_name")).distinct().count();
        return new RealRoleSnapshot(roleCode,
                ROLE_NAMES.getOrDefault(roleCode,
                        "JAVA".equals(roleCode) ? "Java 开发工程师" : "AI Agent 应用开发工程师"),
                documents.size(), denominator, "试验性快照",
                responsibilityMetrics(responsibilities),
                skillMetrics(facts, "REQUIRED", denominator),
                skillMetrics(facts, "BONUS", denominator),
                skillMetrics(facts, "MENTIONED", denominator),
                facts.stream().filter(row -> "NEGATED".equals(string(row, "requirement_type")))
                        .map(this::realEvidence).toList());
    }

    private List<RealResponsibilityMetric> responsibilityMetrics(List<Map<String, Object>> responsibilities) {
        return responsibilities.stream().collect(Collectors.groupingBy(row -> string(row, "responsibility"),
                        LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> {
                    List<Map<String, Object>> grouped = entry.getValue();
                    int documentCount = (int) grouped.stream()
                            .map(row -> longValue(row, "document_id")).distinct().count();
                    int companyCount = (int) grouped.stream()
                            .map(row -> string(row, "company_name")).distinct().count();
                    return new RealResponsibilityMetric(entry.getKey(), documentCount, companyCount,
                            grouped.stream().limit(5).map(this::realEvidence).toList());
                }).sorted(Comparator.comparingInt(RealResponsibilityMetric::documentCount).reversed()
                        .thenComparing(RealResponsibilityMetric::responsibility)).limit(12).toList();
    }

    private List<RealSkillMetric> skillMetrics(List<Map<String, Object>> facts, String requirementType,
                                               int denominator) {
        return facts.stream().filter(row -> requirementType.equals(string(row, "requirement_type")))
                .collect(Collectors.groupingBy(row -> string(row, "skill_name"),
                        LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> {
                    List<Map<String, Object>> grouped = entry.getValue();
                    int documentCount = (int) grouped.stream().map(row -> longValue(row, "document_id")).distinct().count();
                    int companyCount = (int) grouped.stream().map(row -> string(row, "company_name")).distinct().count();
                    return new RealSkillMetric(entry.getKey(), companyCount / (double) Math.max(1, denominator),
                            documentCount, companyCount, grouped.stream().limit(8).map(this::realEvidence).toList());
                }).sorted(Comparator.comparingDouble(RealSkillMetric::rate).reversed()
                        .thenComparing(RealSkillMetric::skill)).toList();
    }

    private RealEvidence realEvidence(Map<String, Object> row) {
        return new RealEvidence(longValue(row, "document_id"), string(row, "document_title"),
                string(row, "company_name"), string(row, "evidence_quote"),
                intValue(row, "start_offset"), intValue(row, "end_offset"), string(row, "source_url"));
    }

    private String roleCode(Map<String, Object> row) {
        String reason = string(row, "status_reason");
        int marker = reason.indexOf(TARGET_ROLE_PREFIX);
        if (marker >= 0) {
            int start = marker + TARGET_ROLE_PREFIX.length();
            int end = reason.indexOf('，', start);
            String code = (end < 0 ? reason.substring(start) : reason.substring(start, end)).trim();
            if (ROLE_NAMES.containsKey(code)) {
                return code;
            }
        }
        return legacyRoleCode(string(row, "document_title"));
    }

    private String legacyRoleCode(String title) {
        return title.toLowerCase(Locale.ROOT).contains("java") ? "JAVA" : "AI_AGENT";
    }

    private Map<String, RegistryDecision> loadCandidateRegistry(List<ValidatedDocument> documents) {
        List<String> candidates = documents.stream()
                .flatMap(document -> document.document().facts().skills().stream())
                .filter(fact -> "CANDIDATE".equals(fact.ontologyStatus()))
                .map(StructuredJdModels.StructuredSkillFact::canonicalSkill)
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }
        return jdbcTemplate.query("""
                SELECT candidate_name, decision, corrected_value, reviewer, note, reviewed_at
                FROM candidate_skill_registry
                WHERE candidate_name IN (""" + placeholders(candidates.size()) + ")",
                rs -> {
                    Map<String, RegistryDecision> decisions = new HashMap<>();
                    while (rs.next()) {
                        decisions.put(rs.getString("candidate_name"), new RegistryDecision(
                                rs.getString("decision"), rs.getString("corrected_value"),
                                rs.getString("reviewer"), "复用候选技能注册表决定",
                                rs.getTimestamp("reviewed_at")));
                    }
                    return decisions;
                }, candidates.toArray());
    }

    private RegistryDecision candidateDecision(StructuredJdModels.StructuredSkillFact fact,
                                                Map<String, RegistryDecision> registry) {
        if (!"CANDIDATE".equals(fact.ontologyStatus())) {
            return new RegistryDecision("APPROVED_EXISTING", null, null, null, null);
        }
        return registry.getOrDefault(fact.canonicalSkill(),
                new RegistryDecision("PENDING_REVIEW", null, null, null, null));
    }

    private void saveCandidateRegistry(String skill, CandidateSkillReviewCommand command) {
        jdbcTemplate.update("""
                INSERT INTO candidate_skill_registry
                    (candidate_name, decision, corrected_value, reviewer, note, reviewed_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE decision=VALUES(decision), corrected_value=VALUES(corrected_value),
                    reviewer=VALUES(reviewer), note=VALUES(note), reviewed_at=CURRENT_TIMESTAMP
                """, skill, command.decision(), command.correctedValue(), command.reviewer(), command.note());
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private long insertAndGetId(String sql, Object... arguments) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回新增记录 ID");
        }
        return key.longValue();
    }

    private long requiredLong(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        if (value == null) {
            throw new IllegalStateException("数据库记录不存在");
        }
        return value;
    }

    private Map<String, Object> singleRow(String sql, Object... arguments) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, arguments);
        if (rows.isEmpty()) {
            throw missing("PROMOTION_BATCH_NOT_FOUND", "未找到晋级批次");
        }
        return rows.get(0);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("STRUCTURED_JD_JSON_FAILED", "结构化记录无法序列化");
        }
    }

    private List<String> jsonList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new BusinessException("STRUCTURED_JD_JSON_FAILED", "审核分流原因无法解析");
        }
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String runCode(String batchKey) {
        return "jd-" + batchKey;
    }

    private Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private OffsetDateTime dateTime(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(OFFSET);
    }

    private List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split("\\|\\|"));
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private String nullableString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    private long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private int intValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private double doubleValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString());
    }

    private boolean booleanValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean bool ? bool
                : value instanceof Number number ? number.intValue() != 0
                : Boolean.parseBoolean(String.valueOf(value));
    }

    private BusinessException missing(String code, String message) {
        return new BusinessException(code, message);
    }

    private record RegistryDecision(String decision, String correctedValue, String reviewer, String note,
                                    Timestamp reviewedAt) {
    }
}
