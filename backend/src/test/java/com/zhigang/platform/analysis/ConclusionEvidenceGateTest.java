package com.zhigang.platform.analysis;

import com.zhigang.platform.domain.PlatformModels.Evidence;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConclusionEvidenceGateTest {
    @Test
    void requiresConfiguredIndependentCompaniesAndSources() {
        List<Evidence> evidence = List.of(
                evidence(1, "来源A", "企业甲"),
                evidence(2, "来源A", "企业乙")
        );

        assertThat(new ConclusionEvidenceGate(2, 1).evaluate(evidence).passed()).isTrue();
        var rejected = new ConclusionEvidenceGate(2, 2).evaluate(evidence);
        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.message()).isEqualTo("证据不足");
    }

    private Evidence evidence(long id, String source, String company) {
        return new Evidence(id, source, company, OffsetDateTime.now(), "必须掌握 RAG", 0, 8,
                1.0, "EVIDENCE_VALID");
    }
}
