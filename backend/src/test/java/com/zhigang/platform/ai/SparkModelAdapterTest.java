package com.zhigang.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SparkModelAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void webSearchRequestUsesOfficialToolAndJsonContract() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"[{\\\"index\\\":1,\\\"url\\\":\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"\\\"https://jobs.example.cn/a\\\",\\\"title\\\":\\\"测试岗位\\\"}]\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"classification\\\":\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"\\\"EMERGING_ROLE\\\"}\"}}]}\n\n"
                    + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        SparkModelAdapter adapter = new SparkModelAdapter(objectMapper, endpoint, "test-api-key", "generalv3", 1000, 1000);

        Optional<SparkModelAdapter.WebSearchResult> result = adapter.generateWithWebSearch("系统提示", "提示词工程师");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content()).isEqualTo("{\"classification\":\"EMERGING_ROLE\"}");
        assertThat(result.orElseThrow().references()).singleElement().satisfies(reference -> {
            assertThat(reference.url()).isEqualTo("https://jobs.example.cn/a");
            assertThat(reference.title()).isEqualTo("测试岗位");
        });
        assertThat(authorization.get()).isEqualTo("Bearer test-api-key");
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("generalv3");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        JsonNode webSearch = body.path("tools").get(0);
        assertThat(webSearch.path("type").asText()).isEqualTo("web_search");
        assertThat(webSearch.path("web_search").path("enable").asBoolean()).isTrue();
        assertThat(webSearch.path("web_search").path("show_ref_label").asBoolean()).isTrue();
        assertThat(webSearch.path("web_search").path("search_mode").asText()).isEqualTo("deep");
    }

    @Test
    void structuredGenerationRequestsJsonObject() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        GenerationModelClient adapter = new SparkModelAdapter(objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "test-api-key", "4.0Ultra", 1000, 1000);

        assertThat(adapter.generateJson("系统提示", "用户提示")).contains("{\"ok\":true}");
        assertThat(adapter.provider()).isEqualTo("spark");
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("stream").asBoolean()).isFalse();
    }

    @Test
    void lengthLimitedStructuredResponseIsRejected() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"length\","
                    + "\"message\":{\"content\":\"{\\\"facts\\\":[\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        GenerationModelClient adapter = new SparkModelAdapter(objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "test-api-key", "4.0Ultra", 1000, 1000);

        assertThat(adapter.generateJson("system", "user")).isEmpty();
    }
}
