package com.zhigang.platform.evaluation;

import com.zhigang.platform.api.Requests.ClassificationEvaluationRequest;
import com.zhigang.platform.api.Requests.ExtractionEvaluationItem;
import com.zhigang.platform.api.Requests.ExtractionEvaluationRequest;
import com.zhigang.platform.common.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service
public class EvaluationService {
    private static final long BOOTSTRAP_SEED = 20260710L;

    public ClassificationMetrics classification(ClassificationEvaluationRequest request) {
        List<String> expected = request.expectedLabels();
        List<String> predicted = request.predictedLabels();
        if (expected.size() != predicted.size()) {
            throw new BusinessException("EVALUATION_SIZE_MISMATCH", "期望标签与预测标签数量必须一致");
        }
        Set<String> labels = new LinkedHashSet<>(expected);
        labels.addAll(predicted);
        Map<String, Map<String, Integer>> confusion = confusionMatrix(labels, expected, predicted);
        double accuracy = accuracy(expected, predicted);
        double macroF1 = macroF1(labels, confusion);
        ConfidenceInterval interval = bootstrapAccuracy(expected, predicted, request.bootstrapSamples());
        return new ClassificationMetrics(expected.size(), round(accuracy), round(macroF1), confusion, interval, "v1.0.0");
    }

    public ExtractionMetrics extraction(ExtractionEvaluationRequest request) {
        long truePositive = 0;
        long falsePositive = 0;
        long falseNegative = 0;
        for (ExtractionEvaluationItem item : request.items()) {
            Set<String> expected = normalized(item.expected());
            Set<String> predicted = normalized(item.predicted());
            for (String value : predicted) {
                if (expected.contains(value)) truePositive++; else falsePositive++;
            }
            for (String value : expected) {
                if (!predicted.contains(value)) falseNegative++;
            }
        }
        double precision = divide(truePositive, truePositive + falsePositive);
        double recall = divide(truePositive, truePositive + falseNegative);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new ExtractionMetrics(request.items().size(), truePositive, falsePositive, falseNegative,
                round(precision), round(recall), round(f1), "v1.0.0");
    }

    private Map<String, Map<String, Integer>> confusionMatrix(Set<String> labels, List<String> expected, List<String> predicted) {
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        labels.forEach(actual -> {
            Map<String, Integer> row = new LinkedHashMap<>();
            labels.forEach(label -> row.put(label, 0));
            matrix.put(actual, row);
        });
        for (int index = 0; index < expected.size(); index++) {
            Map<String, Integer> row = matrix.get(expected.get(index));
            String prediction = predicted.get(index);
            row.put(prediction, row.get(prediction) + 1);
        }
        return matrix;
    }

    private double accuracy(List<String> expected, List<String> predicted) {
        int correct = 0;
        for (int index = 0; index < expected.size(); index++) {
            if (expected.get(index).equals(predicted.get(index))) correct++;
        }
        return correct / (double) expected.size();
    }

    private double macroF1(Set<String> labels, Map<String, Map<String, Integer>> matrix) {
        double sum = 0;
        for (String label : labels) {
            int truePositive = matrix.get(label).get(label);
            int falsePositive = matrix.entrySet().stream().filter(entry -> !entry.getKey().equals(label))
                    .mapToInt(entry -> entry.getValue().get(label)).sum();
            int falseNegative = matrix.get(label).entrySet().stream().filter(entry -> !entry.getKey().equals(label))
                    .mapToInt(Map.Entry::getValue).sum();
            double precision = divide(truePositive, truePositive + falsePositive);
            double recall = divide(truePositive, truePositive + falseNegative);
            sum += precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        }
        return sum / labels.size();
    }

    private ConfidenceInterval bootstrapAccuracy(List<String> expected, List<String> predicted, int bootstrapSamples) {
        Random random = new Random(BOOTSTRAP_SEED);
        List<Double> samples = new ArrayList<>(bootstrapSamples);
        for (int iteration = 0; iteration < bootstrapSamples; iteration++) {
            int correct = 0;
            for (int index = 0; index < expected.size(); index++) {
                int selected = random.nextInt(expected.size());
                if (expected.get(selected).equals(predicted.get(selected))) correct++;
            }
            samples.add(correct / (double) expected.size());
        }
        Collections.sort(samples);
        int lowerIndex = Math.max(0, (int) Math.floor(bootstrapSamples * 0.025));
        int upperIndex = Math.min(bootstrapSamples - 1, (int) Math.ceil(bootstrapSamples * 0.975) - 1);
        return new ConfidenceInterval(round(samples.get(lowerIndex)), round(samples.get(upperIndex)), bootstrapSamples, BOOTSTRAP_SEED);
    }

    private Set<String> normalized(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.stream().map(String::trim).filter(value -> !value.isBlank()).forEach(result::add);
        return result;
    }

    private double divide(long numerator, long denominator) {
        return denominator == 0 ? 0 : numerator / (double) denominator;
    }

    private double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    public record ConfidenceInterval(double lower, double upper, int bootstrapSamples, long seed) {
    }

    public record ClassificationMetrics(int sampleCount, double accuracy, double macroF1,
                                        Map<String, Map<String, Integer>> confusionMatrix,
                                        ConfidenceInterval accuracy95Interval, String algorithmVersion) {
    }

    public record ExtractionMetrics(int sampleCount, long truePositive, long falsePositive, long falseNegative,
                                    double precision, double recall, double f1, String algorithmVersion) {
    }
}
