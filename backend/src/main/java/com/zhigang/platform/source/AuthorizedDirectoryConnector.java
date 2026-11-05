package com.zhigang.platform.source;

import com.zhigang.platform.analysis.AuthorizedEvidenceIndexer;
import com.zhigang.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import com.zhigang.platform.source.SourceImportRepository.ImportedDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AuthorizedDirectoryConnector {
    private static final long MAX_DOCUMENT_SIZE = 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "json");
    private final SourceImportRepository repository;
    private final String configuredDirectory;
    private AuthorizedEvidenceIndexer evidenceIndexer;
    private volatile DirectoryScanResult lastResult;

    public AuthorizedDirectoryConnector(
            SourceImportRepository repository,
            @Value("${zhigang.source.authorized-directory:}") String configuredDirectory
    ) {
        this.repository = repository;
        this.configuredDirectory = configuredDirectory == null ? "" : configuredDirectory.trim();
    }

    @Autowired(required = false)
    void configureEvidenceIndexer(AuthorizedEvidenceIndexer evidenceIndexer) {
        this.evidenceIndexer = evidenceIndexer;
    }

    @Scheduled(fixedDelayString = "${zhigang.source.scan-delay-ms:300000}")
    public void scheduledScan() {
        if (configuredDirectory.isBlank()) return;
        try {
            scan("授权岗位目录", "AUTHORIZED");
        } catch (RuntimeException ignored) {
        }
    }

    public DirectoryScanResult scan(String sourceName, String licenseStatus) {
        if (configuredDirectory.isBlank()) {
            throw new BusinessException("IMPORT_DIRECTORY_NOT_CONFIGURED", "请先设置 ZHIGANG_IMPORT_DIRECTORY 授权目录");
        }
        String normalizedLicense = licenseStatus == null ? "" : licenseStatus.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTHORIZED", "OPEN").contains(normalizedLicense)) {
            throw new BusinessException("IMPORT_LICENSE_REQUIRED", "授权目录只允许 AUTHORIZED 或 OPEN 数据");
        }
        Path root = Path.of(configuredDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException("IMPORT_DIRECTORY_NOT_FOUND", "授权目录不存在或不是文件夹");
        }
        Set<String> knownHashes = repository.findContentHashes(sourceName);
        List<ImportedDocument> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try (var paths = Files.walk(root, 4)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().forEach(path -> {
                String relative = root.relativize(path).toString();
                try {
                    String extension = extension(path);
                    if (!ALLOWED_EXTENSIONS.contains(extension)) {
                        skipped.add(relative + "：格式不支持");
                        return;
                    }
                    long size = Files.size(path);
                    if (size == 0 || size > MAX_DOCUMENT_SIZE) {
                        skipped.add(relative + "：文件为空或超过 1MB");
                        return;
                    }
                    byte[] content = Files.readAllBytes(path);
                    String hash = hash(content);
                    if (knownHashes.contains(hash) || accepted.stream().anyMatch(item -> item.contentHash().equals(hash))) {
                        skipped.add(relative + "：内容未变化");
                        return;
                    }
                    String text = new String(content, StandardCharsets.UTF_8).trim();
                    if (text.length() < 20) {
                        skipped.add(relative + "：有效内容过短");
                        return;
                    }
                    OffsetDateTime modifiedAt = Files.getLastModifiedTime(path).toInstant().atOffset(ZoneOffset.ofHours(8));
                    accepted.add(new ImportedDocument(relative, text, hash, modifiedAt));
                } catch (IOException exception) {
                    failures.add(relative + "：" + exception.getMessage());
                }
            });
        } catch (IOException exception) {
            throw new BusinessException("IMPORT_DIRECTORY_SCAN_FAILED", "授权目录扫描失败：" + exception.getMessage());
        }
        String snapshotId = accepted.isEmpty() ? null : repository.saveSnapshot("RECRUITMENT_JD", sourceName, normalizedLicense, accepted);
        int indexedEvidenceCount = snapshotId == null || evidenceIndexer == null
                ? 0 : evidenceIndexer.index(sourceName, normalizedLicense, accepted);
        DirectoryScanResult result = new DirectoryScanResult(sourceName, root.toString(), snapshotId, accepted.size(), skipped.size(),
                failures.size(), indexedEvidenceCount, List.copyOf(skipped), List.copyOf(failures),
                failures.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", OffsetDateTime.now());
        lastResult = result;
        return result;
    }

    public ConnectorStatus status() {
        return new ConnectorStatus(!configuredDirectory.isBlank(), configuredDirectory, lastResult);
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record DirectoryScanResult(String sourceName, String directory, String snapshotId, int acceptedCount,
                                      int skippedCount, int failedCount, int indexedEvidenceCount,
                                      List<String> skipped, List<String> failures,
                                      String status, OffsetDateTime completedAt) {
    }

    public record ConnectorStatus(boolean enabled, String directory, DirectoryScanResult lastResult) {
    }
}
