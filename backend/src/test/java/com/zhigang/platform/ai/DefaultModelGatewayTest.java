package com.zhigang.platform.ai;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultModelGatewayTest {
    private final GenerationModelClient modelClient = mock(GenerationModelClient.class);
    private final DefaultModelGateway gateway = new DefaultModelGateway(modelClient);

    @Test
    void delegatesStructuredGenerationThroughGenericClient() {
        when(modelClient.generateJson(anyString(), anyString())).thenReturn(Optional.of("{}"));

        assertThat(gateway.generateStructured("岗位提取", "证据正文", "{}")).contains("{}");
    }

    @Test
    void usesRuleFallbackWhenNoModelIsAvailable() {
        when(modelClient.available()).thenReturn(false);

        assertThat(gateway.mode()).isEqualTo("RULE_EVIDENCE_FALLBACK");
    }

    @Test
    void identifiesSparkOnlyForSparkProvider() {
        when(modelClient.available()).thenReturn(true);
        when(modelClient.provider()).thenReturn("spark");

        assertThat(gateway.mode()).isEqualTo("IFLYTEK_SPARK");
    }

    @Test
    void identifiesAnyAvailableNonSparkProviderAsLocalQwen() {
        when(modelClient.available()).thenReturn(true);
        when(modelClient.provider()).thenReturn("local");

        assertThat(gateway.mode()).isEqualTo("LOCAL_QWEN");
    }
}
