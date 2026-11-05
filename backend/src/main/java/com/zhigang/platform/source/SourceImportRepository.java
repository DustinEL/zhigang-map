package com.zhigang.platform.source;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public interface SourceImportRepository {
    Set<String> findContentHashes(String sourceName);

    String saveSnapshot(String sourceType, String sourceName, String licenseStatus, List<ImportedDocument> documents);

    List<SnapshotDocument> findSnapshotDocuments(String snapshotCode);

    default String saveSnapshot(String sourceType, String sourceName, String licenseStatus, String connectorType,
                                String licenseNote, List<ImportedDocument> documents) {
        return saveSnapshot(sourceType, sourceName, licenseStatus, documents);
    }

    record ImportedDocument(String fileName, String text, String contentHash, OffsetDateTime modifiedAt) {
    }

    record SnapshotDocument(long id, String title, String text, String contentHash,
                            OffsetDateTime publishedAt, OffsetDateTime collectedAt) {
    }
}
