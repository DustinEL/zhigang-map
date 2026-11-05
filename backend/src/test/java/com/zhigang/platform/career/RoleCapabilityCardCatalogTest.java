package com.zhigang.platform.career;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleCapabilityCardCatalogTest {
    private final MatchDecisionEngine decisionEngine = new MatchDecisionEngine();

    @Test
    void frozenCatalogContainsEightAtomicReviewedRoleCards() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(catalog.cards()).hasSize(8);
        assertThat(catalog.cards()).extracting(RoleCapabilityCard::roleId)
                .containsExactlyInAnyOrder(2L, 3L, 4L, 5L, 7L, 9L, 12L, 13L);
        assertThat(catalog.find(2L)).isPresent();
        assertThat(catalog.find(999L)).isEmpty();
        assertThat(catalog.snapshotSha256()).matches("[0-9a-f]{64}");
        assertThat(catalog.cards()).allSatisfy(card -> {
            assertThat(card.version()).isNotBlank();
            assertThat(card.sourceReferences()).isNotEmpty();
            assertThat(card.coreCapabilities()).hasSizeBetween(3, 5);
            assertThat(card.coreCapabilities()).allSatisfy(group -> {
                assertThat(group.alternatives()).isNotEmpty();
                assertThat(group.nearEvidenceLevel()).isBetween(1, 4);
                assertThat(group.readyEvidenceLevel()).isBetween(group.nearEvidenceLevel(), 4);
            });
        });
    }

    @Test
    void rejectsSentenceShapedCoreSkills() {
        RoleCapabilityCard invalid = new RoleCapabilityCard(
                99L, "INVALID", "无效岗位", "v2",
                List.of(
                        new RoleCapabilityCard.CapabilityGroup("a", List.of("掌握大模型推理核心技术，具备丰富的项目经验"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("b", List.of("Python"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("c", List.of("Linux"), 1, 2, false)
                ),
                List.of(), List.of(), List.of("source-1"), List.of("reviewer-1"), "ENGINEERING_BASELINE");

        assertThatThrownBy(() -> RoleCapabilityCardCatalog.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原子技能");
    }

    @Test
    void rejectsDecisionPolicyThatReferencesUnknownCapabilityGroup() {
        RoleCapabilityCard invalid = new RoleCapabilityCard(
                99L, "INVALID_POLICY", "无效策略岗位", "v2",
                List.of(
                        new RoleCapabilityCard.CapabilityGroup("language", List.of("Python"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("backend", List.of("FastAPI"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("data", List.of("MySQL"), 1, 2, false)
                ),
                List.of(), List.of(), List.of("source-1"), List.of("reviewer-1"), "TEST",
                new RoleCapabilityCard.DecisionPolicy(List.of("missing"), 0.75, 0.50, 0, 0));

        assertThatThrownBy(() -> RoleCapabilityCardCatalog.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("锚点");
    }

    @Test
    void rejectsRequiredNearGroupThatDoesNotExist() {
        RoleCapabilityCard invalid = new RoleCapabilityCard(
                99L, "INVALID_POLICY", "无效策略岗位", "v2",
                List.of(
                        new RoleCapabilityCard.CapabilityGroup("language", List.of("Python"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("backend", List.of("FastAPI"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("data", List.of("MySQL"), 1, 2, false)
                ),
                List.of(), List.of(), List.of("source-1"), List.of("reviewer-1"), "TEST",
                new RoleCapabilityCard.DecisionPolicy(List.of("backend"), List.of("missing"),
                        0.75, 0.50, 0, 0));

        assertThatThrownBy(() -> RoleCapabilityCardCatalog.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("组合门槛");
    }

    @Test
    void standardCardsRequireRoleSpecificNearAnchors() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 2L, evidence("MySQL", 2), evidence("Docker", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 2L, evidence("Spring Boot", 2), evidence("MySQL", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 3L, evidence("Python", 2), evidence("Docker", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 5L, evidence("Linux", 2), evidence("Python", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 5L, evidence("Linux", 2), evidence("Docker", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 12L, evidence("Python", 2), evidence("OpenAI", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 3L, evidence("Python", 2), evidence("GPT-3", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 4L, evidence("Python", 2), evidence("REST API", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 7L, evidence("Python", 2), evidence("GPT-3", 2)))
                .isEqualTo("需先补关键能力");
    }

    @Test
    void llmApplicationNearRequiresRetrievalOrInteractionEvidence() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 7L,
                evidence("Python", 2), evidence("Claude", 2), evidence("Docker", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 7L,
                evidence("Python", 1), evidence("Claude", 2), evidence("Prompt Engineering", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 7L,
                evidence("Python", 1), evidence("Claude", 2), evidence("RAG", 2)))
                .isEqualTo("接近可申请");
    }

    @Test
    void standardCardsAcceptReviewedBackendLlmAndDataAnchors() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 2L, evidence("Java", 1), evidence("Spring Boot", 1)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 4L, evidence("REST API", 2), evidence("OpenAI", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 7L, evidence("Python", 2), evidence("Gemini", 2), evidence("聊天机器人", 2)))
                .isEqualTo("可申请实习/校招");
        assertThat(decision(catalog, 9L, evidence("Python", 2), evidence("数据清洗", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 9L, evidence("Python", 2), evidence("SQL", 1), evidence("数据清洗", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 5L, evidence("Linux", 2), evidence("vLLM", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 12L, evidence("OpenAI", 2), evidence("渗透测试", 2)))
                .isEqualTo("接近可申请");
    }

    @Test
    void standardLlmCardsRecognizeCommonModelAndProviderNames() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 3L, evidence("Python", 2), evidence("聊天机器人", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 7L, evidence("Python", 2), evidence("聊天机器人", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 3L, evidence("Python", 2), evidence("LangChain", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 4L, evidence("REST API", 2), evidence("GPT-2", 2)))
                .isEqualTo("接近可申请");
    }

    @Test
    void llmApiCountsAsLlmCapabilityInAgentAndApplicationRoles() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 3L,
                evidence("Python", 2), evidence("LLM API", 2), evidence("LangChain", 2),
                evidence("RAG", 2)))
                .isEqualTo("可申请实习/校招");
        assertThat(decision(catalog, 7L,
                evidence("Python", 2), evidence("LLM API", 2), evidence("RAG", 2),
                evidence("LangChain", 2)))
                .isEqualTo("可申请实习/校招");
    }

    @Test
    void sameProjectRagAndLlmApiCountsAsAgentApplicationEvidence() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 3L,
                evidence("Python", 2), evidence("LLM API", 2), evidence("RAG", 2)))
                .isEqualTo("接近可申请");
    }

    @Test
    void aiSecurityRecognizesOwaspAndPromptInjectionTestingEvidence() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 12L,
                evidence("OWASP Top 10", 4), evidence("Prompt Injection Testing", 4),
                evidence("OpenAI", 2), evidence("Python", 2)))
                .isEqualTo("可申请实习/校招");
    }

    @Test
    void agentAndAiDataRolesAcceptProjectBackedAdjacentAnchorsForNear() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 3L,
                evidence("Python", 2), evidence("OpenAI", 2), evidence("Embedding", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 9L,
                evidence("Python", 2), evidence("SQL", 2)))
                .isEqualTo("需先补关键能力");
    }

    @Test
    void aiDataNearRequiresThreeBlockingGroupsAndDoesNotTreatSensorAsProcessing() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 9L,
                evidence("Python", 2), evidence("数据清洗", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 9L,
                evidence("Python", 2), evidence("SQL", 2), evidence("传感器", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 9L,
                evidence("Python", 2), evidence("SQL", 2), evidence("Pandas", 2)))
                .isEqualTo("接近可申请");
    }

    @Test
    void readyPoliciesRequireDataPipelineButAcceptThreeStrongLlmCoreGroups() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 9L,
                evidence("Python", 2), evidence("SQL", 2), evidence("数据清洗", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 3L,
                evidence("Python", 2), evidence("OpenAI", 2), evidence("AI Agent", 2)))
                .isEqualTo("可申请实习/校招");
        assertThat(decision(catalog, 7L,
                evidence("Python", 2), evidence("OpenAI", 2), evidence("聊天机器人", 2)))
                .isEqualTo("可申请实习/校招");
    }

    @Test
    void aiBackendAcceptsCommonProductionBackendAndModelServingStacks() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 4L,
                evidence("C#", 2), evidence(".NET", 2), evidence("RAG", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 4L,
                evidence("C#", 2), evidence("ASP.NET Core", 2), evidence("RAG", 2)))
                .isEqualTo("可申请实习/校招");
        assertThat(decision(catalog, 4L,
                evidence("Python", 2), evidence("Flask", 2), evidence("OpenAI", 2)))
                .isEqualTo("可申请实习/校招");
        assertThat(decision(catalog, 4L,
                evidence("Python", 2), evidence("FastAPI", 2), evidence("vLLM", 2)))
                .isEqualTo("可申请实习/校招");
    }

    @Test
    void aiBackendNearRequiresBackendImplementationEvidence() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 4L,
                evidence("Python", 2), evidence("OpenAI", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 4L,
                evidence("Java", 2), evidence("Spring Boot", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 4L,
                evidence("FastAPI", 2), evidence("RAG", 2)))
                .isEqualTo("接近可申请");
    }

    @Test
    void llmOpsAndAiDataCardsAcceptExplicitProductionPipelineTechnologies() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 5L,
                evidence("Bash", 2), evidence("Docker", 4), evidence("vLLM", 4), evidence("Python", 4)))
                .isEqualTo("可申请实习/校招");
        assertThat(decision(catalog, 9L,
                evidence("Python", 2), evidence("SQL", 2), evidence("Airflow", 2), evidence("数据清洗", 2)))
                .isEqualTo("可申请实习/校招");
        assertThat(decision(catalog, 9L,
                evidence("Python", 2), evidence("SQL", 2), evidence("Kafka", 2), evidence("PySpark", 2)))
                .isEqualTo("可申请实习/校招");
    }

    @Test
    void llmOpsRequiresExplicitModelServingEvidenceWhileAiDataAllowsProcessingFoundationsNear() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 5L,
                evidence("Python", 2), evidence("Docker", 2), evidence("LLM", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 5L,
                evidence("Python", 2), evidence("Docker", 2), evidence("模型微调", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 5L,
                evidence("Python", 2), evidence("Kubernetes", 2), evidence("MLOps", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 5L,
                evidence("Python", 2), evidence("Docker", 2), evidence("模型服务", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 9L,
                evidence("Python", 1), evidence("SQL", 1), evidence("Pandas", 1)))
                .isEqualTo("接近可申请");
    }

    @Test
    void aiBackendReadyRequiresStrongAiProjectButAcceptsListedLanguageAndFramework() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 4L,
                evidence("TypeScript", 1), evidence("FastAPI", 1), evidence("GPT-2", 2)))
                .isEqualTo("可申请实习/校招");
        assertThat(decision(catalog, 4L,
                evidence("TypeScript", 1), evidence("FastAPI", 1), evidence("GPT-2", 1)))
                .isEqualTo("接近可申请");
    }

    @Test
    void inferenceAccelerationRequiresServingOrComputeEvidenceBeforeNear() {
        RoleCapabilityCardCatalog catalog = new RoleCapabilityCardCatalog(new ObjectMapper());

        assertThat(decision(catalog, 13L,
                evidence("C++", 2), evidence("Linux", 2)))
                .isEqualTo("需先补关键能力");
        assertThat(decision(catalog, 13L,
                evidence("C++", 2), evidence("vLLM", 2)))
                .isEqualTo("接近可申请");
        assertThat(decision(catalog, 13L,
                evidence("C++", 2), evidence("vLLM", 2), evidence("CUDA", 2),
                evidence("DeepSpeed", 2), evidence("Linux", 2)))
                .isEqualTo("可申请实习/校招");
    }

    private String decision(RoleCapabilityCardCatalog catalog, long roleId,
                            ResumeEvidenceBundle.SkillEvidence... evidence) {
        ResumeEvidenceBundle bundle = new ResumeEvidenceBundle("test", "hash", List.of(evidence));
        return decisionEngine.evaluate(catalog.find(roleId).orElseThrow(), bundle, true).label();
    }

    private ResumeEvidenceBundle.SkillEvidence evidence(String skill, int level) {
        return new ResumeEvidenceBundle.SkillEvidence(skill, "使用 " + skill + " 完成项目", 0, skill.length(),
                "PROJECT", "project-1", "PROJECT", "实现", "完成项目", level, false, 0.99);
    }
}
