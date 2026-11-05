package com.zhigang.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LocalModelAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void jsonGenerationUsesLocalOpenAiContractWithoutAuthorizationWhenApiKeyIsBlank() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        LocalModelAdapter adapter = adapter("");

        assertThat(adapter.generateJson("系统", "用户")).contains("{\"ok\":true}");

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("qwen-local");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.1);
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("系统");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("用户");
        assertThat(authorization.get()).isNull();
        assertThat(adapter.available()).isTrue();
        assertThat(adapter.model()).isEqualTo("qwen-local");
        assertThat(adapter.provider()).isEqualTo("local");
    }

    @Test
    void plainGenerationOmitsJsonResponseFormatAndReadsFirstChoice() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"第一项\"}},{\"message\":{\"content\":\"第二项\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThat(adapter("local-key").generate("系统", "用户")).contains("第一项");
        assertThat(objectMapper.readTree(requestBody.get()).has("response_format")).isFalse();
    }

    @Test
    void nonSuccessfulResponseReturnsEmpty() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        assertThat(adapter("").generate("系统", "用户")).isEmpty();
    }

    @Test
    void responseWithoutNonBlankFirstChoiceReturnsEmpty() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThat(adapter("").generate("系统", "用户")).isEmpty();
    }

    @Test
    void lengthLimitedResponseIsRejectedInsteadOfReturningTruncatedJson() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"length\","
                    + "\"message\":{\"content\":\"{\\\"facts\\\":[\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThat(adapter("").generateJson("system", "user")).isEmpty();
    }

    private LocalModelAdapter adapter(String apiKey) {
        return new LocalModelAdapter(objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                apiKey, "qwen-local", 1000, 1000, 8192);
    }
}
