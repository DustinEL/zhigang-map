package com.zhigang.platform.reporting;

import com.zhigang.platform.domain.PlatformModels.EvaluationSummary;
import com.zhigang.platform.domain.PlatformModels.RepositoryOverview;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformInsightsServiceTest {
    private final PlatformInsightsService service = new PlatformInsightsService(new DemoPlatformDataProvider());

    @Test
    void keepsDynamicDataSeparateFromBaseLibrary() {
        RepositoryOverview overview = service.repositoryOverview();

        assertThat(overview.baseLibrary().recordCount()).isPositive();
        assertThat(overview.dynamicLibrary().status()).isEqualTo("DEMO_PIPELINE");
        assertThat(overview.pipeline()).hasSize(6);
        assertThat(overview.promotion().promoted()).isLessThanOrEqualTo(overview.promotion().candidates());
        assertThat(overview.demoData()).isTrue();
    }

    @Test
    void doesNotPublishScoresWithoutFrozenData() {
        ReflectionTestUtils.setField(service, "configuredReportPath", "build/missing-evaluation-report.json");
        EvaluationSummary summary = service.evaluationSummary();

        assertThat(summary.metrics()).hasSize(3).allSatisfy(metric -> {
            assertThat(metric.target()).isEqualTo(90);
            assertThat(metric.value()).isNull();
            assertThat(metric.status()).isEqualTo("AWAITING_DATA");
        });
        assertThat(summary.datasets()).allSatisfy(dataset ->
                assertThat(dataset.status()).isEqualTo("AWAITING_ANNOTATION"));
        assertThat(summary.demoData()).isTrue();
    }
}
