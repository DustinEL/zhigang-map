package com.zhigang.platform.quality;

import com.zhigang.platform.domain.PlatformModels.QualityReport;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DataQualityServiceTest {
    private final DemoPlatformDataProvider provider = new DemoPlatformDataProvider();
    private final DataQualityService service = new DataQualityService(provider, 180, "test-policy");

    @Test
    void timeWeightUsesConfiguredHalfLife() {
        OffsetDateTime reference = OffsetDateTime.of(2026, 7, 10, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThat(service.timeWeight(reference.minusDays(180), reference)).isCloseTo(0.5, within(0.0001));
        assertThat(service.timeWeight(reference, reference)).isEqualTo(1.0);
    }

    @Test
    void nearDuplicateDetectionClustersIdenticalDocuments() {
        RawDocument source = provider.findDocuments().stream()
                .filter(document -> document.sourceType().equals("RECRUITMENT_JD"))
                .findFirst()
                .orElseThrow();
        RawDocument duplicate = new RawDocument(
                9999,
                source.sourceType(),
                source.sourceName(),
                source.licenseStatus(),
                "另一家企业",
                source.city(),
                source.industry(),
                source.level(),
                source.jobTitle(),
                source.salary(),
                source.content(),
                source.publishedAt(),
                source.contentHash(),
                true
        );

        DataQualityService.DuplicateAnalysis analysis = service.detectNearDuplicates(List.of(source, duplicate), 0.85);

        assertThat(analysis.clusters()).hasSize(1);
        assertThat(analysis.duplicateMembers()).isEqualTo(1);
    }

    @Test
    void qualityReportExposesVersionedPublishingGate() {
        QualityReport report = service.buildReport("snapshot-test");

        assertThat(report.policyVersion()).isEqualTo("test-policy");
        assertThat(report.metrics()).extracting(metric -> metric.code()).contains("EVIDENCE_COVERAGE", "PUBLISH_TIME_COMPLETENESS");
        assertThat(report.issues()).anyMatch(issue -> issue.type().equals("PROMPT_INJECTION"));
    }
}
