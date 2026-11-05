package com.zhigang.platform.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

final class ResumeSkillBlindEvaluationSupport {
    private ResumeSkillBlindEvaluationSupport() {
    }

    static Metrics aggregate(List<Counts> samples) {
        int truePositives = samples.stream().mapToInt(Counts::truePositives).sum();
        int falsePositives = samples.stream().mapToInt(Counts::falsePositives).sum();
        int falseNegatives = samples.stream().mapToInt(Counts::falseNegatives).sum();
        double precision = ratio(truePositives, truePositives + falsePositives);
        double recall = ratio(truePositives, truePositives + falseNegatives);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new Metrics(truePositives, falsePositives, falseNegatives, precision, recall, f1);
    }

    static String runKind(String value) {
        String runKind = value == null || value.isBlank() ? "FIRST_BLIND" : value.trim();
        if (!runKind.equals("FIRST_BLIND") && !runKind.equals("REGRESSION_TUNING")) {
            throw new IllegalArgumentException("unsupported evaluation run kind: " + runKind);
        }
        return runKind;
    }

    static ConfidenceIntervals bootstrap(List<Counts> samples, int iterations, long seed) {
        if (samples.isEmpty()) {
            return new ConfidenceIntervals(new double[]{0, 0}, new double[]{0, 0}, new double[]{0, 0});
        }
        Random random = new Random(seed);
        double[] precision = new double[iterations];
        double[] recall = new double[iterations];
        double[] f1 = new double[iterations];
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<Counts> sample = new ArrayList<>(samples.size());
            for (int index = 0; index < samples.size(); index++) {
                sample.add(samples.get(random.nextInt(samples.size())));
            }
            Metrics metrics = aggregate(sample);
            precision[iteration] = metrics.precision();
            recall[iteration] = metrics.recall();
            f1[iteration] = metrics.f1();
        }
        return new ConfidenceIntervals(interval(precision), interval(recall), interval(f1));
    }

    static String classify(Metrics metrics, double minimumSampleRecall, boolean parityPassed,
                           boolean integrityFailed, ConfidenceIntervals intervals) {
        if (integrityFailed) return "INVALID";
        boolean hardGate = metrics.precision() >= 0.90 && metrics.recall() >= 0.90 && metrics.f1() >= 0.90
                && minimumSampleRecall >= 0.80 && parityPassed;
        if (!hardGate) return "FAIL";
        boolean strong = intervals.precision()[0] >= 0.90
                && intervals.recall()[0] >= 0.90
                && intervals.f1()[0] >= 0.90;
        return strong ? "STRONG_PASS" : "STAGE_PASS";
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double[] interval(double[] values) {
        Arrays.sort(values);
        int lower = Math.max(0, (int) Math.floor((values.length - 1) * 0.025));
        int upper = Math.min(values.length - 1, (int) Math.ceil((values.length - 1) * 0.975));
        return new double[]{values[lower], values[upper]};
    }

    record Counts(int truePositives, int falsePositives, int falseNegatives) {
    }

    record Metrics(int truePositives, int falsePositives, int falseNegatives,
                   double precision, double recall, double f1) {
    }

    record ConfidenceIntervals(double[] precision, double[] recall, double[] f1) {
    }
}
