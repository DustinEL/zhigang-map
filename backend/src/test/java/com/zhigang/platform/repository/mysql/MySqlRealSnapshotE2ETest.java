package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhigang.platform.source.StructuredJdModels.BulkDynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillReviewCommand;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("mysql")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_E2E", matches = "true")
class MySqlRealSnapshotE2ETest {
    private static final String E2E_DATABASE = "zhigang_map_e2e";

    @Autowired
    private StructuredJdService service;

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
    void importsReviewsAndPublishesTenRealDocumentsIdempotently() throws Exception {
        resetE2eSchema();
        StructuredJdBatch batch = frozenCorpusBatch();

        var imported = service.importBatch(batch);
        assertThat(imported.acceptedCount()).isEqualTo(10);

        for (var candidate : service.candidateSkills()) {
            service.reviewCandidateSkill(candidate.skill(), new CandidateSkillReviewCommand(
                    candidate.recommendedDecision(), candidate.recommendedTarget(), "professional_dev",
                    String.join("；", candidate.recommendationReasons())));
        }
        var records = service.records(imported.id(), null, null, null, null, false, 1, 100);
        List<Long> manualRecords = records.items().stream()
                .filter(item -> List.of("SAMPLE_REVIEW", "EXCEPTION_REVIEW").contains(item.reviewRoute()))
                .map(item -> item.id()).toList();
        if (!manualRecords.isEmpty()) {
            service.bulkReviewRecords(new BulkDynamicReviewCommand(manualRecords, "APPROVE",
                    "professional_dev", "E2E 人工抽检与异常确认"));
        }

        var promotion = service.createPromotionBatch(new PromotionBatchCommand(
                List.of(), imported.id(), "ALL_ELIGIBLE", "professional_dev"));
        promotion = service.reviewPromotionBatch(promotion.id(),
                new PromotionReviewCommand("APPROVE", "reviewer_dev", "E2E 独立审核"));
        promotion = service.publishPromotionBatch(promotion.id(), new PromotionPublishCommand("professional_dev"));
        var repeated = service.publishPromotionBatch(promotion.id(), new PromotionPublishCommand("professional_dev"));
        var snapshot = service.currentRealSnapshot();

        assertThat(promotion.status()).isEqualTo("COMPLETED");
        assertThat(repeated.status()).isEqualTo("COMPLETED");
        assertThat(snapshot.documentCount()).isEqualTo(10);
        assertThat(snapshot.roles()).extracting(role -> role.roleCode())
                .containsExactlyInAnyOrder("AI_AGENT_ENGINEER", "JAVA_ENGINEER");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dynamic_record WHERE status='PROMOTED'",
                Integer.class)).isEqualTo(10);

        var repeatedImport = service.importBatch(batch);
        assertThat(repeatedImport.id()).isEqualTo(imported.id());
        assertThat(repeatedImport.acceptedCount()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crawler_run", Integer.class)).isEqualTo(1);
    }

    private StructuredJdBatch frozenCorpusBatch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<JsonNode> selected = new ArrayList<>();
        int javaCount = 0;
        int agentCount = 0;
        for (String line : Files.readAllLines(Path.of("..", "test-data", "jd-corpus", "qualified.jsonl"),
                StandardCharsets.UTF_8)) {
            JsonNode record = objectMapper.readTree(line);
            if (!"iguopin-current-cn".equals(record.path("sourceId").asText())) {
                continue;
            }
            String roleCode = record.path("primaryTargetRoleCode").asText();
            boolean takeJava = "JAVA_ENGINEER".equals(roleCode) && javaCount < 5;
            boolean takeAgent = "AI_AGENT_ENGINEER".equals(roleCode) && agentCount < 5;
            if (takeJava || takeAgent) {
                selected.add(record);
                javaCount += takeJava ? 1 : 0;
                agentCount += takeAgent ? 1 : 0;
            }
            if (selected.size() == 10) {
                break;
            }
        }
        assertThat(selected).as("冻结语料应提供 5 条 Java 和 5 条智能体国聘 JD").hasSize(10);
        List<StructuredJdDocument> documents = selected.stream().map(this::toDocument).toList();
        int characters = documents.stream().mapToInt(StructuredJdDocument::rawTextLength).sum();
        String corpusHash = sha256(selected.stream().map(JsonNode::toString).reduce("", String::concat));
        return new StructuredJdBatch("1.0.0", OffsetDateTime.parse("2026-08-01T16:00:00+08:00"),
                "mysql-e2e-frozen-corpus-v1",
                new SourceWorkbook("qualified.jsonl", corpusHash, documents.size(), characters),
                new ProcessingPolicy("cn-computer-jd-v1.0", "PUBLIC_CHINA_JD_RULE_EXTRACTION",
                        "SOURCE_DISCLOSED_OR_NULL", "PUBLIC_WEB_REVIEWED_FOR_LOCAL_COMPETITION_USE",
                        "CURRENT_CHINA_COMPUTER_JD_ONLY"), documents);
    }

    private StructuredJdDocument toDocument(JsonNode record) {
        String rawText = record.path("rawText").asText();
        OffsetDateTime observedAt = OffsetDateTime.parse(record.path("collectedAt").asText());
        String publishedAt = record.path("publishedAt").asText();
        String sourceUrl = record.path("sourceUrl").asText();
        Source source = new Source(record.path("sourceId").asText(), record.path("sourceName").asText(),
                record.path("sourceTier").asText("TIER_1"), "PUBLIC_EMPLOYMENT",
                record.path("sourceType").asText(), "PUBLIC_WEB_REVIEWED",
                record.path("licenseStatus").asText() + "；" + record.path("licenseNote").asText(),
                record.path("originalPostingUrl").asText(sourceUrl), sourceUrl);
        return new StructuredJdDocument(record.path("sourceRecordKey").asText(),
                record.path("jobTitle").asText(), record.path("company").asText(), record.path("city").asText(),
                new Salary("", null, null, java.math.BigDecimal.valueOf(12), "CNY"),
                record.path("jobExperience").asText(record.path("jobLevel").asText()), "",
                publishedAt.isBlank() ? null : OffsetDateTime.parse(publishedAt),
                LocalDate.ofInstant(observedAt.toInstant(), ZoneOffset.ofHours(8)), "CURRENT", observedAt,
                source, rawText, rawText.length(), sha256(rawText), "REAL", "ELIGIBLE",
                List.of("TARGET_ROLE:" + record.path("primaryTargetRoleCode").asText()),
                new Facts(List.of(), List.of()));
    }

    private void resetE2eSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, script("DROP DATABASE IF EXISTS " + E2E_DATABASE
                    + "; CREATE DATABASE " + E2E_DATABASE
                    + " DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci; USE "
                    + E2E_DATABASE + ";"));
            executeProjectScript(connection, "01_schema.sql");
            executeProjectScript(connection, "03_real_data_pipeline.sql");
            executeProjectScript(connection, "04_review_triage.sql");
            executeProjectScript(connection, "05_emerging_discovery.sql");
            executeProjectScript(connection, "08_p0_5_release_governance.sql");
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

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
