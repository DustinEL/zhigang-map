package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int dimension;

    public EmbeddingClient(ObjectMapper objectMapper,
                           @Value("${zhigang.ai.embedding.endpoint:}") String endpoint,
                           @Value("${zhigang.ai.embedding.api-key:}") String apiKey,
                           @Value("${zhigang.ai.embedding.model:bge-m3}") String model,
                           @Value("${zhigang.ai.embedding.dimension:1024}") int dimension,
                           @Value("${zhigang.ai.embedding.connect-timeout-ms:3000}") int connectTimeoutMs,
                           @Value("${zhigang.ai.embedding.read-timeout-ms:60000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "bge-m3" : model.trim();
        if (dimension <= 0) throw new IllegalArgumentException("向量维度必须大于 0");
        this.dimension = dimension;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public List<List<Double>> embed(List<String> texts) {
        if (endpoint.isBlank()) throw new IllegalStateException("未配置 EMBEDDING_API_ENDPOINT");
        List<List<Double>> result = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += 16) {
            result.addAll(embedBatch(texts.subList(start, Math.min(texts.size(), start + 16))));
        }
        return List.copyOf(result);
    }

    public boolean available() {
        return !endpoint.isBlank();
    }

    public String model() {
        return model;
    }

    public int dimension() {
        return dimension;
    }

    private List<List<Double>> embedBatch(List<String> texts) {
        var request = restClient.post().uri(endpoint).header(HttpHeaders.CONTENT_TYPE, "application/json");
        if (!apiKey.isBlank()) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        String response;
        try {
            response = request.body(Map.of("model", model, "input", texts)).retrieve().body(String.class);
        } catch (ResourceAccessException exception) {
            throw new DiscoveryExecutionException("EMBEDDING_SERVICE_UNAVAILABLE", "向量服务不可用或响应超时", exception);
        }
        try {
            JsonNode data = objectMapper.readTree(response).path("data");
            if (!data.isArray() || data.size() != texts.size()) throw new IllegalStateException("向量数量不一致");
            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray() || embedding.isEmpty()) throw new IllegalStateException("向量格式无效");
                List<Double> vector = new ArrayList<>();
                embedding.forEach(value -> vector.add(value.asDouble()));
                if (vector.size() != dimension) {
                    throw new DiscoveryExecutionException("EMBEDDING_DIMENSION_INVALID",
                            "向量维度应为 " + dimension + "，实际为 " + vector.size());
                }
                vectors.add(List.copyOf(vector));
            }
            return vectors;
        } catch (DiscoveryExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DiscoveryExecutionException("EMBEDDING_RESPONSE_INVALID", "无法解析向量响应", exception);
        }
    }
}
