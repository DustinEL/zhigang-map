package com.zhigang.platform.emerging;

import com.zhigang.platform.emerging.EmergingDiscoveryModels.ClusterRequest;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.ClusterResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

@Component
public class ClusterWorkerClient {
    private final RestClient restClient;
    private final String endpoint;
    private final int vectorDimension;

    public ClusterWorkerClient(@Value("${zhigang.analysis.emerging.worker-endpoint:http://analysis-worker:8090}") String endpoint,
                               @Value("${zhigang.analysis.emerging.worker-connect-timeout-ms:3000}") int connectTimeoutMs,
                               @Value("${zhigang.analysis.emerging.worker-read-timeout-ms:120000}") int readTimeoutMs,
                               @Value("${zhigang.ai.embedding.dimension:1024}") int vectorDimension) {
        this.endpoint = trimSlash(endpoint);
        this.vectorDimension = vectorDimension;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public ClusterResponse cluster(ClusterRequest request) {
        validateRequest(request);
        ClusterResponse response;
        try {
            response = restClient.post().uri(endpoint + "/v1/clusters")
                    .body(request).retrieve().body(ClusterResponse.class);
        } catch (ResourceAccessException exception) {
            throw new DiscoveryExecutionException("ANALYSIS_WORKER_UNAVAILABLE", "聚类工作器不可用或响应超时", exception);
        } catch (RestClientResponseException exception) {
            throw new DiscoveryExecutionException("ANALYSIS_WORKER_REJECTED_INPUT", "聚类工作器拒绝输入：" + exception.getStatusCode(), exception);
        }
        if (response == null || response.assignments() == null || response.assignments().size() != request.documentIds().size()) {
            throw new DiscoveryExecutionException("ANALYSIS_WORKER_RESPONSE_INVALID", "聚类工作器返回无效结果");
        }
        if (response.vectorDimension() != vectorDimension) {
            throw new DiscoveryExecutionException("EMBEDDING_DIMENSION_INVALID",
                    "聚类工作器返回维度应为 " + vectorDimension + "，实际为 " + response.vectorDimension());
        }
        return response;
    }

    private void validateRequest(ClusterRequest request) {
        if (request.documentIds() == null || request.vectors() == null
                || request.documentIds().size() != request.vectors().size()) {
            throw new DiscoveryExecutionException("ANALYSIS_INPUT_INVALID", "文档与向量数量不一致");
        }
        boolean invalid = request.vectors().stream().anyMatch(vector -> vector == null || vector.size() != vectorDimension);
        if (invalid) {
            throw new DiscoveryExecutionException("EMBEDDING_DIMENSION_INVALID", "聚类输入向量必须为 " + vectorDimension + " 维");
        }
    }

    private static String trimSlash(String value) {
        String cleaned = value == null ? "" : value.trim();
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }
}
