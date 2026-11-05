package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmergingDiscoveryExecutorTest {
    @Test
    void combinesSemanticViewsWithEqualWeightAndUnitLength() {
        List<Double> combined = EmergingDiscoveryExecutor.combineViews(List.of(
                List.of(2.0, 0.0), List.of(0.0, 3.0), List.of(1.0, 1.0)));

        double norm = Math.sqrt(combined.stream().mapToDouble(value -> value * value).sum());
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(combined.get(0)).isEqualTo(combined.get(1));
    }

    @Test
    void semanticCacheKeyChangesWithProfileContentAndVersion() {
        var profile = new EmergingDiscoveryExecutor.SemanticProfile("标题", "职责", "技能");

        assertThat(EmergingDiscoveryExecutor.semanticHash(profile, "v1"))
                .hasSize(64)
                .isNotEqualTo(EmergingDiscoveryExecutor.semanticHash(profile, "v2"))
                .isNotEqualTo(EmergingDiscoveryExecutor.semanticHash(
                        new EmergingDiscoveryExecutor.SemanticProfile("标题", "职责变化", "技能"), "v1"));
    }

    @Test
    void rejectsInconsistentSemanticViewDimensions() {
        assertThatThrownBy(() -> EmergingDiscoveryExecutor.combineViews(List.of(
                List.of(1.0, 0.0), List.of(1.0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void onlyMarksCandidatesReadyForAutomaticPublicationWhenEveryReleaseGatePasses() {
        EmergingDiscoveryPolicy policy = new EmergingDiscoveryPolicy(new ObjectMapper());
        Map<String, Double> supportedWindows = Map.of("previousWindowCompanies", 2.0, "currentWindowCompanies", 2.0);

        assertThat(EmergingDiscoveryExecutor.meetsAutomaticPublicationGate(policy, 8, 5, 2,
                policy.publishThreshold(), supportedWindows)).isTrue();
        assertThat(EmergingDiscoveryExecutor.meetsAutomaticPublicationGate(policy, 8, 5, 2,
                policy.publishThreshold(), Map.of("previousWindowCompanies", 1.0, "currentWindowCompanies", 2.0))).isFalse();
    }
}
