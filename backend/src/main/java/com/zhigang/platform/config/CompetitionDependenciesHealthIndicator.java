package com.zhigang.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Profile("mysql")
@Component
public class CompetitionDependenciesHealthIndicator implements HealthIndicator {
    private final RestClient restClient;
    private final List<Dependency> dependencies;

    public CompetitionDependenciesHealthIndicator(
            @Value("${zhigang.analysis.emerging.worker-endpoint:}") String workerEndpoint,
            @Value("${zhigang.ai.embedding.endpoint:}") String embeddingEndpoint,
            @Value("${zhigang.ai.qdrant.endpoint:}") String qdrantEndpoint,
            @Value("${zhigang.health.dependency-timeout-ms:1500}") int timeoutMs
    ) {
        int safeTimeoutMs = Math.max(200, Math.min(timeoutMs, 5000));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(safeTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(safeTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.dependencies = List.of(
                new Dependency("analysisWorker", healthUrl(workerEndpoint, "/health"),
                        "分析工作器不可用，请检查 EMERGING_WORKER_ENDPOINT 和 analysis-worker 服务"),
                new Dependency("embedding", embeddingHealthUrl(embeddingEndpoint),
                        "向量模型不可用，请检查 EMBEDDING_API_ENDPOINT 和 bge-m3 服务"),
                new Dependency("qdrant", healthUrl(qdrantEndpoint, "/healthz"),
                        "Qdrant 不可用，请检查 QDRANT_ENDPOINT 和 qdrant 服务")
        );
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (Dependency dependency : dependencies) {
            if (dependency.url().isBlank()) {
                details.put(dependency.name(), "未配置");
                failures.add(dependency.failureMessage());
                continue;
            }
            try {
                restClient.get().uri(dependency.url()).retrieve().toBodilessEntity();
                details.put(dependency.name(), "UP");
            } catch (RuntimeException exception) {
                details.put(dependency.name(), dependency.failureMessage());
                failures.add(dependency.failureMessage());
            }
        }
        if (failures.isEmpty()) {
            return Health.up().withDetails(details).build();
        }
        return Health.down().withDetail("summary", String.join("；", failures)).withDetails(details).build();
    }

    private static String embeddingHealthUrl(String endpoint) {
        String cleaned = trimSlash(endpoint);
        if (cleaned.endsWith("/v1/embeddings")) {
            cleaned = cleaned.substring(0, cleaned.length() - "/v1/embeddings".length());
        }
        return healthUrl(cleaned, "/health");
    }

    private static String healthUrl(String endpoint, String path) {
        String cleaned = trimSlash(endpoint);
        return cleaned.isBlank() ? "" : cleaned + path;
    }

    private static String trimSlash(String value) {
        String cleaned = value == null ? "" : value.trim();
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    private record Dependency(String name, String url, String failureMessage) {
    }
}
