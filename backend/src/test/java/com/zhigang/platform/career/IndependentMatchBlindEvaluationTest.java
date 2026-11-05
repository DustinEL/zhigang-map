package com.zhigang.platform.career;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.api.Requests.MatchRunRequest;
import com.zhigang.platform.api.Requests.ResumeRequest;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.MatchReport;
import com.zhigang.platform.domain.PlatformModels.ResumeProfile;
import com.zhigang.platform.repository.PlatformDataProvider;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("mysql")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MATCH_BLIND_EVAL", matches = "true")
class IndependentMatchBlindEvaluationTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("test-data/evaluation/match-blind-96-manifest.json");
    private static final Path ANNOTATION = ROOT.resolve("test-data/evaluation/match-blind-96-annotation.md");
    private static final Path GOLD = ROOT.resolve("test-data/evaluation/match-blind-96-gold.json");
    private static final String READY = "\u53ef\u7533\u8bf7\u5b9e\u4e60/\u6821\u62db";
    private static final String NEAR = "\u63a5\u8fd1\u53ef\u7533\u8bf7";
    private static final String GAP = "\u9700\u5148\u8865\u5173\u952e\u80fd\u529b";
    private static final String UNDECIDABLE = "\u65e0\u6cd5\u5224\u65ad";
    private static final List<String> LABELS = List.of(READY, NEAR, GAP);

    @Autowired private CareerService careerService;
    @Autowired private ResumeFileService resumeFileService;
    @Autowired private PlatformDataProvider dataProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RoleCapabilityCardCatalog capabilityCardCatalog;

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
    void frozenLegacySetProducesDiagnosticReport() throws Exception {
        FrozenDataset dataset = loadAndValidateDataset();
        Map<String, ResumeProfile> parsedResumes = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> matrix = emptyMatrix();
        Map<Long, Map<String, Map<String, Integer>>> roleMatrices = new LinkedHashMap<>();
        List<Map<String, Object>> predictions = new ArrayList<>();
        int correct = 0;

        for (GoldCase gold : dataset.validCases()) {
            ResumeProfile resume = parsedResumes.computeIfAbsent(gold.resumeId(), ignored -> parseResume(gold));
            MatchReport match = careerService.match(new MatchRunRequest(resume.id(), gold.roleId(), "", ""));
            MatchReport rerun = careerService.match(new MatchRunRequest(resume.id(), gold.roleId(), "", ""));
            assertThat(match.roleName()).as("case %s role name", gold.caseId()).isEqualTo(gold.roleName());
            assertThat(deterministicSignature(rerun)).as("case %s deterministic rerun", gold.caseId())
                    .isEqualTo(deterministicSignature(match));
            boolean matches = gold.label().equals(match.applicationState());
            correct += matches ? 1 : 0;
            matrix.get(gold.label()).merge(match.applicationState(), 1, Integer::sum);
            roleMatrices.computeIfAbsent(gold.roleId(), ignored -> emptyMatrix())
                    .get(gold.label()).merge(match.applicationState(), 1, Integer::sum);
            predictions.add(prediction(gold, resume, match, matches));
        }

        int sampleCount = dataset.validCases().size();
        double accuracy = correct / (double) sampleCount;
        Map<String, Map<String, Double>> metrics = classMetrics(matrix);
        double macroF1 = metrics.values().stream().mapToDouble(value -> value.get("f1")).average().orElse(0);
        Map<Long, Map<String, Object>> roleMetrics = roleMetrics(roleMatrices);
        Path output = writeReport(dataset, sampleCount, correct, accuracy, macroF1, metrics, matrix, roleMetrics, predictions);

        assertThat(output).exists();
        assertThat(roleMetrics).hasSize(8);
    }

    private FrozenDataset loadAndValidateDataset() throws Exception {
        assertThat(GOLD).as("gold must be frozen before blind evaluation").exists();
        assertThat(MANIFEST).exists();
        JsonNode gold = objectMapper.readTree(GOLD.toFile());
        JsonNode manifest = objectMapper.readTree(MANIFEST.toFile());
        assertThat(gold.path("manifestSha256").asText()).isEqualTo(sha256(MANIFEST));
        assertThat(gold.path("annotationSha256").asText()).isEqualTo(sha256(ANNOTATION));
        assertThat(manifest.path("datasetSha256").asText()).isEqualTo(canonicalSha256(manifest, "datasetSha256"));
        assertThat(gold.path("datasetSha256").asText()).isEqualTo(manifest.path("datasetSha256").asText());
        assertThat(gold.path("goldSha256").asText()).isEqualTo(canonicalSha256(gold, "goldSha256"));
        assertThat(gold.path("datasetId").asText()).isEqualTo(manifest.path("datasetId").asText());

        Map<Long, JobRole> catalog = new LinkedHashMap<>();
        dataProvider.findJobRoles().forEach(role -> catalog.put(role.id(), role));
        Map<String, JsonNode> manifestCases = byTextKey(manifest.path("cases"), "caseId");
        Map<String, JsonNode> resumes = byTextKey(manifest.path("resumes"), "resumeId");
        Map<Long, String> manifestRoleNames = new LinkedHashMap<>();
        manifest.path("roles").forEach(item -> manifestRoleNames.put(item.path("roleId").asLong(), item.path("roleName").asText()));
        List<GoldCase> validCases = new ArrayList<>();
        int undecidable = gold.path("undecidableCases").size();
        Map<String, JsonNode> goldCases = new LinkedHashMap<>();
        gold.path("validCases").forEach(item -> assertUnique(goldCases, item));
        gold.path("undecidableCases").forEach(item -> assertUnique(goldCases, item));
        assertThat(goldCases.keySet()).containsExactlyInAnyOrderElementsOf(manifestCases.keySet());

        for (JsonNode item : gold.path("validCases")) {
            String caseId = item.path("caseId").asText();
            JsonNode manifestCase = manifestCases.get(caseId);
            assertThat(manifestCase).as("gold case exists in manifest: %s", caseId).isNotNull();
            String label = item.path("label").asText();
            assertThat(LABELS).contains(label);
            String resumeId = manifestCase.path("resumeId").asText();
            JsonNode resume = resumes.get(resumeId);
            long roleId = manifestCase.path("roleId").asLong();
            String roleName = manifestRoleNames.get(roleId);
            JobRole role = catalog.get(roleId);
            assertThat(role).as("role id %s exists", roleId).isNotNull();
            assertThat(role.name()).as("role id %s name", roleId).isEqualTo(roleName);
            Path resumeFile = ROOT.resolve(resume.path("docx").path("path").asText()).normalize();
            assertThat(resumeFile).startsWith(ROOT.resolve("\u6d4b\u8bd5\u7b80\u5386"));
            assertThat(sha256(resumeFile)).as("resume hash for %s", resumeId)
                    .isEqualTo(resume.path("docx").path("sha256").asText());
            validCases.add(new GoldCase(caseId, resumeId, resumeFile, roleId, roleName, label));
        }
        gold.path("undecidableCases").forEach(item -> {
            assertThat(item.path("label").asText()).isEqualTo(UNDECIDABLE);
            assertIdentity(item, manifestCases, resumes, manifestRoleNames);
        });
        assertThat(validCases).hasSizeGreaterThanOrEqualTo(90);
        assertThat(validCases.size() + undecidable).isEqualTo(96);
        return new FrozenDataset(gold.path("datasetId").asText(), validCases, undecidable);
    }

    private void assertUnique(Map<String, JsonNode> cases, JsonNode item) {
        String caseId = item.path("caseId").asText();
        assertThat(caseId).isNotBlank();
        assertThat(cases.putIfAbsent(caseId, item)).as("duplicate gold case: %s", caseId).isNull();
    }

    private void assertIdentity(JsonNode item, Map<String, JsonNode> manifestCases, Map<String, JsonNode> resumes,
                                Map<Long, String> roleNames) {
        String caseId = item.path("caseId").asText();
        JsonNode manifestCase = manifestCases.get(caseId);
        assertThat(manifestCase).as("gold case exists in manifest: %s", caseId).isNotNull();
        String resumeId = manifestCase.path("resumeId").asText();
        long roleId = manifestCase.path("roleId").asLong();
        assertThat(item.path("resumeId").asText()).isEqualTo(resumeId);
        assertThat(item.path("resumeName").asText()).isEqualTo(resumes.get(resumeId).path("resumeName").asText());
        assertThat(item.path("roleId").asLong()).isEqualTo(roleId);
        assertThat(item.path("roleName").asText()).isEqualTo(roleNames.get(roleId));
    }

    private Map<String, JsonNode> byTextKey(JsonNode array, String key) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        array.forEach(item -> result.put(item.path(key).asText(), item));
        return result;
    }

    private ResumeProfile parseResume(GoldCase gold) {
        try {
            byte[] bytes = Files.readAllBytes(gold.resumeFile());
            MockMultipartFile multipart = new MockMultipartFile("file", gold.resumeFile().getFileName().toString(),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
            String text = resumeFileService.extractText(multipart);
            ResumeProfile profile = careerService.parseResume(new ResumeRequest(text, 12, 2027));
            return careerService.confirmResume(profile.id(), true);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse blind-test resume: " + gold.resumeId(), exception);
        }
    }

    private Map<String, Object> prediction(GoldCase gold, ResumeProfile resume, MatchReport match, boolean correct) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("caseId", gold.caseId());
        item.put("resumeId", gold.resumeId());
        item.put("roleId", gold.roleId());
        item.put("roleName", gold.roleName());
        item.put("gold", gold.label());
        item.put("predicted", match.applicationState());
        item.put("correct", correct);
        item.put("readiness", match.readiness());
        item.put("education", resume.education());
        item.put("parsingMode", resume.parsingMode());
        item.put("projectCount", resume.projects().size());
        item.put("resumeSkills", resume.abilities().stream().map(value -> value.skill()).toList());
        item.put("hardGates", match.hardGates());
        item.put("skillDetails", match.skillDetails());
        item.put("dimensions", match.dimensions());
        item.put("scoreLimitReason", match.scoreLimitReason());
        item.put("scoringPolicyVersion", match.scoringPolicyVersion());
        item.put("calibrationDatasetHash", match.calibrationDatasetHash());
        item.put("graphRelease", match.graphRelease());
        return item;
    }

    private Path writeReport(FrozenDataset dataset, int sampleCount, int correct, double accuracy, double macroF1,
                             Map<String, Map<String, Double>> metrics, Map<String, Map<String, Integer>> matrix,
                             Map<Long, Map<String, Object>> roleMetrics,
                             List<Map<String, Object>> predictions) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluationPurpose", "DIAGNOSTIC_ONLY");
        report.put("acceptanceEligible", false);
        report.put("acceptanceExclusionReason", "该 96 条集合已参与开发，不得用于 M5 最终验收");
        report.put("datasetId", dataset.datasetId());
        report.put("sampleCount", sampleCount);
        report.put("undecidableCount", dataset.undecidableCount());
        report.put("correct", correct);
        report.put("accuracy", accuracy);
        report.put("macroF1", macroF1);
        report.put("classMetrics", metrics);
        report.put("confusionMatrix", matrix);
        report.put("roleMetrics", roleMetrics);
        report.put("deterministicRerun", true);
        report.put("versions", Map.of(
                "roleCapabilityCatalogVersion", capabilityCardCatalog.version(),
                "roleCapabilityCatalogSha256", capabilityCardCatalog.snapshotSha256(),
                "extractorVersion", ResumeEvidenceExtractor.VERSION,
                "policyVersion", MatchDecisionEngine.POLICY_VERSION
        ));
        report.put("hashes", Map.of(
                "manifestSha256", sha256(MANIFEST),
                "goldSha256", sha256(GOLD),
                "annotationSha256", sha256(ANNOTATION)
        ));
        report.put("predictions", predictions);
        Path output = Path.of("target", "match-blind-96-evaluation-report.json");
        Files.writeString(output, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        return output;
    }

    private Map<String, Object> deterministicSignature(MatchReport report) {
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("applicationState", report.applicationState());
        signature.put("readiness", report.readiness());
        signature.put("hardGates", report.hardGates());
        signature.put("skillDetails", report.skillDetails());
        signature.put("dimensions", report.dimensions());
        signature.put("scoreLimitReason", report.scoreLimitReason());
        signature.put("scoringPolicyVersion", report.scoringPolicyVersion());
        signature.put("calibrationDatasetHash", report.calibrationDatasetHash());
        return signature;
    }

    private Map<Long, Map<String, Object>> roleMetrics(
            Map<Long, Map<String, Map<String, Integer>>> roleMatrices) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        roleMatrices.forEach((roleId, roleMatrix) -> {
            int sampleCount = roleMatrix.values().stream()
                    .flatMap(row -> row.values().stream()).mapToInt(Integer::intValue).sum();
            int correct = LABELS.stream().mapToInt(label -> roleMatrix.get(label).get(label)).sum();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sampleCount", sampleCount);
            values.put("correct", correct);
            values.put("accuracy", sampleCount == 0 ? 0 : correct / (double) sampleCount);
            values.put("classMetrics", classMetrics(roleMatrix));
            values.put("confusionMatrix", roleMatrix);
            result.put(roleId, values);
        });
        return result;
    }

    private Map<String, Map<String, Integer>> emptyMatrix() {
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (String gold : LABELS) {
            Map<String, Integer> row = new LinkedHashMap<>();
            LABELS.forEach(predicted -> row.put(predicted, 0));
            matrix.put(gold, row);
        }
        return matrix;
    }

    private Map<String, Map<String, Double>> classMetrics(Map<String, Map<String, Integer>> matrix) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (String label : LABELS) {
            int tp = matrix.get(label).get(label);
            int fp = LABELS.stream().filter(other -> !other.equals(label)).mapToInt(other -> matrix.get(other).get(label)).sum();
            int fn = LABELS.stream().filter(other -> !other.equals(label)).mapToInt(other -> matrix.get(label).get(other)).sum();
            double precision = tp + fp == 0 ? 0 : tp / (double) (tp + fp);
            double recall = tp + fn == 0 ? 0 : tp / (double) (tp + fn);
            double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
            result.put(label, Map.of("precision", precision, "recall", recall, "f1", f1, "support", (double) (tp + fn)));
        }
        return result;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private String canonicalSha256(JsonNode node, String excludedField) throws Exception {
        JsonNode copy = node.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove(excludedField);
        byte[] bytes = objectMapper.writeValueAsBytes(canonicalValue(copy));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Object canonicalValue(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> values = new java.util.TreeMap<>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), canonicalValue(entry.getValue())));
            return values;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(canonicalValue(value)));
            return values;
        }
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNull()) return null;
        return node.asText();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }

    private record GoldCase(String caseId, String resumeId, Path resumeFile, long roleId, String roleName, String label) {}
    private record FrozenDataset(String datasetId, List<GoldCase> validCases, int undecidableCount) {}
}
