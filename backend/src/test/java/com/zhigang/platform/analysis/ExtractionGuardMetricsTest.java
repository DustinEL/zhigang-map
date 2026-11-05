package com.zhigang.platform.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionGuardMetricsTest {
    @Test
    void zeroSampleMetricsAreReportedAsZero() {
        var metrics = new ExtractionGuardMetrics().snapshot();

        assertThat(metrics.evidenceCoverageRate()).isZero();
        assertThat(metrics.citationConsistencyRate()).isZero();
        assertThat(metrics.unsupportedRefusalRate()).isZero();
        assertThat(metrics.falseAcceptanceRate()).isZero();
        assertThat(metrics.promptInjectionBlockRate()).isZero();
    }

    @Test
    void observedRatesKeepTheirOriginalMeaning() {
        ExtractionGuardMetrics guard = new ExtractionGuardMetrics();

        guard.record(new ExtractionGuardMetrics.Observation(2, 2, 2, 2,
                false, false, false, false));
        var metrics = guard.record(new ExtractionGuardMetrics.Observation(0, 0, 0, 0,
                true, true, true, true));

        assertThat(metrics.evidenceCoverageRate()).isEqualTo(1.0);
        assertThat(metrics.citationConsistencyRate()).isEqualTo(1.0);
        assertThat(metrics.unsupportedRefusalRate()).isEqualTo(1.0);
        assertThat(metrics.falseAcceptanceRate()).isZero();
        assertThat(metrics.promptInjectionBlockRate()).isEqualTo(1.0);
    }
}
