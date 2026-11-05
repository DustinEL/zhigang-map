package com.zhigang.platform.source;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Profile("!mysql")
@Repository
public class InMemorySourceImportRepository implements SourceImportRepository {
    private final Map<String, Set<String>> hashesBySource = new ConcurrentHashMap<>();
    private final Map<String, List<SnapshotDocument>> documentsBySnapshot = new ConcurrentHashMap<>();

    @Override
    public Set<String> findContentHashes(String sourceName) {
        return Set.copyOf(hashesBySource.getOrDefault(sourceName, Set.of()));
    }

    @Override
    public String saveSnapshot(String sourceType, String sourceName, String licenseStatus, List<ImportedDocument> documents) {
        hashesBySource.computeIfAbsent(sourceName, ignored -> ConcurrentHashMap.newKeySet())
                .addAll(documents.stream().map(ImportedDocument::contentHash).toList());
        String snapshotCode = "directory-snapshot-" + UUID.randomUUID();
        List<SnapshotDocument> snapshotDocuments = java.util.stream.IntStream.range(0, documents.size())
                .mapToObj(index -> {
                    ImportedDocument document = documents.get(index);
                    return new SnapshotDocument(index + 1L, document.fileName(), document.text(),
                            document.contentHash(), document.modifiedAt(), document.modifiedAt());
                }).toList();
        documentsBySnapshot.put(snapshotCode, snapshotDocuments);
        return snapshotCode;
    }

    @Override
    public List<SnapshotDocument> findSnapshotDocuments(String snapshotCode) {
        return List.copyOf(documentsBySnapshot.getOrDefault(snapshotCode, List.of()));
    }
}
