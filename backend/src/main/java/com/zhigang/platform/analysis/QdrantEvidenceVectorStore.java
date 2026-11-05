package com.zhigang.platform.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.CitationIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class QdrantEvidenceVectorStore implements EvidenceVectorStore {
    private static final Set<String> ALLOWED_LICENSES = Set.of("AUTHORIZED", "OPEN");
    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper;
    private final List<EvidenceFragment> localFragments = new CopyOnWriteArrayList<>();
    private final String qdrantEndpoint;
    private final String qdrantApiKey;
    private final String collection;
    private final String embeddingEndpoint;
    private final String embeddingApiKey;
    private final String embeddingModel;
    private volatile boolean collectionReady;

    public QdrantEvidenceVectorStore(
            ObjectMapper objectMapper,
            @Value("${zhigang.ai.qdrant.endpoint:}") String qdrantEndpoint,
            @Value("${zhigang.ai.qdrant.api-key:}") String qdrantApiKey,
            @Value("${zhigang.ai.qdrant.collection:zhigang_authorized_evidence}") String collection,
            @Value("${zhigang.ai.embedding.endpoint:}") String embeddingEndpoint,
            @Value("${zhigang.ai.embedding.api-key:}") String embeddingApiKey,
            @Value("${zhigang.ai.embedding.model:bge-m3}") String embeddingModel
    ) {
        this.objectMapper = objectMapper;
        this.qdrantEndpoint = trimSlash(qdrantEndpoint);
        this.qdrantApiKey = clean(qdrantApiKey);
        this.collection = clean(collection);
        this.embeddingEndpoint = clean(embeddingEndpoint);
        this.embeddingApiKey = clean(embeddingApiKey);
        this.embeddingModel = clean(embeddingModel).isBlank() ? "bge-m3" : clean(embeddingModel);
    }

    @Override
    public void upsert(List<EvidenceFragment> fragments) {
        List<EvidenceFragment> authorized = fragments == null ? List.of() : fragments.stream()
                .filter(this::authorizedAndValid).toList();
        if (authorized.isEmpty()) return;
        mergeLocal(authorized);
        if (!remoteAvailable()) return;
        try {
            List<List<Double>> vectors = embeddings(authorized.stream().map(EvidenceFragment::quote).toList());
            ensureCollection(vectors.get(0).size());
            List<Map<String, Object>> points = new ArrayList<>();
            for (int index = 0; index < authorized.size(); index++) {
                EvidenceFragment fragment = authorized.get(index);
                points.add(Map.of(
                        "id", pointId(fragment),
                        "vector", vectors.get(index),
                        "payload", payload(fragment)
                ));
            }
            var request = restClient.put().uri(qdrantEndpoint + "/collections/" + collection + "/points?wait=true");
            if (!qdrantApiKey.isBlank()) request = request.header("api-key", qdrantApiKey);
            request.body(Map.of("points", points)).retrieve().toBodilessEntity();
        } catch (RuntimeException ignored) {
            collectionReady = false;
        }
    }

    @Override
    public List<EvidenceMatch> search(String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        if (query == null || query.isBlank()) return List.of();
        if (remoteAvailable()) {
            try {
                List<Double> vector = embeddings(List.of(query)).get(0);
                ensureCollection(vector.size());
                var request = restClient.post().uri(qdrantEndpoint + "/collections/" + collection + "/points/query");
                if (!qdrantApiKey.isBlank()) request = request.header("api-key", qdrantApiKey);
                String response = request.body(Map.of("query", vector, "limit", safeLimit,
                                "with_payload", true, "score_threshold", 0.35))
                        .retrieve().body(String.class);
                List<EvidenceMatch> matches = parseMatches(response);
                if (!matches.isEmpty()) return EvidenceReranker.rerank(matches, safeLimit);
            } catch (RuntimeException ignored) {
                collectionReady = false;
            }
        }
        return EvidenceReranker.rerank(localSearch(query, safeLimit), safeLimit);
    }

    @Override
    public String mode() {
        return remoteAvailable() ? "QDRANT_BGE_M3" : "LOCAL_AUTHORIZED_EVIDENCE";
    }

    private void ensureCollection(int vectorSize) {
        if (collectionReady) return;
        synchronized (this) {
            if (collectionReady) return;
            try {
                var request = restClient.get().uri(qdrantEndpoint + "/collections/" + collection);
                if (!qdrantApiKey.isBlank()) request = request.header("api-key", qdrantApiKey);
                request.retrieve().toBodilessEntity();
            } catch (RuntimeException notFound) {
                var request = restClient.put().uri(qdrantEndpoint + "/collections/" + collection);
                if (!qdrantApiKey.isBlank()) request = request.header("api-key", qdrantApiKey);
                request.body(Map.of("vectors", Map.of("size", vectorSize, "distance", "Cosine")))
                        .retrieve().toBodilessEntity();
            }
            collectionReady = true;
        }
    }

    private List<List<Double>> embeddings(List<String> input) {
        Map<String, Object> body = Map.of("model", embeddingModel, "input", input);
        var request = restClient.post().uri(embeddingEndpoint).header(HttpHeaders.CONTENT_TYPE, "application/json");
        if (!embeddingApiKey.isBlank()) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + embeddingApiKey);
        String response = request.body(body).retrieve().body(String.class);
        try {
            JsonNode data = objectMapper.readTree(response).get("data");
            if (data == null || !data.isArray() || data.size() != input.size()) {
                throw new IllegalStateException("向量服务返回数量不一致");
            }
            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.get("embedding");
                if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                    throw new IllegalStateException("向量服务返回格式无效");
                }
                List<Double> vector = new ArrayList<>();
                embedding.forEach(value -> vector.add(value.asDouble()));
                vectors.add(List.copyOf(vector));
            }
            return List.copyOf(vectors);
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析向量服务响应", exception);
        }
    }

    private List<EvidenceMatch> parseMatches(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode result = root.get("result");
            JsonNode points = result != null && result.isObject() ? result.get("points") : result;
            if (points == null || !points.isArray()) return List.of();
            List<EvidenceMatch> matches = new ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.get("payload");
                if (payload == null || !payload.isObject()) continue;
                EvidenceFragment fragment = objectMapper.treeToValue(payload, EvidenceFragment.class);
                if (authorizedAndValid(fragment)
                        && pointId(fragment).equals(point.path("id").asText())) {
                    matches.add(new EvidenceMatch(fragment, point.path("score").asDouble()));
                }
            }
            return List.copyOf(matches);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<EvidenceMatch> localSearch(String query, int limit) {
        Set<String> queryTokens = tokens(query);
        return localFragments.stream()
                .map(fragment -> new EvidenceMatch(fragment, overlap(queryTokens, tokens(fragment.quote()))))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingDouble(EvidenceMatch::score).reversed())
                .limit(limit)
                .toList();
    }

    private double overlap(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        long intersection = left.stream().filter(right::contains).count();
        return intersection / (double) Math.max(1, left.size());
    }

    private Set<String> tokens(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (normalized.isBlank()) return Set.of();
        Set<String> tokens = new java.util.LinkedHashSet<>(List.of(normalized.split("\\s+")));
        String compact = normalized.replace(" ", "");
        for (int index = 0; index + 2 <= compact.length(); index++) tokens.add(compact.substring(index, index + 2));
        return Set.copyOf(tokens);
    }

    private void mergeLocal(List<EvidenceFragment> fragments) {
        Map<String, EvidenceFragment> merged = new LinkedHashMap<>();
        localFragments.forEach(fragment -> merged.put(pointId(fragment), fragment));
        fragments.forEach(fragment -> merged.put(pointId(fragment), fragment));
        localFragments.clear();
        localFragments.addAll(merged.values());
    }

    private boolean authorizedAndValid(EvidenceFragment fragment) {
        return fragment != null && ALLOWED_LICENSES.contains(clean(fragment.licenseStatus()).toUpperCase(Locale.ROOT))
                && fragment.documentId() > 0 && fragment.quote() != null && !fragment.quote().isBlank()
                && fragment.startOffset() >= 0 && fragment.endOffset() > fragment.startOffset();
    }

    private Map<String, Object> payload(EvidenceFragment fragment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", fragment.documentId());
        payload.put("source", clean(fragment.source()));
        payload.put("company", clean(fragment.company()));
        payload.put("licenseStatus", clean(fragment.licenseStatus()).toUpperCase(Locale.ROOT));
        payload.put("quote", fragment.quote());
        payload.put("startOffset", fragment.startOffset());
        payload.put("endOffset", fragment.endOffset());
        if (fragment.publishedAt() != null) payload.put("publishedAt", fragment.publishedAt().toString());
        return payload;
    }

    private String pointId(EvidenceFragment fragment) {
        return CitationIds.fromAnchor(fragment.documentId(), fragment.startOffset(), fragment.endOffset());
    }

    private boolean remoteAvailable() {
        return !qdrantEndpoint.isBlank() && !collection.isBlank() && !embeddingEndpoint.isBlank();
    }

    private static String trimSlash(String value) {
        String cleaned = clean(value);
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
