package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.evolution.RoleEvolutionModels.ChangeDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.DraftUpdate;
import com.zhigang.platform.evolution.RoleEvolutionModels.EvidenceDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.WindowDraft;
import com.zhigang.platform.evolution.RoleEvolutionRepository;
import com.zhigang.platform.evolution.RoleEvolutionService;
import com.zhigang.platform.source.StructuredJdModels.BulkDynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.Facts;
import com.zhigang.platform.source.StructuredJdModels.ProcessingPolicy;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionPublishCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.Salary;
import com.zhigang.platform.source.StructuredJdModels.Source;
import com.zhigang.platform.source.StructuredJdModels.SourceWorkbook;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdBatch;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdDocument;
import com.zhigang.platform.source.StructuredJdModels.StructuredSkillFact;
import com.zhigang.platform.source.StructuredJdService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("mysql")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_E2E", matches = "true")
class MySqlRoleEvolutionE2ETest {
    private static final String E2E_DATABASE = "zhigang_map_e2e";
    private static final String SOURCE_ID = "iguopin-current-cn";
    private static final Map<String, List<String>> SKILL_ALIASES = skills();

    @Autowired
    private StructuredJdService structuredJdService;

    @Autowired
    private RoleEvolutionService evolutionService;

    @Autowired
    private RoleEvolutionRepository evolutionRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        String url = requiredEnvironment("MYSQL_E2E_URL");
        String database = url.replaceFirst("[?].*$", "").replaceFirst(".*/", "");
        if (!E2E_DATABASE.equals(database)) {
            throw new IllegalStateException("MySQL E2E 只允许连接 " + E2E_DATABASE + "，当前为 " + database);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnvironment("MYSQL_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_PASSWORD", ""));
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("zhigang.security.dev-permit-all", () -> false);
    }

    @Test
    void calculatesRealJavaWindowsAndKeepsPublicationAtomic() throws Exception {
        resetE2eSchema();
        var baselineImport = structuredJdService.importBatch(realJavaCorpus("baseline", true));
        assertThat(baselineImport.acceptedCount()).isEqualTo(18);
        approveAndPublish(baselineImport.id());
        var currentImport = structuredJdService.importBatch(realJavaCorpus("current", false));
        assertThat(currentImport.acceptedCount()).isEqualTo(24);
        approveAndPublish(currentImport.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM promotion_batch WHERE status='COMPLETED'", Integer.class)).isEqualTo(2);

        long javaRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM job_role WHERE role_code='JAVA_ENGINEER'", Long.class);
        var realRun = evolutionService.start(javaRoleId);

        assertThat(realRun.windows()).hasSize(2);
        var baseline = realRun.windows().stream().filter(window -> "BASELINE".equals(window.type())).findFirst().orElseThrow();
        var current = realRun.windows().stream().filter(window -> "CURRENT".equals(window.type())).findFirst().orElseThrow();
        assertThat(baseline.startDate()).isEqualTo("2026-03-01");
        assertThat(baseline.endDate()).isEqualTo("2026-05-31");
        assertThat(baseline.documentCount()).isEqualTo(18);
        assertThat(baseline.companyCount()).isEqualTo(13);
        assertThat(current.startDate()).isEqualTo("2026-06-01");
        assertThat(current.endDate()).isEqualTo("2026-08-31");
        assertThat(current.documentCount()).isEqualTo(24);
        assertThat(current.companyCount()).isEqualTo(18);
        assertThat(realRun.windows()).allSatisfy(window -> {
            assertThat(window.sourceCount()).isEqualTo(1);
            assertThat(window.sourceNames()).containsExactly("国聘公开招聘职位");
        });
        assertThat(realRun.conclusionLevel()).isEqualTo("SIGNAL_ONLY");
        assertThat(realRun.changes()).isNotEmpty()
                .anyMatch(change -> "OBSERVATION_SIGNAL".equals(change.confidenceLevel()));
        assertThat(realRun.changes()).noneMatch(change -> change.publishable());
        assertThat(realRun.changes()).flatExtracting(change -> change.evidence()).isNotEmpty().allSatisfy(evidence -> {
            assertThat(evidence.anchorValid()).isTrue();
            String rawText = jdbcTemplate.queryForObject(
                    "SELECT raw_text FROM raw_document WHERE id=?", String.class, evidence.documentId());
            assertThat(rawText.substring(evidence.startOffset(), evidence.endOffset())).isEqualTo(evidence.quote());
        });
        assertThatThrownBy(() -> evolutionService.publish(realRun.id(),
                new com.zhigang.platform.evolution.RoleEvolutionModels.PublishCommand(
                        realRun.version(), "evolution-real-signal", "e2e-publisher")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("EVOLUTION_NO_PUBLISHABLE_CHANGES"));

        var revised = evolutionService.updateDraft(realRun.id(), new DraftUpdate(
                realRun.version(), List.of(), "保留为观察信号", "真实数据未通过正式变化区间门禁", "e2e-editor"));
        assertThat(revised.version()).isEqualTo(realRun.version() + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM role_evolution_revision WHERE run_id=?", Integer.class, realRun.id())).isEqualTo(1);
        assertThatThrownBy(() -> evolutionService.updateDraft(realRun.id(), new DraftUpdate(
                realRun.version(), List.of(), "过期修改", "验证乐观锁", "stale-editor")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("OPTIMISTIC_LOCK_FAILED"));

        assertFailedPublicationRollsBack(javaRoleId, baseline.startDate(), current.endDate());
        assertTamperedEvidenceIsRejected(javaRoleId, baseline.startDate(), current.endDate());
        assertSuccessfulPublicationCopiesGraph(javaRoleId, baseline.startDate(), current.endDate());
    }

    private void approveAndPublish(long importId) {
        var records = structuredJdService.records(importId, null, null, null, null, false, 1, 100);
        List<Long> manualRecords = records.items().stream()
                .filter(item -> List.of("SAMPLE_REVIEW", "EXCEPTION_REVIEW").contains(item.reviewRoute()))
                .map(item -> item.id()).toList();
        if (!manualRecords.isEmpty()) {
            structuredJdService.bulkReviewRecords(new BulkDynamicReviewCommand(
                    manualRecords, "APPROVE", "e2e-reviewer", "P0-4 真实双窗口语料复核"));
        }
        var promotion = structuredJdService.createPromotionBatch(new PromotionBatchCommand(
                List.of(), importId, "ALL_ELIGIBLE", "e2e-requester"));
        promotion = structuredJdService.reviewPromotionBatch(promotion.id(),
                new PromotionReviewCommand("APPROVE", "e2e-reviewer", "P0-4 E2E 独立发布复核"));
        structuredJdService.publishPromotionBatch(promotion.id(), new PromotionPublishCommand("e2e-publisher"));
    }

    private void assertFailedPublicationRollsBack(long roleId, LocalDate baselineStart, LocalDate currentEnd) {
        long documentId = realJavaEvidenceDocument();
        var run = evolutionRepository.save(controlledRun(roleId, "rollback", null, documentId,
                baselineStart, currentEnd));
        int releasesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM graph_release", Integer.class);
        assertThatThrownBy(() -> evolutionRepository.publish(
                run.id(), run.version(), "evolution-e2e-rollback", "e2e-publisher"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("EVOLUTION_SKILL_NOT_NORMALIZED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM graph_release", Integer.class))
                .isEqualTo(releasesBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_release_id FROM role_evolution_run WHERE id=?", Long.class, run.id())).isNull();
    }

    private void assertSuccessfulPublicationCopiesGraph(long roleId, LocalDate baselineStart, LocalDate currentEnd) {
        long documentId = realJavaEvidenceDocument();
        long javaSkillId = jdbcTemplate.queryForObject("SELECT id FROM skill WHERE skill_name='Java'", Long.class);
        long previousRelease = jdbcTemplate.queryForObject(
                "SELECT id FROM graph_release WHERE status='PUBLISHED'", Long.class);
        int previousRelations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM release_job_skill WHERE release_id=?", Integer.class, previousRelease);
        var run = evolutionRepository.save(controlledRun(roleId, "publish", javaSkillId, documentId,
                baselineStart, currentEnd));
        var published = evolutionRepository.publish(
                run.id(), run.version(), "evolution-e2e-published", "e2e-publisher");

        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.publishedReleaseVersion()).isEqualTo("evolution-e2e-published");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM graph_release WHERE id=?", String.class, previousRelease)).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM published_role_evolution WHERE run_id=?", Integer.class, run.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM release_job_skill WHERE release_id=?", Integer.class,
                published.publishedReleaseId())).isGreaterThanOrEqualTo(previousRelations);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT trend_type FROM release_job_skill
                WHERE release_id=? AND job_role_id=? AND skill_id=? ORDER BY id LIMIT 1
                """, String.class, published.publishedReleaseId(), roleId, javaSkillId)).isEqualTo("INCREASED");
    }

    private void assertTamperedEvidenceIsRejected(long roleId, LocalDate baselineStart, LocalDate currentEnd) {
        long documentId = realJavaEvidenceDocument();
        long javaSkillId = jdbcTemplate.queryForObject("SELECT id FROM skill WHERE skill_name='Java'", Long.class);
        var run = evolutionRepository.save(controlledRun(roleId, "tampered-evidence", javaSkillId, documentId,
                baselineStart, currentEnd));
        jdbcTemplate.update("""
                UPDATE role_evolution_evidence SET evidence_quote='被篡改的引用' WHERE change_id=(
                  SELECT id FROM role_evolution_skill_change WHERE run_id=? LIMIT 1)
                """, run.id());
        int releasesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM graph_release", Integer.class);

        assertThatThrownBy(() -> evolutionRepository.publish(
                run.id(), run.version(), "evolution-e2e-tampered", "e2e-publisher"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("EVOLUTION_EVIDENCE_GATE_FAILED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM graph_release", Integer.class))
                .isEqualTo(releasesBefore);
    }

    private RunDraft controlledRun(long roleId, String suffix, Long skillId, long documentId,
                                   LocalDate baselineStart, LocalDate currentEnd) {
        String quote = "Java";
        String rawText = jdbcTemplate.queryForObject(
                "SELECT raw_text FROM raw_document WHERE id=?", String.class, documentId);
        int start = rawText.indexOf(quote);
        String title = jdbcTemplate.queryForObject(
                "SELECT document_title FROM raw_document WHERE id=?", String.class, documentId);
        String company = jdbcTemplate.queryForObject(
                "SELECT company_name FROM raw_document WHERE id=?", String.class, documentId);
        LocalDate publishedAt = jdbcTemplate.queryForObject(
                "SELECT DATE(published_at) FROM raw_document WHERE id=?", LocalDate.class, documentId);
        EvidenceDraft evidence = new EvidenceDraft("CURRENT", documentId, title, company,
                "国聘公开招聘职位", "https://www.iguopin.com/job", publishedAt,
                quote, start, start + quote.length(), true, "SUPPORT");
        ChangeDraft change = new ChangeDraft(skillId, "Java", "INCREASED", "FORMAL_CHANGE",
                8, 8, .40, .20, .60, 12, 12, .80, .60, .92,
                .40, .10, .68, "UP", 0, true, true, List.of(evidence));
        WindowDraft baseline = new WindowDraft("BASELINE", "受控事务夹具基线", baselineStart,
                baselineStart.plusMonths(2).withDayOfMonth(1).minusDays(1), 8, 8, 1,
                List.of("受控 E2E 来源"), "全国", "全职级", "a".repeat(64));
        WindowDraft current = new WindowDraft("CURRENT", "受控事务夹具当前", currentEnd.minusMonths(2).withDayOfMonth(1),
                currentEnd, 12, 12, 1, List.of("受控 E2E 来源"), "全国", "全职级", "b".repeat(64));
        return new RunDraft("evolution-e2e-" + suffix, roleId, "JAVA_ENGINEER", "Java 开发工程师",
                "role-evolution-v1.0", "c".repeat(64), "d".repeat(64), "DRAFT_READY", "FORMAL_CHANGES",
                "受控事务夹具，仅验证发布原子性，不作为市场趋势", "Wilson 95% + Newcombe",
                "{\"fixture\":true}", List.of(baseline, current), List.of(change));
    }

    private long realJavaEvidenceDocument() {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM raw_document
                WHERE is_demo=0 AND deleted_at IS NULL AND raw_text LIKE '%Java%'
                ORDER BY id LIMIT 1
                """, Long.class);
    }

    private StructuredJdBatch realJavaCorpus(String window, boolean baseline) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<JsonNode> selected = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("..", "test-data", "jd-corpus", "qualified.jsonl"),
                StandardCharsets.UTF_8)) {
            JsonNode record = objectMapper.readTree(line);
            String publishedAt = record.path("publishedAt").asText();
            if ("JAVA_ENGINEER".equals(record.path("primaryTargetRoleCode").asText())
                    && SOURCE_ID.equals(record.path("sourceId").asText())
                    && (baseline ? publishedAt.matches("2026-(03|04|05)-.*")
                    : publishedAt.matches("2026-(06|07|08)-.*"))) {
                selected.add(record);
            }
        }
        assertThat(selected).as("冻结语料应包含对应窗口的国聘 Java JD").hasSize(baseline ? 18 : 24);
        List<StructuredJdDocument> documents = selected.stream().map(this::toDocument).toList();
        int characters = documents.stream().mapToInt(StructuredJdDocument::rawTextLength).sum();
        String corpusHash = sha256(selected.stream().map(JsonNode::toString).reduce("", String::concat));
        return new StructuredJdBatch("1.0.0", OffsetDateTime.parse("2026-08-01T16:00:00+08:00"),
                "mysql-e2e-java-evolution-" + window + "-v1", new SourceWorkbook(
                "qualified-java-evolution-" + window + ".jsonl", corpusHash, documents.size(), characters),
                new ProcessingPolicy("cn-computer-jd-v1.0", "PUBLIC_CHINA_JD_RULE_EXTRACTION",
                        "SOURCE_DISCLOSED", "PUBLIC_WEB_REVIEWED_FOR_LOCAL_COMPETITION_USE",
                        "CURRENT_CHINA_COMPUTER_JD_ONLY"), documents);
    }

    private StructuredJdDocument toDocument(JsonNode record) {
        String rawText = record.path("rawText").asText();
        OffsetDateTime observedAt = OffsetDateTime.parse(record.path("collectedAt").asText());
        String sourceUrl = record.path("sourceUrl").asText();
        Source source = new Source(SOURCE_ID, record.path("sourceName").asText(),
                record.path("sourceTier").asText("TIER_1"), "PUBLIC_EMPLOYMENT",
                record.path("sourceType").asText(), "PUBLIC_WEB_REVIEWED",
                record.path("licenseStatus").asText() + "；" + record.path("licenseNote").asText(),
                record.path("originalPostingUrl").asText(sourceUrl), sourceUrl);
        return new StructuredJdDocument(record.path("sourceRecordKey").asText(), record.path("jobTitle").asText(),
                record.path("company").asText(), record.path("city").asText(),
                new Salary("", null, null, java.math.BigDecimal.valueOf(12), "CNY"),
                record.path("jobExperience").asText(record.path("jobLevel").asText()), "",
                OffsetDateTime.parse(record.path("publishedAt").asText()),
                LocalDate.ofInstant(observedAt.toInstant(), ZoneOffset.ofHours(8)), "CURRENT", observedAt,
                source, rawText, rawText.length(), sha256(rawText), "REAL", "ELIGIBLE",
                List.of("TARGET_ROLE:JAVA_ENGINEER"), new Facts(skillFacts(rawText), List.of()));
    }

    private List<StructuredSkillFact> skillFacts(String rawText) {
        String lower = rawText.toLowerCase();
        List<StructuredSkillFact> facts = new ArrayList<>();
        SKILL_ALIASES.forEach((canonical, aliases) -> aliases.stream()
                .map(alias -> Map.entry(alias, lower.indexOf(alias.toLowerCase())))
                .filter(entry -> entry.getValue() >= 0)
                .findFirst()
                .ifPresent(entry -> {
                    int start = entry.getValue();
                    String raw = rawText.substring(start, start + entry.getKey().length());
                    facts.add(new StructuredSkillFact("STANDARD", canonical, "已发布技能本体", raw,
                            "MENTIONED", start, start + raw.length(), raw, .99, true));
                }));
        return List.copyOf(facts);
    }

    private void resetE2eSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, script("DROP DATABASE IF EXISTS " + E2E_DATABASE
                    + "; CREATE DATABASE " + E2E_DATABASE
                    + " DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci; USE "
                    + E2E_DATABASE + ";"));
            for (String name : List.of("01_schema.sql", "02_seed_demo.sql", "03_real_data_pipeline.sql",
                    "04_review_triage.sql", "05_emerging_discovery.sql", "06_emerging_query_discovery.sql",
                    "07_role_evolution.sql", "08_p0_5_release_governance.sql")) {
                executeProjectScript(connection, name);
            }
        }
    }

    private void executeProjectScript(Connection connection, String name) throws Exception {
        String sql = Files.readString(Path.of("..", "database", name), StandardCharsets.UTF_8)
                .replace("zhigang_map", E2E_DATABASE);
        ScriptUtils.executeSqlScript(connection, script(sql));
    }

    private org.springframework.core.io.InputStreamResource script(String sql) {
        return new org.springframework.core.io.InputStreamResource(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量 " + name);
        return value;
    }

    private static Map<String, List<String>> skills() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("Java", List.of("Java"));
        values.put("Python", List.of("Python"));
        values.put("Spring Boot", List.of("Spring Boot", "SpringBoot", "spring-boot"));
        values.put("Spring Cloud", List.of("Spring Cloud"));
        values.put("MySQL", List.of("MySQL"));
        values.put("Redis", List.of("Redis"));
        values.put("Linux", List.of("Linux"));
        values.put("Docker", List.of("Docker"));
        values.put("Kubernetes", List.of("Kubernetes", "K8s", "容器编排"));
        values.put("大语言模型", List.of("大语言模型", "大模型", "LLM", "大型语言模型"));
        values.put("Prompt Engineering", List.of("Prompt Engineering", "提示词工程", "Prompt"));
        values.put("Embedding", List.of("Embedding"));
        values.put("向量数据库", List.of("向量数据库", "Milvus", "Chroma"));
        values.put("RAG", List.of("RAG", "检索增强生成", "知识库问答"));
        values.put("LangChain", List.of("LangChain"));
        values.put("模型评测", List.of("模型评测", "大模型评测", "评测体系"));
        values.put("AI Agent", List.of("AI Agent", "智能体", "Agentic", "Agent"));
        values.put("JSP/Servlet", List.of("JSP/Servlet"));
        return Map.copyOf(values);
    }
}
