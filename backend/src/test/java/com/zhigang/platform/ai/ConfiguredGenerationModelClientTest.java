package com.zhigang.platform.ai;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConfiguredGenerationModelClientTest {
    private final GenerationModelClient local = mock(GenerationModelClient.class);
    private final GenerationModelClient spark = mock(GenerationModelClient.class);

    @Test
    void localProviderDelegatesOnlyToLocalModel() {
        when(local.generate("s", "u")).thenReturn(Optional.of("local-result"));
        ConfiguredGenerationModelClient router = router("LOCAL");

        assertThat(router.generate("s", "u")).contains("local-result");
        verify(local).generate("s", "u");
        verifyNoInteractions(spark);
    }

    @Test
    void localEmptyResultDoesNotFallBackToSpark() {
        when(local.generateJson("s", "u")).thenReturn(Optional.empty());
        ConfiguredGenerationModelClient router = router("local");

        assertThat(router.generateJson("s", "u")).isEmpty();
        verify(local).generateJson("s", "u");
        verify(spark, never()).generateJson("s", "u");
    }

    @Test
    void sparkProviderDelegatesOnlyToSparkModel() {
        when(spark.generateJson("s", "u")).thenReturn(Optional.of("spark-result"));
        ConfiguredGenerationModelClient router = router("spark");

        assertThat(router.generateJson("s", "u")).contains("spark-result");
        verify(spark).generateJson("s", "u");
        verifyNoInteractions(local);
    }

    @Test
    void rulesProviderNeverInvokesGenerationModels() {
        ConfiguredGenerationModelClient router = router("rules");

        assertThat(router.generate("s", "u")).isEmpty();
        assertThat(router.generateJson("s", "u")).isEmpty();
        assertThat(router.available()).isFalse();
        assertThat(router.model()).isEqualTo("rules");
        assertThat(router.provider()).isEqualTo("rules");
        verifyNoInteractions(local, spark);
    }

    @Test
    void selectedProviderExposesSelectedModelMetadata() {
        when(local.available()).thenReturn(true);
        when(local.model()).thenReturn("qwen-local");
        ConfiguredGenerationModelClient router = router("local");

        assertThat(router.available()).isTrue();
        assertThat(router.model()).isEqualTo("qwen-local");
        assertThat(router.provider()).isEqualTo("local");
        verifyNoInteractions(spark);
    }

    @Test
    void unknownProviderFailsFastWithConfigurationHint() {
        assertThatThrownBy(() -> router("paid-provider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI_PROVIDER");
        verifyNoInteractions(local, spark);
    }

    private ConfiguredGenerationModelClient router(String provider) {
        return new ConfiguredGenerationModelClient(provider, local, spark);
    }
}
