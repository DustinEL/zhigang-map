package com.zhigang.platform.evaluation;

import com.zhigang.platform.api.Requests.ClassificationEvaluationRequest;
import com.zhigang.platform.api.Requests.ExtractionEvaluationItem;
import com.zhigang.platform.api.Requests.ExtractionEvaluationRequest;
import com.zhigang.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationServiceTest {
    private final EvaluationService service = new EvaluationService();

    @Test
    void classificationReturnsAccuracyMacroF1AndDeterministicInterval() {
        var request = new ClassificationEvaluationRequest(
                List.of("READY", "READY", "GAP", "GAP", "GAP"),
                List.of("READY", "GAP", "GAP", "GAP", "READY"),
                500
        );

        var metrics = service.classification(request);

        assertThat(metrics.accuracy()).isEqualTo(0.6);
        assertThat(metrics.macroF1()).isBetween(0.58, 0.59);
        assertThat(metrics.confusionMatrix().get("READY").get("GAP")).isEqualTo(1);
        assertThat(metrics.accuracy95Interval().lower()).isLessThanOrEqualTo(metrics.accuracy());
        assertThat(metrics.accuracy95Interval().upper()).isGreaterThanOrEqualTo(metrics.accuracy());
    }

    @Test
    void extractionReturnsMicroPrecisionRecallAndF1() {
        var request = new ExtractionEvaluationRequest(List.of(
                new ExtractionEvaluationItem(Set.of("Java", "MySQL"), Set.of("Java", "Redis")),
                new ExtractionEvaluationItem(Set.of("RAG"), Set.of("RAG"))
        ));

        var metrics = service.extraction(request);

        assertThat(metrics.truePositive()).isEqualTo(2);
        assertThat(metrics.falsePositive()).isEqualTo(1);
        assertThat(metrics.falseNegative()).isEqualTo(1);
        assertThat(metrics.f1()).isEqualTo(0.6667);
    }

    @Test
    void rejectsDifferentClassificationListSizes() {
        assertThatThrownBy(() -> service.classification(new ClassificationEvaluationRequest(
                List.of("A", "B"), List.of("A", "B", "C"), 100
        ))).isInstanceOf(BusinessException.class);
    }
}
