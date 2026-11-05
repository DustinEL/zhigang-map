package com.zhigang.platform.career;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Component
public class ResumeOcrClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;

    public ResumeOcrClient(ObjectMapper objectMapper,
                           @Value("${zhigang.resume.ocr-endpoint:http://127.0.0.1:8090/v1/resume-ocr}") String endpoint,
                           @Value("${zhigang.resume.ocr-connect-timeout-ms:3000}") int connectTimeoutMs,
                           @Value("${zhigang.resume.ocr-read-timeout-ms:120000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public OcrResult extractPdf(byte[] content, String fileName) {
        if (endpoint.isBlank()) {
            throw new BusinessException("RESUME_OCR_NOT_CONFIGURED", "扫描版 PDF 需要配置 PaddleOCR 服务");
        }
        try {
            String response = restClient.post().uri(endpoint)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(Map.of("fileName", fileName, "contentBase64", Base64.getEncoder().encodeToString(content)))
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(response);
            String text = root.path("text").asText("").trim();
            if (text.isBlank()) throw new IllegalStateException("OCR 未返回文字");
            return new OcrResult(text, root.path("pageCount").asInt(0), root.path("model").asText("PaddleOCR"));
        } catch (BusinessException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new BusinessException("RESUME_OCR_UNAVAILABLE", "PaddleOCR 服务不可用或响应超时");
        } catch (Exception exception) {
            throw new BusinessException("RESUME_OCR_FAILED", "扫描版 PDF OCR 失败，请确认文件清晰且未损坏");
        }
    }

    public record OcrResult(String text, int pageCount, String model) {
    }
}
