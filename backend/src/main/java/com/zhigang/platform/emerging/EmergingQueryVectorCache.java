package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class EmergingQueryVectorCache {
    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String collection;
    private volatile boolean collectionReady;

    public EmergingQueryVectorCache(ObjectMapper objectMapper,
                                    @Value("${zhigang.ai.qdrant.endpoint:}") String endpoint,
                                    @Value("${zhigang.ai.qdrant.api-key:}") String apiKey,
                                    @Value("${zhigang.analysis.emerging.query-collection:zhigang_emerging_queries}") String collection) {
        this.objectMapper = objectMapper;
        this.endpoint = trimSlash(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.collection = collection == null ? "" : collection.trim();
    }

    public Optional<QueryCacheMatch> search(List<Double> vector, String model, double threshold) {
        if (!available() || vector.isEmpty()) return Optional.empty();
        try {
            var request = restClient.post().uri(endpoint + "/collections/" + collection + "/points/search");
            if (!apiKey.isBlank()) request = request.header("api-key", apiKey);
            String response = request.body(Map.of(
                    "vector", vector,
                    "limit", 1,
                    "score_threshold", threshold,
                    "with_payload", true
            )).retrieve().body(String.class);
            JsonNode result = objectMapper.readTree(response).path("result");
            if (!result.isArray() || result.isEmpty()) return Optional.empty();
            JsonNode first = result.get(0);
            JsonNode payload = first.path("payload");
            if (!model.equals(payload.path("model").asText())) return Optional.empty();
            long discoveryId = payload.path("discoveryId").asLong(0);
            if (discoveryId <= 0) return Optional.empty();
            return Optional.of(new QueryCacheMatch(discoveryId, first.path("score").asDouble(0),
                    payload.path("normalizedQuery").asText()));
        } catch (Exception ignored) {
            collectionReady = false;
            return Optional.empty();
        }
    }

    public void put(long discoveryId, String normalizedQuery, List<Double> vector, String model) {
        if (!available() || discoveryId <= 0 || vector.isEmpty()) return;
        try {
            ensureCollection(vector.size());
            var request = restClient.put().uri(endpoint + "/collections/" + collection + "/points?wait=true");
            if (!apiKey.isBlank()) request = request.header("api-key", apiKey);
            request.body(Map.of("points", List.of(Map.of(
                    "id", discoveryId,
                    "vector", vector,
                    "payload", Map.of("discoveryId", discoveryId, "normalizedQuery", normalizedQuery, "model", model)
            )))).retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            collectionReady = false;
        }
    }

    private void ensureCollection(int dimension) {
        if (collectionReady) return;
        synchronized (this) {
            if (collectionReady) return;
            try {
                var request = restClient.get().uri(endpoint + "/collections/" + collection);
                if (!apiKey.isBlank()) request = request.header("api-key", apiKey);
                request.retrieve().toBodilessEntity();
            } catch (RuntimeException missing) {
                var request = restClient.put().uri(endpoint + "/collections/" + collection);
                if (!apiKey.isBlank()) request = request.header("api-key", apiKey);
                request.body(Map.of("vectors", Map.of("size", dimension, "distance", "Cosine")))
                        .retrieve().toBodilessEntity();
            }
            collectionReady = true;
        }
    }

    private boolean available() {
        return !endpoint.isBlank() && !collection.isBlank();
    }

    private static String trimSlash(String value) {
        String cleaned = value == null ? "" : value.trim();
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    public record QueryCacheMatch(long discoveryId, double similarity, String normalizedQuery) {
    }
}
