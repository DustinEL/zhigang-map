package com.zhigang.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LocalModelAdapter implements GenerationModelClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalModelAdapter.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final int maxAttempts;
    private final Duration retryDelay;

    public LocalModelAdapter(ObjectMapper objectMapper, String endpoint, String apiKey, String model,
                             int connectTimeoutMs, int readTimeoutMs, int maxTokens) {
        this(objectMapper, endpoint, apiKey, model, connectTimeoutMs, readTimeoutMs, maxTokens, 3, 150);
    }

    @Autowired
    public LocalModelAdapter(ObjectMapper objectMapper,
                             @Value("${zhigang.ai.local.endpoint:http://127.0.0.1:11500/v1/chat/completions}") String endpoint,
                             @Value("${zhigang.ai.local.api-key:}") String apiKey,
                             @Value("${zhigang.ai.local.model:qwen-local}") String model,
                             @Value("${zhigang.ai.local.connect-timeout-ms:3000}") int connectTimeoutMs,
                             @Value("${zhigang.ai.local.read-timeout-ms:120000}") int readTimeoutMs,
                             @Value("${zhigang.ai.local.max-tokens:8192}") int maxTokens,
                             @Value("${zhigang.ai.local.max-attempts:3}") int maxAttempts,
                             @Value("${zhigang.ai.local.retry-delay-ms:150}") int retryDelayMs) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "qwen-local" : model.trim();
        this.maxTokens = maxTokens;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelay = Duration.ofMillis(Math.max(0, retryDelayMs));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<String> generate(String systemPrompt, String userPrompt) {
        return invoke(baseRequest(systemPrompt, userPrompt));
    }

    @Override
    public Optional<String> generateJson(String systemPrompt, String userPrompt) {
        Map<String, Object> body = baseRequest(systemPrompt, userPrompt);
        body.put("response_format", Map.of("type", "json_object"));
        return invoke(body);
    }

    @Override
    public boolean available() {
        return !endpoint.isBlank();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String provider() {
        return "local";
    }

    private Map<String, Object> baseRequest(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("stream", false);
        body.put("max_tokens", maxTokens);
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return body;
    }

    private Optional<String> invoke(Map<String, Object> body) {
        if (!available()) return Optional.empty();
        try {
            return RetryPolicy.execute(() -> invokeOnce(body), maxAttempts, retryDelay,
                    delay -> {
                        try { Thread.sleep(delay.toMillis()); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    });
        } catch (Exception exception) {
            LOGGER.warn("Local model invocation failed after {} attempts: {}", maxAttempts, exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> invokeOnce(Map<String, Object> body) {
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(endpoint)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.ACCEPT, "application/json");
            if (!apiKey.isBlank()) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            byte[] response = request.body(body).retrieve().body(byte[].class);
            if (response == null || response.length == 0) return Optional.empty();
            JsonNode choices = objectMapper.readTree(new String(response, StandardCharsets.UTF_8)).path("choices");
            if (!choices.isArray() || choices.isEmpty()) return Optional.empty();
            if ("length".equals(choices.get(0).path("finish_reason").asText())) {
                LOGGER.warn("Local model response was truncated at max_tokens={}", maxTokens);
                return Optional.empty();
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) return Optional.empty();
            return Optional.of(content.asText());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
