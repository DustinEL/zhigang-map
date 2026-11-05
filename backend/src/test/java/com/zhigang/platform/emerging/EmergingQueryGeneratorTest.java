package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.ai.GenerationModelClient;
import com.zhigang.platform.emerging.VerifiedWebEvidenceSearch.VerifiedEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergingQueryGeneratorTest {
    private final GenerationModelClient modelClient = mock(GenerationModelClient.class);
    private final VerifiedWebEvidenceSearch webEvidenceSearch = mock(VerifiedWebEvidenceSearch.class);
    private final EmergingQueryGenerator generator = new EmergingQueryGenerator(modelClient, new ObjectMapper(), webEvidenceSearch);

    @BeforeEach
    void verifiedEvidence() {
        when(webEvidenceSearch.search(anyString())).thenReturn(List.of(new VerifiedEvidence(
                "E1", "Prompt Engineer 招聘要求", "jobs.example.cn",
                "https://jobs.example.cn/prompt-engineer", "2026-07-01",
                "岗位负责提示策略设计、测试与持续评估，并要求理解大语言模型应用。")));
    }

    @Test
    void acceptsStructuredFieldsOnlyWhenEveryValueReferencesKnownEvidence() {
        when(modelClient.model()).thenReturn("generalv3");
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(java.util.Optional.of("""
                {
                  "classification":"EMERGING_ROLE",
                  "domainAllowed":true,
                  "domain":"人工智能",
                  "existingRoleDifference":"面向提示优化评测",
                  "name":{"value":"提示词工程师","evidenceIds":["E1"]},
                  "definition":{"value":"负责提示策略设计与评测的人工智能岗位","evidenceIds":["E1"]},
                  "responsibilities":[{"value":"设计并评测提示策略","evidenceIds":["E1"]}],
                  "requiredSkills":[{"value":"大语言模型","evidenceIds":["E1"]}],
                  "bonusSkills":[],
                  "scenarios":[{"value":"大模型应用优化","evidenceIds":["E1"]}],
                  "evidence":[{
                    "id":"E1","title":"Prompt Engineer 招聘要求",
                    "url":"https://jobs.example.cn/prompt-engineer","source":"测试招聘站",
                    "publishedAt":"2026-07-01","excerpt":"岗位负责提示策略设计、测试与持续评估。"
                  }]
                }
                """));

        var result = generator.generate("提示词工程师", new EmergingQueryGenerator.RoleMatch(1L, "AI Agent 开发工程师", 0.62));

        assertThat(result.mode()).isEqualTo("READY");
        assertThat(result.fieldEvidenceComplete()).isTrue();
        assertThat(result.name()).isEqualTo("提示词工程师");
        assertThat(result.fieldEvidence()).extracting(item -> item.fieldName()).contains(
                "NAME", "DEFINITION", "RESPONSIBILITY", "REQUIRED_SKILL", "SCENARIO");
    }

    @Test
    void rejectsUnknownEvidenceReferences() {
        when(modelClient.model()).thenReturn("generalv3");
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(java.util.Optional.of("""
                {
                  "classification":"EMERGING_ROLE","domainAllowed":true,"domain":"人工智能",
                  "name":{"value":"提示词工程师","evidenceIds":["E2"]},
                  "definition":{"value":"岗位定义","evidenceIds":["E1"]},
                  "responsibilities":[{"value":"设计提示","evidenceIds":["E1"]}],
                  "requiredSkills":[{"value":"大语言模型","evidenceIds":["E1"]}],
                  "bonusSkills":[],"scenarios":[{"value":"AI应用","evidenceIds":["E1"]}],
                  "evidence":[{"id":"E1","title":"来源","url":"https://jobs.example.cn/a","source":"测试来源","publishedAt":"","excerpt":"这是能够支持字段的公开资料摘要。"}]
                }
                """));

        var result = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(result.mode()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(result.fieldEvidenceComplete()).isFalse();
    }

    @Test
    void refusesResultsOutsideConfiguredIndustryScope() {
        when(modelClient.model()).thenReturn("generalv3");
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(java.util.Optional.of("""
                {"classification":"EVIDENCE_INSUFFICIENT","domainAllowed":false,"domain":"餐饮"}
                """));

        var result = generator.generate("数字菜品研发师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(result.mode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(result.errorCode()).isEqualTo("QUERY_OUT_OF_SCOPE");
    }

    @Test
    void retriesPureEnglishRoleNameAsAChineseJobTitle() {
        when(modelClient.model()).thenReturn("generalv3");
        when(modelClient.generateJson(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(structuredResult("Machine Learning Engineer")))
                .thenReturn(java.util.Optional.of(structuredResult("机器学习工程师")));

        var result = generator.generate("Machine Learning Engineer", EmergingQueryGenerator.RoleMatch.none());

        assertThat(result.name()).isEqualTo("机器学习工程师");
        assertThat(result.mode()).isEqualTo("READY");
        verify(modelClient, times(2)).generateJson(anyString(), anyString());
    }

    @Test
    void reportsNeutralUnavailableErrorAndConfiguredModelWhenModelReturnsEmpty() {
        when(modelClient.model()).thenReturn("qwen-local");
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(java.util.Optional.empty());

        var result = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(result.errorCode()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(result.errorMessage()).isEqualTo("生成模型不可用或响应超时");
        assertThat(result.model()).isEqualTo("qwen-local");
    }

    @Test
    void reportsNeutralInvalidResponseErrorAndConfiguredModelForInvalidJson() {
        when(modelClient.model()).thenReturn("qwen-local");
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(java.util.Optional.of("不是 JSON"));

        var result = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(result.errorCode()).isEqualTo("MODEL_RESPONSE_INVALID");
        assertThat(result.errorMessage()).isEqualTo("生成模型返回的 JSON 或字段引用不符合约束");
        assertThat(result.model()).isEqualTo("qwen-local");
    }

    @Test
    void rejectsReservedPlaceholderEvidenceDomains() {
        when(modelClient.model()).thenReturn("generalv3");
        when(webEvidenceSearch.search(anyString())).thenReturn(List.of(new VerifiedEvidence(
                "E1", "占位来源", "example.com", "https://example.com/a", "",
                "这是模型编造的占位来源摘要，不能进入岗位证据。")));
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(java.util.Optional.of("""
                {
                  "classification":"EMERGING_ROLE","domainAllowed":true,"domain":"人工智能",
                  "name":{"value":"提示词工程师","evidenceIds":["E1"]},
                  "definition":{"value":"岗位定义","evidenceIds":["E1"]},
                  "responsibilities":[{"value":"设计提示","evidenceIds":["E1"]}],
                  "requiredSkills":[{"value":"大语言模型","evidenceIds":["E1"]}],
                  "bonusSkills":[],"scenarios":[{"value":"AI应用","evidenceIds":["E1"]}],
                  "evidence":[{"id":"E1","title":"占位来源","url":"https://example.com/a","source":"示例","publishedAt":"","excerpt":"这是模型编造的占位来源摘要，不能进入岗位证据。"}]
                }
                """));

        var result = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(result.mode()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(result.errorCode()).isEqualTo("WEB_EVIDENCE_INSUFFICIENT");
    }

    @Test
    void rejectsNonEnumeratedDomainLabels() {
        when(modelClient.model()).thenReturn("generalv3");
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(java.util.Optional.of("""
                {"classification":"EMERGING_ROLE","domainAllowed":true,
                 "domain":"人工智能/软件/大数据/物联网/智能系统中的一个"}
                """));

        var result = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(result.mode()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(result.errorMessage()).contains("领域");
    }

    @Test
    void ignoresModelProvidedEvidenceAndUsesOnlyVerifiedEvidence() {
        when(modelClient.model()).thenReturn("generalv3");
        when(webEvidenceSearch.search(anyString())).thenReturn(List.of(new VerifiedEvidence(
                "E1", "真实来源", "real.example.cn", "https://real.example.cn/a", "",
                "真实网页正文说明提示词工程师负责设计提示策略并评估大模型输出质量。")));
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(java.util.Optional.of("""
                {
                  "classification":"EMERGING_ROLE","domainAllowed":true,"domain":"人工智能",
                  "name":{"value":"提示词工程师","evidenceIds":["E1"]},
                  "definition":{"value":"岗位定义","evidenceIds":["E1"]},
                  "responsibilities":[{"value":"设计提示","evidenceIds":["E1"]}],
                  "requiredSkills":[{"value":"大语言模型","evidenceIds":["E1"]}],
                  "bonusSkills":[],"scenarios":[{"value":"AI应用","evidenceIds":["E1"]}],
                  "evidence":[{"id":"E1","title":"伪造来源","url":"https://fake.example.cn/a","source":"伪造","publishedAt":"","excerpt":"模型生成了不在平台检索信源中的链接。"}]
                }
                """));

        var generated = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(generated.mode()).isEqualTo("READY");
        assertThat(generated.evidence()).singleElement().satisfies(item -> {
            assertThat(item.url()).isEqualTo("https://real.example.cn/a");
            assertThat(item.title()).isEqualTo("真实来源");
        });
    }

    @Test
    void retriesWhenStructuredFieldsDoNotUseAvailableIndependentEvidence() {
        when(modelClient.model()).thenReturn("4.0Ultra");
        when(webEvidenceSearch.search(anyString())).thenReturn(List.of(
                evidence("E1", "https://one.example.cn/a"),
                evidence("E2", "https://two.example.cn/b"),
                evidence("E3", "https://three.example.cn/c")
        ));
        when(modelClient.generateJson(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(structuredJson("[\"E1\"]", "[\"E1\"]")))
                .thenReturn(java.util.Optional.of(structuredJson("[\"E1\",\"E2\",\"E3\"]", "[\"E1\",\"E2\",\"E3\"]")));

        var generated = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(generated.mode()).isEqualTo("READY");
        assertThat(generated.evidence()).hasSize(3);
        verify(modelClient, times(2)).generateJson(anyString(), anyString());
    }

    @Test
    void retryRequestsAllAvailableEvidenceWhenFewerThanThreeSourcesExist() {
        when(modelClient.model()).thenReturn("4.0Ultra");
        when(webEvidenceSearch.search(anyString())).thenReturn(List.of(
                evidence("E1", "https://one.example.cn/a"),
                evidence("E2", "https://two.example.cn/b")
        ));
        when(modelClient.generateJson(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(structuredJson("[\"E1\"]", "[\"E1\"]")))
                .thenReturn(java.util.Optional.of(structuredJson("[\"E1\",\"E2\"]", "[\"E1\",\"E2\"]")));

        var generated = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(generated.evidence()).hasSize(2);
        verify(modelClient).generateJson(anyString(), contains("覆盖 2 条不同证据（E1、E2）"));
    }

    @Test
    void fallsBackToClearlyLabelledReasoningAfterCitationRetry() {
        when(modelClient.model()).thenReturn("4.0Ultra");
        when(webEvidenceSearch.search(anyString())).thenReturn(List.of(
                evidence("E1", "https://one.example.cn/a"),
                evidence("E2", "https://two.example.cn/b"),
                evidence("E3", "https://three.example.cn/c")
        ));
        when(modelClient.generateJson(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(structuredJson("[\"E1\"]", "[\"E1\"]")))
                .thenReturn(java.util.Optional.of(structuredJson("[\"E1\"]", "[\"E1\"]")))
                .thenReturn(java.util.Optional.of("""
                        {
                          "classification":"EMERGING_ROLE","domainAllowed":true,"domain":"人工智能",
                          "confidence":"LOW","reasoningBasis":"基于 E1 的有限证据并结合领域知识推理",
                          "name":"提示词工程师","definition":"负责提示策略设计与模型输出评估",
                          "responsibilities":["设计提示策略"],
                          "requiredSkills":["大语言模型"],"bonusSkills":[],
                          "scenarios":["人工智能应用优化"]
                        }
                        """));

        var generated = generator.generate("提示词工程师", EmergingQueryGenerator.RoleMatch.none());

        assertThat(generated.mode()).isEqualTo("REASONING");
        assertThat(generated.evidence()).anyMatch(item -> "REASONING".equals(item.key()));
        assertThat(generated.errorCode()).isEqualTo("AI_REASONING_LOW");
        verify(modelClient, times(3)).generateJson(anyString(), anyString());
    }

    private VerifiedEvidence evidence(String key, String url) {
        return new VerifiedEvidence(key, "真实来源" + key, URI.create(url).getHost(), url, "",
                "提示词工程师岗位需要设计提示策略、评估大模型输出，并在人工智能应用中持续优化交互效果。".repeat(2));
    }

    private String structuredJson(String nameEvidence, String fieldEvidence) {
        return """
                {
                  "classification":"EMERGING_ROLE","domainAllowed":true,"domain":"人工智能",
                  "name":{"value":"提示词工程师","evidenceIds":%s},
                  "definition":{"value":"负责提示策略设计与评测的人工智能岗位","evidenceIds":%s},
                  "responsibilities":[{"value":"设计并评测提示策略","evidenceIds":%s}],
                  "requiredSkills":[{"value":"大语言模型","evidenceIds":%s}],
                  "bonusSkills":[],"scenarios":[{"value":"AI应用优化","evidenceIds":%s}]
                }
                """.formatted(nameEvidence, fieldEvidence, fieldEvidence, fieldEvidence, fieldEvidence);
    }

    private String structuredResult(String name) {
        return """
                {
                  "classification":"EMERGING_ROLE","domainAllowed":true,"domain":"人工智能",
                  "name":{"value":"%s","evidenceIds":["E1"]},
                  "definition":{"value":"负责机器学习系统设计与实现","evidenceIds":["E1"]},
                  "responsibilities":[{"value":"设计机器学习模型","evidenceIds":["E1"]}],
                  "requiredSkills":[{"value":"机器学习","evidenceIds":["E1"]}],
                  "bonusSkills":[],"scenarios":[{"value":"智能系统开发","evidenceIds":["E1"]}]
                }
                """.formatted(name);
    }
}
