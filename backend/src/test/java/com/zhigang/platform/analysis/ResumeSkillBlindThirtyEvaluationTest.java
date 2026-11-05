package com.zhigang.platform.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhigang.platform.career.ResumeFileService;
import com.zhigang.platform.career.ResumeOcrClient;
import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumeSkillBlindThirtyEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final long BOOTSTRAP_SEED = 20260816L;
    private static final int BOOTSTRAP_ITERATIONS = 10_000;

    @Test
    void frozenBlindDatasetProducesImmutableFirstRunReport() throws Exception {
        String goldProperty = System.getProperty("blind.gold");
        Assumptions.assumeTrue(goldProperty != null && !goldProperty.isBlank(),
                "blind evaluation runs only through the sealed baseline runner");

        Path repositoryRoot = requiredPath("blind.repositoryRoot");
        Path goldPath = requiredPath("blind.gold");
        Path manifestPath = requiredPath("blind.manifest");
        Path baselinePath = requiredPath("blind.baseline");
        Path reportPath = requiredPath("blind.report");
        String runKind = ResumeSkillBlindEvaluationSupport.runKind(System.getProperty("blind.runKind"));
        Instant startedAt = Instant.now();

        Map<String, Object> gold = readJson(goldPath);
        Map<String, Object> manifest = readJson(manifestPath);
        Map<String, Object> baseline = readJson(baselinePath);
        List<String> integrityFailures = validateDataset(repositoryRoot, gold, manifest, baseline);

        ResumeOcrClient ocrClient = mock(ResumeOcrClient.class);
        when(ocrClient.extractPdf(any(byte[].class), anyString()))
                .thenReturn(new ResumeOcrClient.OcrResult("", 0, "TEST_OCR_FALLBACK"));
        ResumeFileService fileService = new ResumeFileService(ocrClient);
        SkillNormalizationService skillService = new SkillNormalizationService(new DemoPlatformDataProvider());

        List<SampleResult> sampleResults = new ArrayList<>();
        for (Map<String, Object> sample : sampleMaps(gold)) {
            sampleResults.add(evaluate(repositoryRoot, sample, fileService, skillService, integrityFailures));
        }
        List<ResumeSkillBlindEvaluationSupport.Counts> counts = sampleResults.stream().map(SampleResult::counts).toList();
        ResumeSkillBlindEvaluationSupport.Metrics overall = ResumeSkillBlindEvaluationSupport.aggregate(counts);
        ResumeSkillBlindEvaluationSupport.ConfidenceIntervals intervals =
                ResumeSkillBlindEvaluationSupport.bootstrap(counts, BOOTSTRAP_ITERATIONS, BOOTSTRAP_SEED);
        double minimumRecall = sampleResults.stream().mapToDouble(SampleResult::recall).min().orElse(0);
        boolean parityPassed = sampleResults.stream().allMatch(result -> result.docxOnly().isEmpty() && result.pdfOnly().isEmpty());
        String status = ResumeSkillBlindEvaluationSupport.classify(
                overall, minimumRecall, parityPassed, !integrityFailures.isEmpty(), intervals);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runKind", runKind);
        report.put("status", status);
        report.put("startedAt", startedAt.toString());
        report.put("finishedAt", Instant.now().toString());
        report.put("datasetSha256", gold.get("datasetSha256"));
        report.put("goldSha256", sha256(goldPath));
        report.put("baselineSourceTreeSha256", baseline.get("sourceTreeSha256"));
        report.put("bootstrap", Map.of("seed", BOOTSTRAP_SEED, "iterations", BOOTSTRAP_ITERATIONS,
                "confidenceLevel", 0.95, "intervals", intervals));
        report.put("overallMicro", overall);
        report.put("overallMacro", macro(sampleResults));
        report.put("categoryMetrics", categoryMetrics(sampleResults));
        report.put("minimumSampleRecall", minimumRecall);
        report.put("docxPdfParityPassed", parityPassed);
        report.put("samples", sampleResults);
        report.put("integrityFailures", integrityFailures);
        report.put("thresholds", Map.of("precision", 0.90, "recall", 0.90, "f1", 0.90,
                "minimumSampleRecall", 0.80, "docxPdfParityRequired", true));
        writeCreateNew(reportPath, report);

        System.out.printf("Blind resume metrics: status=%s precision=%.4f recall=%.4f F1=%.4f TP=%d FP=%d FN=%d%n",
                status, overall.precision(), overall.recall(), overall.f1(), overall.truePositives(),
                overall.falsePositives(), overall.falseNegatives());
        assertThat(status).as("first blind evaluation; report=%s", reportPath)
                .isIn("STRONG_PASS", "STAGE_PASS");
    }

    private Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing system property " + property);
        return Path.of(value).toAbsolutePath().normalize();
    }

    private Map<String, Object> readJson(Path path) throws IOException {
        return JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sampleMaps(Map<String, Object> root) {
        return (List<Map<String, Object>>) root.get("samples");
    }

    private List<String> validateDataset(Path root, Map<String, Object> gold, Map<String, Object> manifest,
                                         Map<String, Object> baseline) throws Exception {
        List<String> failures = new ArrayList<>();
        if (!"FROZEN".equals(gold.get("goldStatus"))) failures.add("goldStatus must be FROZEN");
        int sampleCount = ((Number) manifest.get("sampleCount")).intValue();
        if (sampleCount <= 0) failures.add("manifest sampleCount must be positive");
        if (sampleCount != ((Number) gold.get("sampleCount")).intValue()) failures.add("gold/manifest sampleCount mismatch");
        if (!gold.get("datasetSha256").equals(manifest.get("datasetSha256"))) failures.add("gold/manifest dataset hash mismatch");
        if (!gold.get("datasetSha256").equals(baseline.get("datasetSha256"))) failures.add("gold/baseline dataset hash mismatch");
        if (!gold.get("baselineSourceTreeSha256").equals(baseline.get("sourceTreeSha256"))) failures.add("gold/baseline source hash mismatch");
        List<String> ids = sampleMaps(gold).stream().map(sample -> String.valueOf(sample.get("sampleId"))).toList();
        List<String> expectedIds = java.util.stream.IntStream.rangeClosed(1, sampleCount)
                .mapToObj(value -> "%02d".formatted(value)).toList();
        if (!ids.equals(expectedIds)) failures.add("sample IDs must be continuous 01-" + "%02d".formatted(sampleCount));
        for (Map<String, Object> sample : sampleMaps(gold)) {
            validateFile(root, sample, "docxPath", "docxSha256", failures);
            validateFile(root, sample, "pdfPath", "pdfSha256", failures);
            if (sample.containsKey("sourceZipPath") || sample.containsKey("sourceZipSha256")) {
                validateFile(root, sample, "sourceZipPath", "sourceZipSha256", failures);
            }
        }
        return failures;
    }

    private void validateFile(Path root, Map<String, Object> sample, String pathKey, String hashKey,
                              List<String> failures) throws Exception {
        Path path = root.resolve(String.valueOf(sample.get(pathKey))).normalize();
        String sampleId = String.valueOf(sample.get("sampleId"));
        if (!Files.isRegularFile(path)) failures.add(sampleId + " missing " + pathKey);
        else if (!sha256(path).equals(sample.get(hashKey))) failures.add(sampleId + " hash mismatch " + pathKey);
    }

    @SuppressWarnings("unchecked")
    private SampleResult evaluate(Path root, Map<String, Object> sample, ResumeFileService fileService,
                                  SkillNormalizationService skillService, List<String> integrityFailures) {
        String sampleId = String.valueOf(sample.get("sampleId"));
        Set<String> expected = new LinkedHashSet<>((List<String>) sample.get("skills"));
        try {
            ResumeFileService.ExtractedResumeDocument docx = extract(root, String.valueOf(sample.get("docxPath")),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", fileService);
            ResumeFileService.ExtractedResumeDocument pdf = extract(root, String.valueOf(sample.get("pdfPath")),
                    "application/pdf", fileService);
            Set<String> predicted = skills(docx.text(), skillService);
            Set<String> pdfSkills = skills(pdf.text(), skillService);
            int truePositives = intersection(expected, predicted).size();
            var counts = new ResumeSkillBlindEvaluationSupport.Counts(
                    truePositives, difference(predicted, expected).size(), difference(expected, predicted).size());
            var metrics = ResumeSkillBlindEvaluationSupport.aggregate(List.of(counts));
            return new SampleResult(sampleId, String.valueOf(sample.get("sampleName")), category(sample),
                    counts, metrics.precision(), metrics.recall(), metrics.f1(), expected, predicted,
                    difference(expected, predicted), difference(predicted, expected),
                    difference(predicted, pdfSkills), difference(pdfSkills, predicted),
                    docx.parsingMode(), pdf.parsingMode());
        } catch (Exception exception) {
            integrityFailures.add(sampleId + " evaluation error: " + exception.getMessage());
            var counts = new ResumeSkillBlindEvaluationSupport.Counts(0, 0, expected.size());
            return new SampleResult(sampleId, String.valueOf(sample.get("sampleName")), category(sample),
                    counts, 0, 0, 0, expected, Set.of(), expected, Set.of(), Set.of(), Set.of(), "ERROR", "ERROR");
        }
    }

    private String category(Map<String, Object> sample) {
        String name = String.valueOf(sample.get("sampleName"));
        int secondUnderscore = name.indexOf('_', 3);
        return secondUnderscore >= 0 ? name.substring(secondUnderscore + 1) : name;
    }

    private ResumeFileService.ExtractedResumeDocument extract(Path root, String relativePath, String contentType,
                                                               ResumeFileService fileService) throws IOException {
        Path file = root.resolve(relativePath).normalize();
        return fileService.extract(new MockMultipartFile("file", file.getFileName().toString(), contentType,
                Files.readAllBytes(file)));
    }

    private Set<String> skills(String text, SkillNormalizationService service) {
        return service.extract(text).stream().map(ExtractedFact::normalizedValue)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.retainAll(right);
        return result;
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private Map<String, Double> macro(List<SampleResult> samples) {
        return Map.of(
                "precision", samples.stream().mapToDouble(SampleResult::precision).average().orElse(0),
                "recall", samples.stream().mapToDouble(SampleResult::recall).average().orElse(0),
                "f1", samples.stream().mapToDouble(SampleResult::f1).average().orElse(0));
    }

    private Map<String, ResumeSkillBlindEvaluationSupport.Metrics> categoryMetrics(List<SampleResult> samples) {
        Map<String, List<ResumeSkillBlindEvaluationSupport.Counts>> grouped = new LinkedHashMap<>();
        for (SampleResult sample : samples) {
            grouped.computeIfAbsent(sample.category(), ignored -> new ArrayList<>()).add(sample.counts());
        }
        Map<String, ResumeSkillBlindEvaluationSupport.Metrics> metrics = new LinkedHashMap<>();
        grouped.forEach((category, counts) -> metrics.put(category, ResumeSkillBlindEvaluationSupport.aggregate(counts)));
        return metrics;
    }

    private String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append("%02x".formatted(value));
        return result.toString();
    }

    private void writeCreateNew(Path reportPath, Map<String, Object> report) throws IOException {
        Files.createDirectories(reportPath.getParent());
        try (OutputStream stream = Files.newOutputStream(reportPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            JSON.writeValue(stream, report);
        }
    }

    private record SampleResult(String sampleId, String sampleName, String category,
                                ResumeSkillBlindEvaluationSupport.Counts counts,
                                double precision, double recall, double f1,
                                Set<String> expectedSkills, Set<String> predictedSkills,
                                Set<String> missingSkills, Set<String> unexpectedSkills,
                                Set<String> docxOnly, Set<String> pdfOnly,
                                String docxParsingMode, String pdfParsingMode) { }
}
