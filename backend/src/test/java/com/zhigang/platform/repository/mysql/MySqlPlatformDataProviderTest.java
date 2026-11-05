package com.zhigang.platform.repository.mysql;

import com.zhigang.platform.source.StructuredJdModels.RealResponsibilityMetric;
import com.zhigang.platform.source.StructuredJdModels.RealRoleSnapshot;
import com.zhigang.platform.source.StructuredJdModels.RealSkillMetric;
import com.zhigang.platform.source.StructuredJdModels.RealSnapshotView;
import com.zhigang.platform.domain.PlatformModels.SkillDefinition;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlPlatformDataProviderTest {
    @Test
    void repairsRepeatedUtf8MojibakeWithoutChangingChinese() {
        assertThat(MySqlPlatformDataProvider.repairMojibake("Java å¼€å‘å·¥ç¨‹å¸ˆ"))
                .isEqualTo("Java 开发工程师");
        assertThat(MySqlPlatformDataProvider.repairMojibake("å¤§æ¨¡åž‹è¯„æµ‹å·¥ç¨‹å¸ˆ"))
                .isEqualTo("大模型评测工程师");
        assertThat(MySqlPlatformDataProvider.repairMojibake("大模型推理工程师"))
                .isEqualTo("大模型推理工程师");
    }

    @Test
    void publishedDiscoveryQueryOnlySelectsPromotedNonDemoJobPostings() throws Exception {
        Method method = MySqlPlatformMapper.class.getMethod("selectDiscoveryDocuments");
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("JOIN promoted_document_metadata")
                .contains("JOIN dynamic_record")
                .contains("rd.is_demo=0")
                .contains("dr.record_type='JOB_POSTING'");
    }

    @Test
    void publicDataStatsCountsTheSameRoleSourcesAsPublicOverview() throws Exception {
        Method method = MySqlPlatformMapper.class.getMethod("selectPublicDataStats");
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("pdm.current_snapshot_eligible=1")
                .contains("pb.status='COMPLETED'")
                .contains("jr.is_emerging=1")
                .contains("gr.status='PUBLISHED'")
                .contains("release_job_skill")
                .contains("release_job_responsibility")
                .contains("release_job_scenario")
                .contains("published_role_document")
                .contains("graph_fingerprint")
                .contains("UNION")
                .doesNotContain("SELECT COUNT(*) FROM job_role jr WHERE jr.status='PUBLISHED'");
    }

    @Test
    void publicRoleDefinitionDescribesTheJobInsteadOfTheProcessingSnapshot() {
        String definition = MySqlPlatformDataProvider.buildPublicRoleDefinition(
                "Java 开发工程师",
                java.util.List.of("负责后端接口与核心业务模块开发", "参与系统设计与性能优化"),
                java.util.List.of("Java", "Spring Boot", "MySQL")
        );

        assertThat(definition).contains("Java 开发工程师")
                .contains("负责后端接口与核心业务模块开发")
                .contains("Java、Spring Boot、MySQL")
                .doesNotContain("试验", "快照", "样本", "不代表");
    }

    @Test
    void completedRealSnapshotExposesRolesBeforeGraphCatalogPublication() {
        MySqlPlatformMapper mapper = mock(MySqlPlatformMapper.class);
        MySqlStructuredJdRepository structuredRepository = mock(MySqlStructuredJdRepository.class);
        RealRoleSnapshot snapshotRole = new RealRoleSnapshot(
                "AI_DATA_ENGINEER", "AI 数据工程师", 18, 11, "真实岗位画像",
                List.of(new RealResponsibilityMetric("建设训练与检索数据链路", 12, 8, List.of())),
                List.of(new RealSkillMetric("SQL", 0.72, 13, 8, List.of())),
                List.of(new RealSkillMetric("Flink", 0.45, 8, 5, List.of())),
                List.of(), List.of());
        when(structuredRepository.findCurrentRealSnapshot()).thenReturn(new RealSnapshotView(
                "snapshot-2026-08", "REAL", "CURRENT_SNAPSHOT_ONLY", 18, 11, true,
                List.of(snapshotRole)));
        when(mapper.selectPublishedRoleIdentities()).thenReturn(List.of());
        when(mapper.selectPublishedEmergingRoles()).thenReturn(List.of());
        when(mapper.selectSkills()).thenReturn(List.of());
        MySqlPlatformDataProvider provider = new MySqlPlatformDataProvider(mapper, structuredRepository);

        var roles = provider.findJobRoles();

        assertThat(roles).singleElement().satisfies(role -> {
            assertThat(role.id()).isPositive();
            assertThat(role.code()).isEqualTo("AI_DATA_ENGINEER");
            assertThat(role.name()).isEqualTo("AI 数据工程师");
            assertThat(role.releaseVersion()).startsWith("real-jd-");
            assertThat(role.requiredSkills()).extracting("skill").containsExactly("SQL");
            assertThat(role.definition()).contains("建设训练与检索数据链路")
                    .doesNotContain("试验", "快照", "样本", "不代表");
        });
        assertThat(provider.findJobRole(roles.get(0).id())).isPresent();
    }

    @Test
    void realRoleProfileKeepsOnlyFrequentCoreSkillsAsRequired() {
        MySqlPlatformMapper mapper = mock(MySqlPlatformMapper.class);
        MySqlStructuredJdRepository structuredRepository = mock(MySqlStructuredJdRepository.class);
        RealRoleSnapshot snapshotRole = new RealRoleSnapshot(
                "JAVA_ENGINEER", "Java 开发工程师", 12, 6, "真实岗位画像", List.of(),
                List.of(
                        new RealSkillMetric("Java", 0.83, 6, 6, List.of()),
                        new RealSkillMetric("后端开发", 0.50, 3, 3, List.of()),
                        new RealSkillMetric("C++", 0.17, 1, 1, List.of()),
                        new RealSkillMetric("Hadoop", 0.17, 1, 1, List.of()),
                        new RealSkillMetric("JavaScript", 0.17, 1, 1, List.of()),
                        new RealSkillMetric("Python", 0.17, 1, 1, List.of())),
                List.of(), List.of(), List.of());
        when(structuredRepository.findCurrentRealSnapshot()).thenReturn(new RealSnapshotView(
                "snapshot-2026-08", "REAL", "CURRENT_SNAPSHOT_ONLY", 12, 6, true, List.of(snapshotRole)));
        when(mapper.selectPublishedRoleIdentities()).thenReturn(List.of());
        when(mapper.selectPublishedEmergingRoles()).thenReturn(List.of());
        when(mapper.selectSkills()).thenReturn(List.of());

        var role = new MySqlPlatformDataProvider(mapper, structuredRepository).findJobRoles().get(0);

        assertThat(role.requiredSkills()).extracting("skill").containsExactly("Java", "后端开发");
        assertThat(role.bonusSkills()).extracting("skill")
                .contains("C++", "Hadoop", "JavaScript", "Python");
    }

    @Test
    void sentenceShapedRoleRequirementsAreProjectedToCanonicalSkills() {
        MySqlPlatformMapper mapper = mock(MySqlPlatformMapper.class);
        MySqlStructuredJdRepository structuredRepository = mock(MySqlStructuredJdRepository.class);
        RealRoleSnapshot snapshotRole = new RealRoleSnapshot(
                "INFERENCE_ENGINEER", "大模型推理加速工程师", 4, 4, "真实岗位画像", List.of(),
                List.of(new RealSkillMetric(
                        "掌握大模型推理加速技术，包括模型量化、FlashAttention，熟悉vLLM和C++。",
                        1.0, 4, 4, List.of())), List.of(), List.of(), List.of());
        when(structuredRepository.findCurrentRealSnapshot()).thenReturn(new RealSnapshotView(
                "snapshot-2026-08", "REAL", "CURRENT_SNAPSHOT_ONLY", 4, 4, true, List.of(snapshotRole)));
        when(mapper.selectPublishedRoleIdentities()).thenReturn(List.of());
        when(mapper.selectPublishedEmergingRoles()).thenReturn(List.of());
        when(mapper.selectSkills()).thenReturn(List.of(
                skillRow(1, "大语言模型", "AI应用"), skillRow(2, "模型推理", "AI工程"),
                skillRow(3, "vLLM", "AI工程"), skillRow(4, "C++", "编程语言")));

        var role = new MySqlPlatformDataProvider(mapper, structuredRepository).findJobRoles().get(0);

        assertThat(role.requiredSkills()).extracting("skill")
                .contains("模型推理", "vLLM", "C++")
                .doesNotContain("C")
                .allMatch(skill -> !skill.toString().contains("掌握"));
    }

    @Test
    void roleWithoutMajoritySkillStillKeepsTopEvidenceAsRequired() {
        MySqlPlatformMapper mapper = mock(MySqlPlatformMapper.class);
        MySqlStructuredJdRepository structuredRepository = mock(MySqlStructuredJdRepository.class);
        RealRoleSnapshot snapshotRole = new RealRoleSnapshot(
                "DATA_ENGINEER", "数据工程师", 10, 10, "真实岗位画像", List.of(),
                List.of(new RealSkillMetric("SQL", 0.40, 4, 4, List.of()),
                        new RealSkillMetric("Flink", 0.30, 3, 3, List.of()),
                        new RealSkillMetric("Spark", 0.20, 2, 2, List.of())), List.of(), List.of(), List.of());
        when(structuredRepository.findCurrentRealSnapshot()).thenReturn(new RealSnapshotView(
                "snapshot-2026-08", "REAL", "CURRENT_SNAPSHOT_ONLY", 10, 10, true, List.of(snapshotRole)));
        when(mapper.selectPublishedRoleIdentities()).thenReturn(List.of());
        when(mapper.selectPublishedEmergingRoles()).thenReturn(List.of());
        when(mapper.selectSkills()).thenReturn(List.of());

        var role = new MySqlPlatformDataProvider(mapper, structuredRepository).findJobRoles().get(0);

        assertThat(role.requiredSkills()).extracting("skill").containsExactly("SQL", "Flink", "Spark");
    }

    private static java.util.Map<String, Object> skillRow(long id, String name, String category) {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", id);
        row.put("skill_name", name);
        row.put("category_name", category);
        row.put("aliases", "");
        row.put("prerequisites", "");
        return row;
    }
}
