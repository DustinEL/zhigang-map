package com.zhigang.platform.career;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.api.Requests.MatchRunRequest;
import com.zhigang.platform.api.Requests.ResumeRequest;
import com.zhigang.platform.domain.PlatformModels.MatchReport;
import com.zhigang.platform.domain.PlatformModels.ResumeProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("mysql")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_REAL_MATCH_EVAL", matches = "true")
class RealResumeStandardRoleEvaluationTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Autowired private CareerService careerService;
    @Autowired private ResumeFileService resumeFileService;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("MYSQL_URL"));
        registry.add("spring.datasource.username", () -> required("MYSQL_USERNAME"));
        registry.add("spring.datasource.password", () -> required("MYSQL_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("zhigang.security.dev-permit-all", () -> true);
        registry.add("zhigang.ai.embedding.endpoint", () -> "");
        registry.add("zhigang.ai.qdrant.endpoint", () -> "");
    }

    @Test
    void currentProductionScoringMeetsNinetyPercentOnFrozenGoldSet() throws Exception {
        List<GoldCase> cases = loadGold();
        Map<String, ResumeProfile> resumes = new LinkedHashMap<>();
        List<Map<String, Object>> predictions = new ArrayList<>();
        Map<String, Map<String, Integer>> matrix = emptyMatrix();
        int correct = 0;

        for (GoldCase gold : cases) {
            ResumeProfile resume = resumes.computeIfAbsent(gold.resume(), this::parseResume);
            MatchReport report = careerService.match(new MatchRunRequest(resume.id(), gold.roleId(), "", ""));
            boolean matches = gold.label().equals(report.applicationState());
            correct += matches ? 1 : 0;
            matrix.get(gold.label()).merge(report.applicationState(), 1, Integer::sum);
            Map<String, Object> prediction = new LinkedHashMap<>();
            prediction.put("caseId", gold.id());
            prediction.put("resume", gold.resume());
            prediction.put("roleId", gold.roleId());
            prediction.put("role", gold.role());
            prediction.put("gold", gold.label());
            prediction.put("predicted", report.applicationState());
            prediction.put("correct", matches);
            prediction.put("readiness", report.readiness());
            prediction.put("projectCount", resume.projects().size());
            prediction.put("resumeSkills", resume.abilities().stream().map(value -> value.skill()).toList());
            prediction.put("skillDetails", report.skillDetails().stream().map(detail -> Map.of(
                    "skill", detail.skill(), "state", detail.alignmentState(), "score", detail.score(),
                    "evidenceLevel", detail.evidenceLevel(), "requiredLevel", detail.requiredLevel())).toList());
            prediction.put("dimensions", report.dimensions());
            prediction.put("limitReason", report.scoreLimitReason());
            predictions.add(prediction);
        }

        double accuracy = correct / (double) cases.size();
        double macroF1 = macroF1(matrix);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleCount", cases.size());
        report.put("correct", correct);
        report.put("accuracy", accuracy);
        report.put("macroF1", macroF1);
        report.put("confusionMatrix", matrix);
        report.put("predictions", predictions);
        Path output = Path.of("target", "real-resume-standard-role-evaluation-report.json");
        Files.writeString(output, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);

        System.out.printf("Real match metrics: correct=%d/%d accuracy=%.4f macroF1=%.4f report=%s%n",
                correct, cases.size(), accuracy, macroF1, output.toAbsolutePath());
        assertThat(accuracy).as("56-case accuracy; report=%s", output.toAbsolutePath()).isGreaterThanOrEqualTo(0.90);
    }

    private ResumeProfile parseResume(String resumeName) {
        try {
            Path file = ROOT.resolve("测试简历").resolve(resumeName).resolve(resumeName + ".docx");
            byte[] bytes = Files.readAllBytes(file);
            MockMultipartFile multipart = new MockMultipartFile("file", file.getFileName().toString(),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
            String text = resumeFileService.extractText(multipart);
            ResumeProfile profile = careerService.parseResume(new ResumeRequest(text, 12, 2027));
            return careerService.confirmResume(profile.id(), true);
        } catch (Exception exception) {
            throw new IllegalStateException("解析真实简历失败: " + resumeName, exception);
        }
    }

    private List<GoldCase> loadGold() throws Exception {
        List<GoldCase> result = new ArrayList<>();
        for (String line : Files.readAllLines(ROOT.resolve("test-data/evaluation/标准岗位人岗匹配初标注-待审核.md"), StandardCharsets.UTF_8)) {
            if (!line.matches("[|]\\s*\\d+\\s*[|].*")) continue;
            String[] columns = line.split("[|]", -1);
            String label = columns[7].trim();
            if (label.isBlank()) continue;
            result.add(new GoldCase(Integer.parseInt(columns[1].trim()), columns[2].trim(),
                    Long.parseLong(columns[3].trim()), columns[4].trim(), label));
        }
        assertThat(result).hasSize(56);
        return result;
    }

    private Map<String, Map<String, Integer>> emptyMatrix() {
        List<String> labels = List.of("可申请实习/校招", "接近可申请", "需先补关键能力");
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (String gold : labels) {
            Map<String, Integer> row = new LinkedHashMap<>();
            labels.forEach(predicted -> row.put(predicted, 0));
            matrix.put(gold, row);
        }
        return matrix;
    }

    private double macroF1(Map<String, Map<String, Integer>> matrix) {
        double total = 0;
        for (String label : matrix.keySet()) {
            int tp = matrix.get(label).get(label);
            int fp = matrix.entrySet().stream().filter(entry -> !entry.getKey().equals(label))
                    .mapToInt(entry -> entry.getValue().get(label)).sum();
            int fn = matrix.get(label).entrySet().stream().filter(entry -> !entry.getKey().equals(label))
                    .mapToInt(Map.Entry::getValue).sum();
            total += tp == 0 ? 0 : 2.0 * tp / (2 * tp + fp + fn);
        }
        return total / matrix.size();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量: " + name);
        return value;
    }

    private record GoldCase(int id, String resume, long roleId, String role, String label) {}
}
