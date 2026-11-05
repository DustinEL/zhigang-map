package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmergingDiscoveryPolicyTest {
    private final EmergingDiscoveryPolicy policy = new EmergingDiscoveryPolicy(new ObjectMapper());

    @Test
    void scoreUsesVersionedPolicyMetrics() {
        double weak = policy.score(Map.of(
                "companySupport", 0.2, "sourceSupport", 0.5, "windowGrowth", 0.2,
                "titleNovelty", 0.1, "responsibilityDifference", 0.1, "skillCombinationDifference", 0.1));
        double strong = policy.score(Map.of(
                "companySupport", 1.0, "sourceSupport", 1.0, "windowGrowth", 0.8,
                "titleNovelty", 0.9, "responsibilityDifference", 0.9, "skillCombinationDifference", 0.9));

        assertThat(policy.version()).isEqualTo("emerging-discovery-v1.2.2");
        assertThat(policy.semanticProfileVersion()).isEqualTo("reviewed-title-responsibility-skill-equal-v2");
        assertThat(policy.hash()).hasSize(64);
        assertThat(strong).isGreaterThan(weak).isGreaterThan(policy.candidateThreshold());
    }

    @Test
    void gatesMatchAuditedDefaults() {
        assertThat(policy.candidateGate().minimumDocuments()).isEqualTo(5);
        assertThat(policy.candidateGate().minimumMedianProbability()).isEqualTo(0.60);
        assertThat(policy.publishGate().minimumDocuments()).isEqualTo(8);
        assertThat(policy.publishGate().minimumCompaniesPerWindow()).isEqualTo(2);
        assertThat(policy.clustering().minimumClusterSize()).isEqualTo(5);
        assertThat(policy.existingRoleSimilarityThreshold()).isEqualTo(0.88);
    }
}
