package com.zhigang.platform.analysis;

import java.time.OffsetDateTime;
import java.util.List;

public interface EvidenceVectorStore {
    void upsert(List<EvidenceFragment> fragments);

    List<EvidenceMatch> search(String query, int limit);

    String mode();

    record EvidenceFragment(
            long documentId,
            String source,
            String company,
            String licenseStatus,
            String quote,
            int startOffset,
            int endOffset,
            OffsetDateTime publishedAt
    ) {
    }

    record EvidenceMatch(EvidenceFragment fragment, double score) {
    }
}
