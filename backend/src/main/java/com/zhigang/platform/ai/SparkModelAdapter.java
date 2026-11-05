package com.zhigang.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SparkModelAdapter implements GenerationModelClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SparkModelAdapter.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int maxAttempts;
    private final Duration retryDelay;

    public SparkModelAdapter(ObjectMapper objectMapper,
                             @Value("${zhigang.ai.spark.endpoint:}") String endpoint,
                             @Value("${zhigang.ai.spark.api-key:}") String apiKey,
                             @Value("${zhigang.ai.spark.model:generalv3}") String model,
                             @Value("${zhigang.ai.spark.connect-timeout-ms:5000}") int connectTimeoutMs,
                             @Value("${zhigang.ai.spark.read-timeout-ms:90000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "generalv3" : model.trim();
        this.maxAttempts = 3;
        this.retryDelay = Duration.ofMillis(150);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean available() {
        return !endpoint.isBlank() && !apiKey.isBlank();
    }

    @Override
    public String model() {
        return model;
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
    public String provider() {
        return "spark";
    }

    /**
     * 使用星火 HTTP 接口自带的联网搜索插件，并要求最终响应为 JSON 对象。
     * 搜索结果只作为生成证据，调用方仍需校验 URL、来源数量和字段引用关系。
     */
    public Optional<WebSearchResult> generateWithWebSearch(String systemPrompt, String userPrompt) {
        Map<String, Object> body = baseRequest(systemPrompt, userPrompt);
        body.put("stream", true);
        body.put("tools", List.of(Map.of(
                "type", "web_search",
                "web_search", Map.of("enable", true, "show_ref_label", true, "search_mode", "deep")
        )));
        body.put("response_format", Map.of("type", "json_object"));
        if (!available()) return Optional.empty();
        try {
            String response = execute(body);
            String content = streamedContent(response);
            if (content.isBlank()) return Optional.empty();
            String answer = "";
            List<WebSearchReference> references = new ArrayList<>();
            LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
            for (String value : jsonValues(content)) {
                List<WebSearchReference> parsed = references(value);
                if (!parsed.isEmpty()) {
                    parsed.stream().filter(item -> seenUrls.add(item.url())).forEach(references::add);
                } else {
                    answer = value;
                }
            }
            return answer.isBlank() ? Optional.empty() : Optional.of(new WebSearchResult(answer, List.copyOf(references)));
        } catch (Exception exception) {
            LOGGER.warn("Spark web-search invocation failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> baseRequest(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("user", "zhigang-emerging-discovery");
        body.put("temperature", 0.1);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return body;
    }

    private Optional<String> invoke(Map<String, Object> body) {
        if (!available()) return Optional.empty();
        try {
            String response = RetryPolicy.execute(() -> execute(body), maxAttempts, retryDelay,
                    delay -> {
                        try { Thread.sleep(delay.toMillis()); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    });
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");
            if (!choices.isArray()) return Optional.empty();
            String selected = "";
            for (JsonNode choice : choices) {
                if ("length".equals(choice.path("finish_reason").asText())) {
                    LOGGER.warn("Spark model response was truncated: model={}", model);
                    return Optional.empty();
                }
                JsonNode content = choice.path("message").path("content");
                if (content.isTextual() && !content.asText().isBlank()) selected = content.asText();
            }
            return selected.isBlank() ? Optional.empty() : Optional.of(selected);
        } catch (Exception exception) {
            LOGGER.warn("Spark model invocation failed after {} attempts: {}", maxAttempts, exception.getMessage());
            return Optional.empty();
        }
    }

    private String execute(Map<String, Object> body) {
        byte[] response = restClient.post().uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.ACCEPT, "text/event-stream, application/json")
                .body(body).retrieve().body(byte[].class);
        return response == null ? "" : new String(response, StandardCharsets.UTF_8);
    }

    private String streamedContent(String response) throws Exception {
        String cleaned = response == null ? "" : response.trim();
        if (!cleaned.startsWith("data:")) {
            JsonNode root = objectMapper.readTree(cleaned);
            return choicesContent(root, "message");
        }
        StringBuilder content = new StringBuilder();
        for (String line : cleaned.split("\\R")) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring("data:".length()).trim();
            if (data.isBlank() || "[DONE]".equals(data)) continue;
            JsonNode event = objectMapper.readTree(data);
            content.append(choicesContent(event, "delta"));
        }
        return content.toString();
    }

    private String choicesContent(JsonNode root, String messageField) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) return "";
        StringBuilder content = new StringBuilder();
        for (JsonNode choice : choices) {
            JsonNode value = choice.path(messageField).path("content");
            if (value.isTextual()) content.append(value.asText());
        }
        return content.toString();
    }

    private List<String> jsonValues(String content) {
        List<String> values = new ArrayList<>();
        int start = -1;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{' || current == '[') {
                if (depth++ == 0) start = index;
            } else if ((current == '}' || current == ']') && depth > 0 && --depth == 0 && start >= 0) {
                values.add(content.substring(start, index + 1));
                start = -1;
            }
        }
        return List.copyOf(values);
    }

    private List<WebSearchReference> references(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isArray()) return List.of();
            List<WebSearchReference> result = new ArrayList<>();
            for (JsonNode item : node) {
                String url = item.path("url").asText("").trim();
                String title = item.path("title").asText("").trim();
                if (!url.isBlank() && !title.isBlank()) {
                    result.add(new WebSearchReference(item.path("index").asInt(result.size() + 1), url, title));
                }
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public record WebSearchResult(String content, List<WebSearchReference> references) {
    }

    public record WebSearchReference(int index, String url, String title) {
    }
}
