package com.zhigang.platform.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.analysis.EvidenceVectorStore.EvidenceFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_QDRANT_E2E", matches = "true")
class QdrantEvidenceVectorStoreE2ETest {
    @Test
    void bgeM3EmbeddingsAreStoredAndRetrievedFromQdrant() {
        String qdrantEndpoint = required("QDRANT_E2E_ENDPOINT");
        String embeddingEndpoint = required("EMBEDDING_E2E_ENDPOINT");
        String collection = System.getenv().getOrDefault(
                "QDRANT_E2E_COLLECTION", "zhigang_authorized_evidence_e2e_bge_m3");
        QdrantEvidenceVectorStore store = new QdrantEvidenceVectorStore(
                new ObjectMapper().findAndRegisterModules(), qdrantEndpoint, "", collection,
                embeddingEndpoint, "", "bge-m3");
        String firstQuote = "向量审计协议ZX-42要求构建企业级 RAG 检索增强生成系统与证据链。";
        String secondQuote = "向量审计协议ZX-42用于验证授权证据的跨企业独立性和引用一致性。";
        EvidenceFragment first = new EvidenceFragment(900001L, "P0-2-E2E-A", "企业甲", "AUTHORIZED",
                firstQuote, 0, firstQuote.length(), OffsetDateTime.now());
        EvidenceFragment second = new EvidenceFragment(900002L, "P0-2-E2E-B", "企业乙", "AUTHORIZED",
                secondQuote, 0, secondQuote.length(), OffsetDateTime.now());

        store.upsert(List.of(first, second));
        var matches = store.search("向量审计协议ZX-42 企业级 RAG 授权证据", 3);

        assertThat(store.mode()).isEqualTo("QDRANT_BGE_M3");
        assertThat(matches).extracting(match -> match.fragment().documentId())
                .contains(first.documentId(), second.documentId());
        assertThat(matches).allMatch(match -> match.score() > 0.35);
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value;
    }
}
