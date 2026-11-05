package com.zhigang.platform.career;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.api.Requests.ResumeRequest;
import com.zhigang.platform.domain.PlatformModels.ResumeProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ActiveProfiles("demo")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MATCH_TRACE_DIAGNOSTIC", matches = "true")
class MatchBlindTraceDiagnosticTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve(
            "outputs/diagnostics/final-independent-30-v11-frozen-manifest.json");

    @Autowired private CareerService careerService;
    @Autowired private ResumeFileService resumeFileService;
    @Autowired private ResumeEvidenceExtractor evidenceExtractor;
    @Autowired private MatchDecisionEngine decisionEngine;
    @Autowired private RoleCapabilityCardCatalog cardCatalog;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void writesDecisionTracesForRootCauseAnalysis() throws Exception {
        JsonNode manifest = objectMapper.readTree(MANIFEST.toFile());
        Map<String, String> textByResume = new LinkedHashMap<>();
        Map<String, ResumeProfile> profileByResume = new LinkedHashMap<>();
        for (JsonNode resume : manifest.path("resumes")) {
            String resumeId = resume.path("resumeId").asText();
            Path source = ROOT.resolve(resume.path("sourcePath").asText()).normalize();
            byte[] bytes = Files.readAllBytes(source);
            MockMultipartFile file = new MockMultipartFile("file", source.getFileName().toString(),
                    source.toString().endsWith(".pdf") ? "application/pdf"
                            : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    bytes);
            String text = resumeFileService.extractText(file);
            textByResume.put(resumeId, text);
            profileByResume.put(resumeId, careerService.parseResume(new ResumeRequest(text, 12, 2027)));
        }

        List<Map<String, Object>> traces = new ArrayList<>();
        for (JsonNode item : manifest.path("cases")) {
            String resumeId = item.path("resumeId").asText();
            long roleId = item.path("roleId").asLong();
            ResumeProfile profile = profileByResume.get(resumeId);
            ResumeEvidenceBundle evidence = evidenceExtractor.extract(textByResume.get(resumeId), profile.facts());
            RoleCapabilityCard card = cardCatalog.find(roleId).orElseThrow();
            boolean educationPassed = profile.education().contains("本科")
                    || profile.education().contains("硕士") || profile.education().contains("博士")
                    || "未明确".equals(profile.education());
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("caseId", item.path("caseId").asText());
            trace.put("resumeId", resumeId);
            trace.put("roleId", roleId);
            trace.put("decision", decisionEngine.evaluate(card, evidence, educationPassed));
            traces.add(trace);
        }

        Path output = Path.of("target", "match-blind-traces.json");
        Files.writeString(output, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(traces));
    }
}
