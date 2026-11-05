package com.zhigang.platform.career;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MatchScoringPolicy {
    public static final String REQUIRED_SKILLS = "requiredSkills";
    public static final String RESPONSIBILITIES = "responsibilities";
    public static final String BONUS_SKILLS = "bonusSkills";
    public static final String PROFICIENCY = "proficiency";
    public static final String SCENARIOS = "scenarios";
    public static final String CERTIFICATES = "certificates";

    private final Policy policy;

    @Autowired
    public MatchScoringPolicy(ObjectMapper objectMapper) {
        this.policy = load(objectMapper);
    }

    MatchScoringPolicy(Policy policy) {
        this.policy = validate(policy);
    }

    public static MatchScoringPolicy defaults() {
        return new MatchScoringPolicy(new Policy(
                "resume-match-logistic-v1.0",
                "sha256:ccc95b1be504f57b598256d6db3f14ea27e4582ec4c56b5f04cb8e225399db76",
                Map.of(REQUIRED_SKILLS, 0.43, RESPONSIBILITIES, 0.24, BONUS_SKILLS, 0.08,
                        PROFICIENCY, 0.13, SCENARIOS, 0.07, CERTIFICATES, 0.05),
                2, 60.0, "fixture-f1@0.60=1.000; brier=0.099; n=6; scope=smoke-fixture"
        ));
    }

    public double weight(String dimension) {
        return policy.weights().getOrDefault(dimension, 0.0);
    }

    public String version() {
        return policy.version();
    }

    public String validationDatasetHash() {
        return policy.validationDatasetHash();
    }

    public int hardGateEvidenceLevel() {
        return policy.hardGateEvidenceLevel();
    }

    public double hardGateScoreCap() {
        return policy.hardGateScoreCap();
    }

    public String calibrationSummary() {
        return policy.calibrationSummary();
    }

    private Policy load(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource("scoring/p0-6-resume-match-v1.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            Map<String, Double> weights = new LinkedHashMap<>();
            root.path("weights").fields().forEachRemaining(entry -> weights.put(entry.getKey(), entry.getValue().asDouble()));
            return validate(new Policy(root.path("version").asText(), root.path("validationDatasetHash").asText(),
                    Map.copyOf(weights), root.path("hardGateEvidenceLevel").asInt(2),
                    root.path("hardGateScoreCap").asDouble(60), root.path("calibrationSummary").asText()));
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载 P0-6 匹配评分策略", exception);
        }
    }

    private Policy validate(Policy value) {
        double total = value.weights().values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(total - 1.0) > 0.0001) throw new IllegalArgumentException("匹配评分权重之和必须为 1");
        if (value.version().isBlank() || !value.validationDatasetHash().startsWith("sha256:")) {
            throw new IllegalArgumentException("匹配评分策略必须包含版本和验证集哈希");
        }
        return value;
    }

    record Policy(String version, String validationDatasetHash, Map<String, Double> weights,
                  int hardGateEvidenceLevel, double hardGateScoreCap, String calibrationSummary) {
    }
}
