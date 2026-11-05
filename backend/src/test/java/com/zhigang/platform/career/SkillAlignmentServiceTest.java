package com.zhigang.platform.career;

import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.domain.PlatformModels.AbilityEvidence;
import com.zhigang.platform.emerging.EmbeddingClient;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillAlignmentServiceTest {
    private final SkillNormalizationService normalization = new SkillNormalizationService(new DemoPlatformDataProvider());

    @Test
    void usesExactAliasBeforeSemanticSimilarity() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        SkillAlignmentService service = new SkillAlignmentService(normalization, embedding);

        var result = service.align(List.of("Kubernetes"), List.of(
                new AbilityEvidence("K8s", 2, "L2 实践", "项目使用 K8s", "PROJECT", .95)
        ));

        assertThat(result.get("Kubernetes").method()).isEqualTo("ALIAS");
    }

    @Test
    void marksHighSemanticSimilarityAsManuallyConfirmedAfterProfileConfirmation() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.available()).thenReturn(true);
        when(embedding.embed(List.of("Kubernetes", "容器编排平台")))
                .thenReturn(List.of(List.of(1.0, 0.0), List.of(.98, .08)));
        SkillAlignmentService service = new SkillAlignmentService(normalization, embedding);

        var result = service.align(List.of("Kubernetes"), List.of(
                new AbilityEvidence("容器编排平台", 2, "L2 实践", "项目使用容器编排平台", "PROJECT", .90)
        ));

        assertThat(result.get("Kubernetes").method()).isEqualTo("SEMANTIC_CONFIRMED");
        assertThat(result.get("Kubernetes").similarity()).isGreaterThan(.82);
        assertThat(result.get("Kubernetes").manualConfirmed()).isTrue();
    }
}
