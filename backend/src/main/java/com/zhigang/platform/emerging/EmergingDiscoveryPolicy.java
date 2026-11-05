package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Component
public class EmergingDiscoveryPolicy {
    private final Policy policy;
    private final String hash;

    public EmergingDiscoveryPolicy(ObjectMapper objectMapper) {
        try {
            byte[] content = new ClassPathResource("emerging-discovery-policy-v1.json").getInputStream().readAllBytes();
            this.policy = objectMapper.readValue(content, Policy.class);
            this.hash = sha256(content);
            validate(policy);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取新岗位发现策略", exception);
        }
    }

    public String version() {
        return policy.version();
    }

    public String hash() {
        return hash;
    }

    public String semanticProfileVersion() {
        return policy.semanticProfileVersion();
    }

    public Gate candidateGate() {
        return policy.candidateGate();
    }

    public Gate publishGate() {
        return policy.publishGate();
    }

    public Clustering clustering() {
        return policy.clustering();
    }

    public int timeWindowDays() {
        return policy.timeWindowDays();
    }

    public double candidateThreshold() {
        return policy.candidateThreshold();
    }

    public double publishThreshold() {
        return policy.publishThreshold();
    }

    public double existingRoleSimilarityThreshold() {
        return policy.existingRoleSimilarityThreshold();
    }

    public double score(Map<String, Double> metrics) {
        double logit = policy.intercept();
        for (Map.Entry<String, Double> weight : policy.weights().entrySet()) {
            logit += weight.getValue() * metrics.getOrDefault(weight.getKey(), 0.0);
        }
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    private void validate(Policy value) {
        if (!"logistic-regression".equals(value.algorithm()) || value.weights() == null || value.weights().size() != 6
                || value.semanticProfileVersion() == null || value.semanticProfileVersion().isBlank()) {
            throw new IllegalStateException("新岗位发现策略格式无效");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record Policy(String version, String semanticProfileVersion, String calibrationDataset, String algorithm, double intercept,
                         Map<String, Double> weights, double candidateThreshold, double publishThreshold,
                         double existingRoleSimilarityThreshold, Gate candidateGate, Gate publishGate,
                         Clustering clustering, int timeWindowDays) {
    }

    public record Gate(int minimumDocuments, int minimumCompanies, int minimumSources,
                       double minimumMedianProbability, int minimumCompaniesPerWindow) {
    }

    public record Clustering(int minimumClusterSize, int minimumSamples) {
    }
}
