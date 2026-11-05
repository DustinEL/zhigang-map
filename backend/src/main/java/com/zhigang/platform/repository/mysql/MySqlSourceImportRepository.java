package com.zhigang.platform.repository.mysql;

import com.zhigang.platform.source.SourceImportRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Profile("mysql")
@Repository
public class MySqlSourceImportRepository implements SourceImportRepository {
    private final JdbcTemplate jdbcTemplate;

    public MySqlSourceImportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Set<String> findContentHashes(String sourceName) {
        return new HashSet<>(jdbcTemplate.queryForList("""
                SELECT rd.content_hash
                FROM raw_document rd
                JOIN data_source ds ON ds.id = rd.source_id
                WHERE ds.source_name = ? AND rd.deleted_at IS NULL
                """, String.class, sourceName));
    }

    @Override
    public List<SnapshotDocument> findSnapshotDocuments(String snapshotCode) {
        return jdbcTemplate.query("""
                SELECT rd.id, rd.document_title, rd.raw_text, rd.content_hash, rd.published_at, rd.collected_at
                FROM raw_document rd
                JOIN dataset_snapshot ds ON ds.id = rd.snapshot_id
                WHERE ds.snapshot_code = ? AND rd.deleted_at IS NULL
                ORDER BY rd.id
                """, (resultSet, rowNum) -> new SnapshotDocument(
                resultSet.getLong("id"), resultSet.getString("document_title"), resultSet.getString("raw_text"),
                resultSet.getString("content_hash"), dateTime(resultSet.getTimestamp("published_at")),
                dateTime(resultSet.getTimestamp("collected_at"))), snapshotCode);
    }

    @Override
    @Transactional
    public String saveSnapshot(String sourceType, String sourceName, String licenseStatus, List<ImportedDocument> documents) {
        return saveSnapshot(sourceType, sourceName, licenseStatus, "FILE", "团队授权或公开许可数据，仅导入明确允许处理的文件", documents);
    }

    @Override
    @Transactional
    public String saveSnapshot(String sourceType, String sourceName, String licenseStatus, String connectorType,
                               String licenseNote, List<ImportedDocument> documents) {
        String sourceCode = "AUTHORIZED_DIR_" + shortHash(sourceName);
        jdbcTemplate.update("""
                INSERT INTO data_source (source_code, source_name, source_type, license_status, license_note, connector_type)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE source_name = VALUES(source_name), license_status = VALUES(license_status),
                    license_note = VALUES(license_note), connector_type = VALUES(connector_type), updated_at = CURRENT_TIMESTAMP
                """, sourceCode, sourceName, sourceType, licenseStatus, licenseNote, connectorType);
        Long sourceId = jdbcTemplate.queryForObject("SELECT id FROM data_source WHERE source_code = ?", Long.class, sourceCode);
        String snapshotCode = "directory-" + OffsetDateTime.now().toLocalDate() + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO dataset_snapshot (snapshot_code, snapshot_name, status, policy_version, raw_count, effective_count, publishable)
                VALUES (?, ?, 'INGESTED', 'v1.0.0', ?, ?, 0)
                """, snapshotCode, sourceName + " 增量快照", documents.size(), documents.size());
        Long snapshotId = jdbcTemplate.queryForObject("SELECT id FROM dataset_snapshot WHERE snapshot_code = ?", Long.class, snapshotCode);
        for (ImportedDocument document : documents) {
            jdbcTemplate.update("""
                    INSERT INTO raw_document (
                        source_id, snapshot_id, document_title, raw_text, content_hash, published_at,
                        field_completeness, time_weight, is_demo
                    ) VALUES (?, ?, ?, ?, ?, ?, 0.500, 1.000000, 0)
                    """, sourceId, snapshotId, document.fileName(), document.text(), document.contentHash(),
                    Timestamp.from(document.modifiedAt().toInstant()));
        }
        return snapshotCode;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8).toUpperCase();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime dateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}
