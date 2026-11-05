package com.zhigang.platform.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.career.ResumeFileService;
import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression subset for the seven public resumes. The expected skills are
 * loaded from the frozen 30-resume gold set so there is one source of truth.
 * PDF copies remain covered by the dedicated 30-resume evaluation.
 */
class ResumeSkillGoldEvaluationTest {
    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path GOLD_FILE = REPOSITORY_ROOT.resolve("test-data").resolve("resume-skill-gold").resolve("resume-skill-30-gold.json");

    private final ResumeFileService fileService = new ResumeFileService(mock(com.zhigang.platform.career.ResumeOcrClient.class));
    private final SkillNormalizationService skillService = new SkillNormalizationService(new DemoPlatformDataProvider());

    @Test
    void sevenRealResumesMeetSkillRecallAndF1Threshold() throws IOException {
        int truePositives = 0;
        int expected = 0;
        int predicted = 0;
        Map<String, Set<String>> misses = new LinkedHashMap<>();

        for (Map.Entry<String, GoldSample> entry : gold().entrySet()) {
            GoldSample sample = entry.getValue();
            Path file = REPOSITORY_ROOT.resolve(sample.docxPath());
            byte[] bytes = Files.readAllBytes(file);
            var multipart = new MockMultipartFile("file", file.getFileName().toString(),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
            String text = fileService.extractText(multipart);
            Set<String> actual = skillService.extract(text).stream()
                    .map(ExtractedFact::normalizedValue)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);

            Set<String> gold = sample.skills();
            Set<String> missing = new LinkedHashSet<>(gold);
            missing.removeAll(actual);
            if (!missing.isEmpty()) misses.put(entry.getKey(), missing);
            int sampleTruePositives = intersectionSize(gold, actual);
            double sampleRecall = (double) sampleTruePositives / gold.size();
            assertThat(sampleRecall).as("per-resume recall for %s; missing=%s", entry.getKey(), missing)
                    .isGreaterThanOrEqualTo(0.80);
            truePositives += sampleTruePositives;
            expected += gold.size();
            predicted += actual.size();
        }

        double recall = (double) truePositives / expected;
        double precision = predicted == 0 ? 0 : (double) truePositives / predicted;
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        System.out.printf("Resume gold metrics: precision=%.4f, recall=%.4f, F1=%.4f%n", precision, recall, f1);
        assertThat(recall >= 0.90 && f1 >= 0.90)
                .as("gold skill metrics: precision=%.4f, recall=%.4f, F1=%.4f; misses=%s",
                        precision, recall, f1, misses)
                .isTrue();
    }

    private int intersectionSize(Set<String> left, Set<String> right) {
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return intersection.size();
    }

    private Map<String, GoldSample> gold() throws IOException {
        JsonNode samples = new ObjectMapper().readTree(Files.readString(GOLD_FILE)).path("samples");
        Map<String, GoldSample> result = new LinkedHashMap<>();
        for (JsonNode sample : samples) {
            String sampleId = sample.path("sampleId").asText();
            if (Integer.parseInt(sampleId) <= 7) {
                Set<String> skills = new LinkedHashSet<>();
                sample.path("skills").forEach(skill -> skills.add(skill.asText()));
                result.put(sampleId, new GoldSample(sample.path("docxPath").asText(), skills));
            }
        }
        return result;
    }

    private record GoldSample(String docxPath, Set<String> skills) { }
}
