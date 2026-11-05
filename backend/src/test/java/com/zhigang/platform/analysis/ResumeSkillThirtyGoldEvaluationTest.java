package com.zhigang.platform.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhigang.platform.career.ResumeFileService;
import com.zhigang.platform.career.ResumeOcrClient;
import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
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

class ResumeSkillThirtyGoldEvaluationTest {
    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path GOLD_FILE = REPOSITORY_ROOT.resolve("test-data/resume-skill-gold/resume-skill-30-gold.json");
    private static final Path MANIFEST_FILE = REPOSITORY_ROOT.resolve("test-data/resume-skill-gold/resume-skill-30-manifest.json");
    private static final Path REPORT_FILE = Path.of("target/resume-skill-30-evaluation-report.json").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void thirtyFrozenResumesMeetGoldMetricsAndDocxPdfSkillParity() throws Exception {
        GoldDataset gold = readGoldDataset();
        Manifest manifest = readManifest();
        List<String> integrityFailures = validateDataset(gold, manifest);
        ResumeOcrClient ocrClient = mock(ResumeOcrClient.class);
        when(ocrClient.extractPdf(any(byte[].class), anyString())).thenReturn(
                new ResumeOcrClient.OcrResult("", 0, "TEST_OCR_FALLBACK"));
        ResumeFileService fileService = new ResumeFileService(ocrClient);
        SkillNormalizationService skillService = new SkillNormalizationService(new DemoPlatformDataProvider());

        List<SampleResult> samples = new ArrayList<>();
        for (GoldSample sample : gold.samples()) {
            samples.add(evaluate(sample, fileService, skillService, integrityFailures));
        }
        Metrics overall = aggregate(samples);
        List<String> parityFailures = samples.stream()
                .filter(sample -> !sample.docxOnly().isEmpty() || !sample.pdfOnly().isEmpty())
                .map(sample -> sample.sampleId() + " docxOnly=" + sample.docxOnly() + " pdfOnly=" + sample.pdfOnly())
                .toList();
        writeReport(gold, overall, samples, integrityFailures, parityFailures);
        printSummary(overall, samples, parityFailures, integrityFailures);

        assertThat(integrityFailures).as("frozen gold and source file integrity").isEmpty();
        assertThat(overall.recall()).as("DOCX micro recall").isGreaterThanOrEqualTo(0.90);
        assertThat(overall.f1()).as("DOCX micro F1").isGreaterThanOrEqualTo(0.90);
        assertThat(samples).allSatisfy(sample -> assertThat(sample.metrics().recall())
                .as("DOCX recall for %s; missing=%s", sample.sampleId(), sample.missingSkills())
                .isGreaterThanOrEqualTo(0.80));
        assertThat(parityFailures).as("DOCX/PDF extracted skill-set parity").isEmpty();
    }

    private GoldDataset readGoldDataset() throws IOException {
        Map<String, Object> root = JSON.readValue(Files.readString(GOLD_FILE, StandardCharsets.UTF_8), new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawSamples = (List<Map<String, Object>>) root.get("samples");
        List<GoldSample> samples = rawSamples.stream().map(this::toGoldSample).toList();
        return new GoldDataset(
                String.valueOf(root.get("goldStatus")),
                String.valueOf(root.get("datasetSha256")),
                ((Number) root.get("sampleCount")).intValue(),
                samples
        );
    }

    private GoldSample toGoldSample(Map<String, Object> value) {
        @SuppressWarnings("unchecked")
        List<String> skills = (List<String>) value.get("skills");
        return new GoldSample(
                String.valueOf(value.get("sampleId")),
                String.valueOf(value.get("sampleName")),
                String.valueOf(value.get("docxPath")),
                String.valueOf(value.get("docxSha256")),
                String.valueOf(value.get("pdfPath")),
                String.valueOf(value.get("pdfSha256")),
                new LinkedHashSet<>(skills)
        );
    }

    private Manifest readManifest() throws IOException {
        Map<String, Object> root = JSON.readValue(Files.readString(MANIFEST_FILE, StandardCharsets.UTF_8), new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawSamples = (List<Map<String, Object>>) root.get("samples");
        Map<String, ManifestSample> samples = new LinkedHashMap<>();
        for (Map<String, Object> value : rawSamples) {
            @SuppressWarnings("unchecked")
            Map<String, Object> docx = (Map<String, Object>) value.get("docx");
            @SuppressWarnings("unchecked")
            Map<String, Object> pdf = (Map<String, Object>) value.get("pdf");
            samples.put(String.valueOf(value.get("sampleId")), new ManifestSample(
                    String.valueOf(docx.get("sha256")), String.valueOf(pdf.get("sha256"))));
        }
        return new Manifest(String.valueOf(root.get("datasetSha256")), ((Number) root.get("sampleCount")).intValue(), samples);
    }

    private List<String> validateDataset(GoldDataset gold, Manifest manifest) throws Exception {
        List<String> failures = new ArrayList<>();
        if (!"FROZEN".equals(gold.status())) failures.add("goldStatus must be FROZEN but was " + gold.status());
        if (gold.sampleCount() != 30) failures.add("gold sampleCount must be 30 but was " + gold.sampleCount());
        if (manifest.sampleCount() != 30) failures.add("manifest sampleCount must be 30 but was " + manifest.sampleCount());
        if (!gold.datasetSha256().equals(manifest.datasetSha256())) failures.add("gold datasetSha256 differs from manifest");
        List<String> ids = gold.samples().stream().map(GoldSample::sampleId).toList();
        List<String> expectedIds = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(number -> "%02d".formatted(number)).toList();
        if (!ids.equals(expectedIds)) failures.add("sample IDs must be continuous 01-30 but were " + ids);
        for (GoldSample sample : gold.samples()) {
            ManifestSample manifestSample = manifest.samples().get(sample.sampleId());
            if (manifestSample == null) {
                failures.add(sample.sampleId() + " is absent from manifest");
                continue;
            }
            if (!sample.docxSha256().equals(manifestSample.docxSha256())) {
                failures.add(sample.sampleId() + " DOCX gold hash differs from manifest");
            }
            if (!sample.pdfSha256().equals(manifestSample.pdfSha256())) {
                failures.add(sample.sampleId() + " PDF gold hash differs from manifest");
            }
            validateFileHash(sample.sampleId(), "DOCX", sample.docxPath(), sample.docxSha256(), failures);
            validateFileHash(sample.sampleId(), "PDF", sample.pdfPath(), sample.pdfSha256(), failures);
        }
        return failures;
    }

    private void validateFileHash(String sampleId, String type, String relativePath, String expectedHash, List<String> failures) throws Exception {
        Path file = REPOSITORY_ROOT.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            failures.add(sampleId + " " + type + " is missing: " + relativePath);
            return;
        }
        String actualHash = hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!expectedHash.equals(actualHash)) failures.add(sampleId + " " + type + " SHA-256 mismatch");
    }

    private SampleResult evaluate(GoldSample sample, ResumeFileService fileService, SkillNormalizationService skillService,
                                  List<String> integrityFailures) throws IOException {
        try {
            ResumeFileService.ExtractedResumeDocument docx = extract(sample.docxPath(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", fileService);
            ResumeFileService.ExtractedResumeDocument pdf = extract(sample.pdfPath(), "application/pdf", fileService);
            Set<String> docxSkills = extractSkills(docx.text(), skillService);
            Set<String> pdfSkills = extractSkills(pdf.text(), skillService);
            Metrics metrics = metrics(sample.skills(), docxSkills);
            Set<String> missing = difference(sample.skills(), docxSkills);
            Set<String> unexpected = difference(docxSkills, sample.skills());
            return new SampleResult(sample.sampleId(), sample.sampleName(), metrics, missing, unexpected,
                    difference(docxSkills, pdfSkills), difference(pdfSkills, docxSkills),
                    docx.parsingMode(), pdf.parsingMode());
        } catch (RuntimeException exception) {
            integrityFailures.add(sample.sampleId() + " evaluation failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return new SampleResult(sample.sampleId(), sample.sampleName(), new Metrics(0, 0, sample.skills().size(), 0, 0, 0),
                    new TreeSet<>(sample.skills()), Set.of(), Set.of(), Set.of(), "ERROR", "ERROR");
        }
    }

    private ResumeFileService.ExtractedResumeDocument extract(String relativePath, String contentType, ResumeFileService fileService) throws IOException {
        Path file = REPOSITORY_ROOT.resolve(relativePath);
        return fileService.extract(new MockMultipartFile("file", file.getFileName().toString(), contentType, Files.readAllBytes(file)));
    }

    private Set<String> extractSkills(String text, SkillNormalizationService skillService) {
        return skillService.extract(text).stream().map(ExtractedFact::normalizedValue)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private Metrics aggregate(List<SampleResult> samples) {
        int truePositives = samples.stream().mapToInt(sample -> sample.metrics().truePositives()).sum();
        int falsePositives = samples.stream().mapToInt(sample -> sample.metrics().falsePositives()).sum();
        int falseNegatives = samples.stream().mapToInt(sample -> sample.metrics().falseNegatives()).sum();
        return metrics(truePositives, falsePositives, falseNegatives);
    }

    private Metrics metrics(Set<String> expected, Set<String> actual) {
        return metrics(intersectionSize(expected, actual), difference(actual, expected).size(), difference(expected, actual).size());
    }

    private Metrics metrics(int truePositives, int falsePositives, int falseNegatives) {
        double precision = ratio(truePositives, truePositives + falsePositives);
        double recall = ratio(truePositives, truePositives + falseNegatives);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new Metrics(truePositives, falsePositives, falseNegatives, precision, recall, f1);
    }

    private int intersectionSize(Set<String> left, Set<String> right) {
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return intersection.size();
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private void writeReport(GoldDataset gold, Metrics overall, List<SampleResult> samples,
                             List<String> integrityFailures, List<String> parityFailures) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("goldStatus", gold.status());
        report.put("datasetSha256", gold.datasetSha256());
        report.put("sampleCount", gold.sampleCount());
        report.put("overall", overall);
        report.put("samples", samples);
        report.put("integrityFailures", integrityFailures);
        report.put("docxPdfParityFailures", parityFailures);
        Files.createDirectories(REPORT_FILE.getParent());
        JSON.writeValue(REPORT_FILE.toFile(), report);
    }

    private void printSummary(Metrics overall, List<SampleResult> samples, List<String> parityFailures, List<String> integrityFailures) {
        System.out.printf("Resume skill 30 micro metrics: precision=%.4f, recall=%.4f, F1=%.4f, TP=%d, FP=%d, FN=%d%n",
                overall.precision(), overall.recall(), overall.f1(), overall.truePositives(), overall.falsePositives(), overall.falseNegatives());
        System.out.printf("Resume skill 30 DOCX/PDF differences: %d; integrity failures: %d%n", parityFailures.size(), integrityFailures.size());
        samples.stream().filter(sample -> !sample.docxOnly().isEmpty() || !sample.pdfOnly().isEmpty()).forEach(sample ->
                System.out.printf("Resume %s difference: docxOnly=%s, pdfOnly=%s%n", sample.sampleId(), sample.docxOnly(), sample.pdfOnly()));
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append("%02x".formatted(value));
        return result.toString();
    }

    private record GoldDataset(String status, String datasetSha256, int sampleCount, List<GoldSample> samples) {
    }

    private record GoldSample(String sampleId, String sampleName, String docxPath, String docxSha256,
                              String pdfPath, String pdfSha256, Set<String> skills) {
    }

    private record Manifest(String datasetSha256, int sampleCount, Map<String, ManifestSample> samples) {
    }

    private record ManifestSample(String docxSha256, String pdfSha256) {
    }

    private record Metrics(int truePositives, int falsePositives, int falseNegatives,
                           double precision, double recall, double f1) {
    }

    private record SampleResult(String sampleId, String sampleName, Metrics metrics,
                                Set<String> missingSkills, Set<String> unexpectedSkills,
                                Set<String> docxOnly, Set<String> pdfOnly,
                                String docxParsingMode, String pdfParsingMode) {
    }
}
