package com.zhigang.platform.analysis;

import com.zhigang.platform.ai.ModelGateway;
import com.zhigang.platform.api.Requests.EvidenceExplanationRequest;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.quality.DataQualityService;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import com.zhigang.platform.reporting.JobAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceExplanationServiceTest {
    private EvidenceExplanationService service;

    @BeforeEach
    void setUp() {
        DemoPlatformDataProvider provider = new DemoPlatformDataProvider();
        SkillNormalizationService normalization = new SkillNormalizationService(provider);
        JobAnalysisService analysis = new JobAnalysisService(provider, normalization,
                new DataQualityService(provider, 180, "v1.0.0"), 8, 5, 2);
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Optional<String> generateStructured(String task, String sourceText, String jsonSchema) {
                return Optional.empty();
            }

            @Override
            public String explain(String question, String evidenceText) {
                return "基于已登记岗位原文生成的受控解释。";
            }

            @Override
            public String mode() {
                return "TEST";
            }
        };
        service = new EvidenceExplanationService(analysis, provider, gateway);
    }

    @Test
    void explainsOnlyWhenMatchingEvidenceExists() {
        var result = service.explain(new EvidenceExplanationRequest("SKILL_REQUIREMENT", 1L,
                "Python", "2026.07", null));

        assertThat(result.refused()).isFalse();
        assertThat(result.evidence()).isNotEmpty().allMatch(item -> item.quote().contains("Python"));
        assertThat(result.metadata().evidenceAvailable()).isTrue();
    }

    @Test
    void refusesUnsupportedOrEvidenceFreeQuestions() {
        var refused = service.explain(new EvidenceExplanationRequest("SKILL_REQUIREMENT", 1L,
                "不存在的技能", "2026.07", null));

        assertThat(refused.refused()).isTrue();
        assertThat(refused.evidence()).isEmpty();
        assertThat(refused.metadata().evidenceAvailable()).isFalse();
        assertThatThrownBy(() -> service.explain(new EvidenceExplanationRequest("OPEN_CHAT", 1L,
                "Python", "2026.07", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只支持");
    }
}
