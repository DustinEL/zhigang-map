package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateSeed;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.ClusterAssignment;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.FieldEvidence;
import com.zhigang.platform.emerging.EmergingDiscoveryPolicy;
import com.zhigang.platform.emerging.EmergingDiscoveryRepository;
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
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("mysql")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_E2E", matches = "true")
class MySqlEmergingDiscoveryE2ETest {
    private static final String E2E_DATABASE = "zhigang_map_e2e";

    @Autowired
    private EmergingDiscoveryRepository repository;

    @Autowired
    private EmergingDiscoveryPolicy policy;

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
    }

    @Test
    void automaticallyPublishesQualifiedCandidateAndWritesEvidenceMappingsAtomically() throws Exception {
        resetE2eSchema();
        long firstCandidateId = createCandidate("大模型推理加速工程师", "run-publish");
        var firstRun = repository.findRuns().get(0);
        assertThat(firstRun.createdAt().getHour()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT HOUR(created_at) FROM emerging_discovery_run WHERE id=?", Integer.class, firstRun.id()));
        var detail = repository.findCandidateDetail(firstCandidateId);
        var published = repository.findCandidate(firstCandidateId).orElseThrow();

        assertThat(published.status()).isEqualTo("AUTO_PUBLISHED");
        assertThat(detail.revisions()).isEmpty();
        assertThat(published.creator()).isNull();
        assertThat(published.reviewer()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_role WHERE role_name='大模型推理加速工程师'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM published_role_document WHERE candidate_id=?", Integer.class, firstCandidateId)).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM release_job_skill WHERE release_id=? AND job_role_id=?",
                Integer.class, publishedRelease(firstCandidateId), publishedRole(firstCandidateId))).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM release_job_skill WHERE release_id=? AND job_role_id IN (1,2)",
                Integer.class, publishedRelease(firstCandidateId))).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM graph_release WHERE release_version='2026.07'", String.class)).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM emerging_candidate_review WHERE candidate_id=?", Integer.class,
                firstCandidateId)).isZero();
    }

    private long createCandidate(String name, String runCode) {
        var run = repository.createRun(runCode, policy.version(), policy.hash(), "a".repeat(64), "bge-m3", 8, "e2e");
        repository.markRunning(run.id());
        List<ClusterAssignment> members = java.util.stream.LongStream.rangeClosed(1001, 1008)
                .mapToObj(id -> new ClusterAssignment(id, 0, .95, false)).toList();
        List<FieldEvidence> evidence = List.of(
                evidence("NAME", name), evidence("RESPONSIBILITY", "优化推理系统"),
                evidence("REQUIRED_SKILL", "C++"), evidence("REQUIRED_SKILL", "推理框架"),
                evidence("BONUS_SKILL", "GPU"), evidence("SCENARIO", "大模型服务"));
        repository.complete(run.id(), 1024, "sklearn-hdbscan-1.5.1", 1, 1, 0, List.of(new CandidateSeed(
                0, "AUTO_PUBLISH_READY", 8, 5, 2, .95, 5L, "LLMOps / 模型服务工程师", .72, 90,
                Map.of("previousWindowCompanies", 2.0, "currentWindowCompanies", 3.0), List.of(), name, "低",
                List.of("优化推理系统"), List.of("C++", "推理框架"), List.of("GPU"), List.of("大模型服务"),
                "RAG_SCHEMA_EVIDENCE", "b".repeat(64), members, evidence)));
        return repository.findCandidates().stream().filter(candidate -> candidate.runId() == run.id()).findFirst().orElseThrow().id();
    }

    private FieldEvidence evidence(String field, String value) {
        return new FieldEvidence(field, value, 1001, 0, 20, "大模型推理服务原文证据锚点", "SUPPORT", true);
    }

    private long publishedRole(long candidateId) {
        return jdbcTemplate.queryForObject("SELECT published_role_id FROM emerging_candidate WHERE id=?", Long.class, candidateId);
    }

    private long publishedRelease(long candidateId) {
        return jdbcTemplate.queryForObject("SELECT published_release_id FROM emerging_candidate WHERE id=?", Long.class, candidateId);
    }

    private void resetE2eSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, script("DROP DATABASE IF EXISTS " + E2E_DATABASE
                    + "; CREATE DATABASE " + E2E_DATABASE
                    + " DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci; USE " + E2E_DATABASE + ";"));
            executeProjectScript(connection, "01_schema.sql");
            executeProjectScript(connection, "02_seed_demo.sql");
            executeProjectScript(connection, "03_real_data_pipeline.sql");
            executeProjectScript(connection, "04_review_triage.sql");
            executeProjectScript(connection, "05_emerging_discovery.sql");
            executeProjectScript(connection, "08_p0_5_release_governance.sql");
        }
        jdbcTemplate.update("INSERT INTO promotion_batch (batch_code,target_snapshot_id,policy_version,status,candidate_count,promoted_count,requested_by,approved_by,completed_at) VALUES ('e2e-published',1,'e2e','COMPLETED',8,8,'creator','reviewer',CURRENT_TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO data_source (id,source_code,source_name,source_type,license_status,connector_type) VALUES (20,'E2E_SECOND','E2E 第二来源','RECRUITMENT_JD','AUTHORIZED','FILE')");
        for (int index = 1; index <= 8; index++) {
            jdbcTemplate.update("""
                    INSERT INTO raw_document (id,source_id,snapshot_id,document_title,company_name,raw_text,content_hash,published_at,is_demo)
                    VALUES (?,?,?,?,?,?,?,DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? DAY),0)
                    """, 1000 + index, index % 2 == 0 ? 20 : 1, 1, "未预建推理岗位 " + index,
                    "企业" + ((index - 1) % 5 + 1), "大模型推理服务原文证据锚点，包含推理框架、GPU 与吞吐优化。",
                    String.format("%064x", index), index <= 3 ? 20 : 110);
        }
    }

    private void executeProjectScript(Connection connection, String name) throws Exception {
        String sql = Files.readString(Path.of("..", "database", name), StandardCharsets.UTF_8)
                .replace("zhigang_map", E2E_DATABASE);
        ScriptUtils.executeSqlScript(connection, script(sql));
    }

    private org.springframework.core.io.InputStreamResource script(String sql) {
        return new org.springframework.core.io.InputStreamResource(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量 " + name);
        return value;
    }
}
