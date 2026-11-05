package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.ai.ModelGateway;
import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EmergingRoleDefinitionGeneratorTest {
    private static final String TEXT = "推理加速工程师负责优化大模型推理吞吐，建设高性能推理服务，应用于模型服务平台。";
    private static final RawDocument DOCUMENT = new RawDocument(7, "RECRUITMENT_JD", "企业官网", "AUTHORIZED",
            "甲公司", "北京", "AI", "高级", "推理加速工程师", "", TEXT, null, "hash", false);

    @Test
    void acceptsOnlySchemaOutputWithExactSourceAnchors() {
        String response = """
                {"name":{"value":"大模型推理优化工程师","documentId":7,"startOffset":0,"endOffset":6,"evidenceQuote":"推理加速工程"},
                 "responsibilities":[{"value":"优化大模型推理吞吐","documentId":7,"startOffset":9,"endOffset":18,"evidenceQuote":"优化大模型推理吞吐"}],
                 "requiredSkills":[{"value":"大模型推理","documentId":7,"startOffset":11,"endOffset":16,"evidenceQuote":"大模型推理"}],
                 "bonusSkills":[],
                 "scenarios":[{"value":"模型服务平台","documentId":7,"startOffset":32,"endOffset":38,"evidenceQuote":"模型服务平台"}]}
                """;
        EmergingRoleDefinitionGenerator generator = new EmergingRoleDefinitionGenerator(gateway(response), new ObjectMapper());

        var result = generator.generate(List.of(DOCUMENT));

        assertThat(result.accepted()).isTrue();
        assertThat(result.name()).isEqualTo("大模型推理优化工程师");
        assertThat(result.evidence()).allMatch(item -> item.anchorValid() && item.documentId() == 7);
    }

    @Test
    void rejectsModelOutputWhenOffsetDoesNotMatchSource() {
        String response = """
                {"name":{"value":"大模型推理优化工程师","documentId":7,"startOffset":0,"endOffset":6,"evidenceQuote":"错误引用"},
                 "responsibilities":[],"requiredSkills":[],"bonusSkills":[],"scenarios":[]}
                """;
        EmergingRoleDefinitionGenerator generator = new EmergingRoleDefinitionGenerator(gateway(response), new ObjectMapper());

        assertThat(generator.generate(List.of(DOCUMENT)).accepted()).isFalse();
    }

    @Test
    void waitsInsteadOfInventingDefinitionWhenModelUnavailable() {
        EmergingRoleDefinitionGenerator generator = new EmergingRoleDefinitionGenerator(gateway(null), new ObjectMapper());

        var result = generator.generate(List.of(DOCUMENT));

        assertThat(result.accepted()).isFalse();
        assertThat(result.mode()).isEqualTo("WAITING_MODEL");
        assertThat(result.name()).isBlank();
    }

    @Test
    void derivesOffsetsFromAUniqueVerbatimQuoteInsteadOfTrustingModelOffsets() {
        String text = "Inference acceleration engineer designs high throughput model serving systems for production.";
        RawDocument document = new RawDocument(9, "RECRUITMENT_JD", "enterprise", "AUTHORIZED", "company", "city",
                "AI", "senior", "Inference acceleration engineer", "", text, null, "hash-9", false);
        String response = """
                {"name":{"value":"Inference acceleration engineer","documentId":9,"evidenceQuote":"Inference acceleration engineer designs high throughput"},
                 "responsibilities":[{"value":"Optimize serving throughput","documentId":9,"evidenceQuote":"designs high throughput model serving systems for production"}],
                 "requiredSkills":[{"value":"Model serving","documentId":9,"evidenceQuote":"high throughput model serving systems for production"}],
                 "bonusSkills":[],
                 "scenarios":[{"value":"Production serving","documentId":9,"evidenceQuote":"model serving systems for production"}]}
                """;

        var result = new EmergingRoleDefinitionGenerator(gateway(response), new ObjectMapper()).generate(List.of(document));

        assertThat(result.accepted()).isTrue();
        assertThat(result.evidence()).allMatch(item -> item.startOffset() >= 0
                && text.substring(item.startOffset(), item.endOffset()).equals(item.quote()));
    }

    @Test
    void acceptsJsonWrappedInMarkdownFenceWithoutRelaxingEvidenceChecks() {
        String text = "Designs reliable inference serving systems for production workloads.";
        RawDocument document = new RawDocument(10, "RECRUITMENT_JD", "enterprise", "AUTHORIZED", "company", "city",
                "AI", "senior", "Inference platform engineer", "", text, null, "hash-10", false);
        String response = """
                Here is the requested JSON:
                ```json
                {"name":{"value":"Inference platform engineer","documentId":10,"evidenceQuote":"Designs reliable inference serving systems"},
                 "responsibilities":[{"value":"Design serving systems","documentId":10,"evidenceQuote":"Designs reliable inference serving systems"}],
                 "requiredSkills":[{"value":"Inference serving","documentId":10,"evidenceQuote":"inference serving systems for production workloads"}],
                 "bonusSkills":[],
                 "scenarios":[{"value":"Production workloads","documentId":10,"evidenceQuote":"for production workloads"}]}
                ```
                """;

        var result = new EmergingRoleDefinitionGenerator(gateway(response), new ObjectMapper()).generate(List.of(document));

        assertThat(result.accepted()).isTrue();
        assertThat(result.evidence()).allMatch(item -> item.anchorValid());
    }

    @Test
    void resolvesModelSelectedEvidenceIdsToServerVerifiedOffsets() {
        String text = "Builds scalable model inference services for production AI platforms.";
        RawDocument document = new RawDocument(11, "RECRUITMENT_JD", "enterprise", "AUTHORIZED", "company", "city",
                "AI", "senior", "Model inference engineer", "", text, null, "hash-11", false);
        String response = """
                {"name":{"value":"Model inference engineer","evidenceId":"E1"},
                 "responsibilities":[{"value":"Build inference services","evidenceId":"E1"}],
                 "requiredSkills":[{"value":"Scalable systems","evidenceId":"E1"}],
                 "bonusSkills":[],
                 "scenarios":[{"value":"Production AI platforms","evidenceId":"E1"}]}
                """;

        var result = new EmergingRoleDefinitionGenerator(gateway(response), new ObjectMapper()).generate(List.of(document));

        assertThat(result.accepted()).isTrue();
        assertThat(result.evidence()).allMatch(item -> item.documentId() == 11
                && text.substring(item.startOffset(), item.endOffset()).equals(item.quote()));
    }

    @Test
    void rejectsUnknownEvidenceIdsWithoutPersistingModelText() {
        String text = "Builds scalable model inference services for production AI platforms.";
        RawDocument document = new RawDocument(12, "RECRUITMENT_JD", "enterprise", "AUTHORIZED", "company", "city",
                "AI", "senior", "Model inference engineer", "", text, null, "hash-12", false);
        String response = """
                {"name":{"value":"Model inference engineer","evidenceId":"UNKNOWN"},
                 "responsibilities":[{"value":"Build inference services","evidenceId":"E1"}],
                 "requiredSkills":[{"value":"Scalable systems","evidenceId":"E1"}],
                 "bonusSkills":[],
                 "scenarios":[{"value":"Production AI platforms","evidenceId":"E1"}]}
                """;

        var result = new EmergingRoleDefinitionGenerator(gateway(response), new ObjectMapper()).generate(List.of(document));

        assertThat(result.accepted()).isFalse();
        assertThat(result.mode()).isEqualTo("INVALID_EVIDENCE_QUOTE_MISSING");
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void keepsOnlyCanonicalSkillNamesAndRejectsResponsibilitySentences() {
        String text = "Requires Kubernetes and model-serving experience for production inference systems.";
        RawDocument document = new RawDocument(13, "RECRUITMENT_JD", "enterprise", "AUTHORIZED", "company", "city",
                "AI", "senior", "Inference platform engineer", "", text, null, "hash-13", false);
        String response = """
                {"name":{"value":"Inference platform engineer","evidenceId":"E1"},
                 "responsibilities":[{"value":"Build production inference systems","evidenceId":"E1"}],
                 "requiredSkills":[
                   {"value":"Build and optimize scalable inference services for production","evidenceId":"E1"},
                   {"value":"K8s","evidenceId":"E1"}],
                 "bonusSkills":[{"value":"Own service reliability and release delivery","evidenceId":"E1"}],
                 "scenarios":[{"value":"Production inference","evidenceId":"E1"}]}
                """;

        var result = generator(response).generate(List.of(document));

        assertThat(result.accepted()).isTrue();
        assertThat(result.requiredSkills()).containsExactly("Kubernetes");
        assertThat(result.bonusSkills()).isEmpty();
    }

    private ModelGateway gateway(String response) {
        return new ModelGateway() {
            @Override public Optional<String> generateStructured(String task, String sourceText, String jsonSchema) {
                return Optional.ofNullable(response);
            }
            @Override public String explain(String question, String evidenceText) { return ""; }
            @Override public String mode() { return response == null ? "OFFLINE" : "TEST"; }
        };
    }

    private EmergingRoleDefinitionGenerator generator(String response) {
        return new EmergingRoleDefinitionGenerator(gateway(response), new ObjectMapper(),
                new SkillNormalizationService(new DemoPlatformDataProvider()));
    }
}
