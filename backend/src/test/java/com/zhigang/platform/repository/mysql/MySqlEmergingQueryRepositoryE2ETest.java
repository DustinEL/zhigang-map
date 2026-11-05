package com.zhigang.platform.repository.mysql;

import com.zhigang.platform.emerging.EmergingQueryModels.QueryDraft;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryEvidenceDraft;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryFieldEvidenceDraft;
import com.zhigang.platform.emerging.EmergingQueryRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("mysql")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_E2E", matches = "true")
class MySqlEmergingQueryRepositoryE2ETest {
    private static final String E2E_DATABASE = "zhigang_map_e2e";

    @Autowired
    private EmergingQueryRepository repository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        String url = requiredEnvironment("MYSQL_E2E_URL");
        String database = url.replaceFirst("[?].*$", "").replaceFirst(".*/", "");
        if (!E2E_DATABASE.equals(database)) throw new IllegalStateException("MySQL E2E 只允许连接 " + E2E_DATABASE);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnvironment("MYSQL_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_PASSWORD", ""));
        registry.add("spring.flyway.enabled", () -> false);
    }

    @Test
    void cachesFieldEvidenceAndPublishesWithoutIndependentReview() throws Exception {
        resetE2eSchema();
        List<QueryEvidenceDraft> evidence = List.of(
                new QueryEvidenceDraft("E1", "提示词工程岗位观察", "来源甲", "example-a.test",
                        "https://example-a.test/prompt", "2026-07-01", "招聘资料明确要求提示策略设计和持续评测。"),
                new QueryEvidenceDraft("E2", "大模型应用岗位研究", "来源乙", "example-b.test",
                        "https://example-b.test/llm", "2026-07-02", "岗位需要理解大语言模型并构建评测方法。"),
                new QueryEvidenceDraft("E3", "AI 应用工程场景", "来源乙", "example-b.test",
                        "https://example-b.test/scenario", "2026-07-03", "典型场景包括智能客服与内容生成应用优化。")
        );
        List<QueryFieldEvidenceDraft> citations = List.of(
                new QueryFieldEvidenceDraft("NAME", "提示词工程师", "E1"),
                new QueryFieldEvidenceDraft("DEFINITION", "负责提示策略设计、评测与优化的人工智能岗位。", "E1"),
                new QueryFieldEvidenceDraft("RESPONSIBILITY", "设计并持续评测提示策略", "E1"),
                new QueryFieldEvidenceDraft("REQUIRED_SKILL", "大语言模型", "E2"),
                new QueryFieldEvidenceDraft("SCENARIO", "智能客服与内容生成优化", "E3")
        );
        var saved = repository.save(new QueryDraft("提示词工程师", "提示词工程师", "a".repeat(64),
                "EMERGING_ROLE", "人工智能", "提示词工程师", "负责提示策略设计、评测与优化的人工智能岗位。",
                List.of("设计并持续评测提示策略"), List.of("大语言模型"), List.of(),
                List.of("智能客服与内容生成优化"), 1L, "AI Agent 开发工程师", .61, .39,
                true, true, true, "generalv3", "b".repeat(64), "", "", evidence, citations));

        var published = repository.publish(saved.id(), "e2e-auto-gate");

        assertThat(published.publishedRoleId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT is_emerging FROM job_role WHERE id=?", Integer.class,
                published.publishedRoleId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM published_role_query_evidence WHERE discovery_id=?",
                Integer.class, saved.id())).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM emerging_candidate_review", Integer.class)).isZero();
    }

    private void resetE2eSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, script("DROP DATABASE IF EXISTS " + E2E_DATABASE
                    + "; CREATE DATABASE " + E2E_DATABASE
                    + " DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci; USE " + E2E_DATABASE + ";"));
            for (String name : List.of("01_schema.sql", "02_seed_demo.sql", "03_real_data_pipeline.sql",
                    "04_review_triage.sql", "05_emerging_discovery.sql", "06_emerging_query_discovery.sql",
                    "08_p0_5_release_governance.sql")) {
                executeProjectScript(connection, name);
            }
        }
        jdbcTemplate.update("""
                INSERT INTO promotion_batch
                (batch_code,target_snapshot_id,policy_version,status,candidate_count,promoted_count,requested_by,approved_by,completed_at)
                VALUES ('query-e2e',1,'e2e','COMPLETED',0,0,'system','system',CURRENT_TIMESTAMP)
                """);
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
