package com.zhigang.platform.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeSkillBlindEvaluationSupportTest {
    @Test
    void acceptsOnlyExplicitEvaluationRunKinds() {
        assertThat(ResumeSkillBlindEvaluationSupport.runKind(null)).isEqualTo("FIRST_BLIND");
        assertThat(ResumeSkillBlindEvaluationSupport.runKind("REGRESSION_TUNING"))
                .isEqualTo("REGRESSION_TUNING");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ResumeSkillBlindEvaluationSupport.runKind("FIRST_BLNID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported evaluation run kind");
    }

    @Test
    void aggregatesMicroCountsInsteadOfAveragingSamplePercentages() {
        var first = new ResumeSkillBlindEvaluationSupport.Counts(9, 1, 1);
        var second = new ResumeSkillBlindEvaluationSupport.Counts(1, 9, 0);

        var metrics = ResumeSkillBlindEvaluationSupport.aggregate(List.of(first, second));

        assertThat(metrics.truePositives()).isEqualTo(10);
        assertThat(metrics.falsePositives()).isEqualTo(10);
        assertThat(metrics.falseNegatives()).isEqualTo(1);
        assertThat(metrics.precision()).isEqualTo(0.5);
        assertThat(metrics.recall()).isEqualTo(10.0 / 11.0);
    }

    @Test
    void exactThresholdsPassAndConfidenceIntervalControlsStrength() {
        var metrics = new ResumeSkillBlindEvaluationSupport.Metrics(90, 10, 10, 0.90, 0.90, 0.90);
        var weakInterval = new ResumeSkillBlindEvaluationSupport.ConfidenceIntervals(
                new double[]{0.89, 0.95}, new double[]{0.90, 0.96}, new double[]{0.90, 0.96});
        var strongInterval = new ResumeSkillBlindEvaluationSupport.ConfidenceIntervals(
                new double[]{0.90, 0.95}, new double[]{0.90, 0.96}, new double[]{0.90, 0.96});

        assertThat(ResumeSkillBlindEvaluationSupport.classify(metrics, 0.80, true, false, weakInterval))
                .isEqualTo("STAGE_PASS");
        assertThat(ResumeSkillBlindEvaluationSupport.classify(metrics, 0.80, true, false, strongInterval))
                .isEqualTo("STRONG_PASS");
    }

    @Test
    void integrityFailureIsInvalidAndMetricFailureIsFail() {
        var metrics = new ResumeSkillBlindEvaluationSupport.Metrics(89, 11, 11, 0.89, 0.89, 0.89);
        var intervals = new ResumeSkillBlindEvaluationSupport.ConfidenceIntervals(
                new double[]{0.89, 0.89}, new double[]{0.89, 0.89}, new double[]{0.89, 0.89});

        assertThat(ResumeSkillBlindEvaluationSupport.classify(metrics, 1.0, true, true, intervals))
                .isEqualTo("INVALID");
        assertThat(ResumeSkillBlindEvaluationSupport.classify(metrics, 1.0, true, false, intervals))
                .isEqualTo("FAIL");
        assertThat(ResumeSkillBlindEvaluationSupport.classify(metrics, 0.79, true, false, intervals))
                .isEqualTo("FAIL");
        assertThat(ResumeSkillBlindEvaluationSupport.classify(metrics, 1.0, false, false, intervals))
                .isEqualTo("FAIL");
    }
}
