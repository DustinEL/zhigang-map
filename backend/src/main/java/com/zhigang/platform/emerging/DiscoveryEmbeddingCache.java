package com.zhigang.platform.emerging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DiscoveryEmbeddingCache {
    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String collection;
    private volatile boolean collectionReady;

    public DiscoveryEmbeddingCache(ObjectMapper objectMapper,
                                   @Value("${zhigang.ai.qdrant.endpoint:}") String endpoint,
                                   @Value("${zhigang.ai.qdrant.api-key:}") String apiKey,
                                   @Value("${zhigang.analysis.emerging.embedding-collection:zhigang_emerging_embeddings}") String collection) {
        this.objectMapper = objectMapper;
        this.endpoint = trimSlash(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.collection = collection == null ? "" : collection.trim();
    }

    public Map<String, List<Double>> get(List<String> hashes, String model) {
        if (!available() || hashes.isEmpty()) return Map.of();
        try {
            var request = restClient.post().uri(endpoint + "/collections/" + collection + "/points");
            if (!apiKey.isBlank()) request = request.header("api-key", apiKey);
            String response = request.body(Map.of("ids", hashes.stream().map(this::pointId).toList(),
                    "with_vector", true, "with_payload", true)).retrieve().body(String.class);
            JsonNode result = objectMapper.readTree(response).path("result");
            Map<String, List<Double>> vectors = new LinkedHashMap<>();
            for (JsonNode point : result) {
                JsonNode payload = point.path("payload");
                if (!model.equals(payload.path("model").asText())) continue;
                String hash = payload.path("semanticHash").asText();
                JsonNode vectorNode = point.path("vector");
                if (hash.isBlank() || !vectorNode.isArray()) continue;
                List<Double> vector = new ArrayList<>();
                vectorNode.forEach(value -> vector.add(value.asDouble()));
                vectors.put(hash, List.copyOf(vector));
            }
            return Map.copyOf(vectors);
        } catch (Exception ignored) {
            collectionReady = false;
            return Map.of();
        }
    }

    public void put(Map<String, List<Double>> vectors, String model) {
        if (!available() || vectors.isEmpty()) return;
        try {
            int dimension = vectors.values().iterator().next().size();
            ensureCollection(dimension);
            List<Map<String, Object>> points = vectors.entrySet().stream().map(entry -> Map.of(
                    "id", (Object) pointId(entry.getKey()),
                    "vector", entry.getValue(),
                    "payload", Map.of("semanticHash", entry.getKey(), "model", model)
            )).toList();
            var request = restClient.put().uri(endpoint + "/collections/" + collection + "/points?wait=true");
            if (!apiKey.isBlank()) request = request.header("api-key", apiKey);
            request.body(Map.of("points", points)).retrieve().toBodilessEntity();
        } catch (RuntimeException ignored) {
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

    private String pointId(String hash) {
        String value = hash.replaceAll("[^0-9a-fA-F]", "");
        if (value.length() < 32) value = (value + "0".repeat(32)).substring(0, 32);
        value = value.substring(0, 32).toLowerCase();
        return value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
                + "-" + value.substring(16, 20) + "-" + value.substring(20);
    }

    private boolean available() {
        return !endpoint.isBlank() && !collection.isBlank();
    }

    private static String trimSlash(String value) {
        String cleaned = value == null ? "" : value.trim();
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }
}
