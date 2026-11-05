package com.zhigang.platform.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.analysis.EvidenceVectorStore.EvidenceFragment;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantEvidenceVectorStoreTest {
    @Test
    void localFallbackIndexesOnlyAuthorizedEvidence() {
        QdrantEvidenceVectorStore store = new QdrantEvidenceVectorStore(
                new ObjectMapper().findAndRegisterModules(), "", "", "test", "", "", "bge-m3");
        store.upsert(List.of(
                new EvidenceFragment(1, "授权来源", "企业甲", "AUTHORIZED", "负责 RAG 企业知识库建设", 0, 14, OffsetDateTime.now()),
                new EvidenceFragment(2, "未知来源", "企业乙", "UNKNOWN", "负责 RAG 系统", 0, 8, OffsetDateTime.now())
        ));

        var matches = store.search("RAG 知识库", 5);

        assertThat(store.mode()).isEqualTo("LOCAL_AUTHORIZED_EVIDENCE");
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).fragment().documentId()).isEqualTo(1);
    }
}
