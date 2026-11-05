package com.zhigang.platform.analysis;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceRerankerTest {
    @Test
    void removesDuplicateAnchorsAndPrefersDistinctSources() {
        var duplicate = fragment(1, "同一公司", 0, 10, "2026-01-01T00:00:00+08:00");
        var distinct = fragment(2, "另一公司", 0, 10, "2026-02-01T00:00:00+08:00");
        var matches = List.of(
                new EvidenceVectorStore.EvidenceMatch(duplicate, .91),
                new EvidenceVectorStore.EvidenceMatch(duplicate, .89),
                new EvidenceVectorStore.EvidenceMatch(distinct, .90)
        );

        var result = EvidenceReranker.rerank(matches, 2);

        assertEquals(2, result.size());
        assertEquals(1, result.stream().filter(item -> item.fragment().documentId() == 1).count());
        assertEquals(2, result.get(1).fragment().documentId());
    }

    private static EvidenceVectorStore.EvidenceFragment fragment(long id, String company, int start, int end, String publishedAt) {
        return new EvidenceVectorStore.EvidenceFragment(id, "source", company, "OPEN", "quote", start, end,
                OffsetDateTime.parse(publishedAt));
    }
}
