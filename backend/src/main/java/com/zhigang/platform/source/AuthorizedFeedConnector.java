package com.zhigang.platform.source;

import com.zhigang.platform.analysis.AuthorizedEvidenceIndexer;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.source.SourceImportRepository.ImportedDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AuthorizedFeedConnector {
    private static final int MAX_RESPONSE_SIZE = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_LICENSES = Set.of("AUTHORIZED", "OPEN");
    private final SourceImportRepository repository;
    private final FeedFetcher fetcher;
    private final String feedUrl;
    private final String sourceName;
    private final String sourceType;
    private final String licenseStatus;
    private final String licenseNote;
    private final long scanDelayMs;
    private AuthorizedEvidenceIndexer evidenceIndexer;
    private volatile FeedStatus lastStatus;

    @Autowired
    public AuthorizedFeedConnector(
            SourceImportRepository repository,
            @Value("${zhigang.source.rss.url:}") String feedUrl,
            @Value("${zhigang.source.rss.source-name:}") String sourceName,
            @Value("${zhigang.source.rss.source-type:RECRUITMENT_JD}") String sourceType,
            @Value("${zhigang.source.rss.license-status:}") String licenseStatus,
            @Value("${zhigang.source.rss.license-note:}") String licenseNote,
            @Value("${zhigang.source.rss.scan-delay-ms:1800000}") long scanDelayMs
    ) {
        this(repository, new JdkFeedFetcher(), feedUrl, sourceName, sourceType, licenseStatus, licenseNote, scanDelayMs);
    }

    AuthorizedFeedConnector(SourceImportRepository repository, FeedFetcher fetcher, String feedUrl, String sourceName,
                            String sourceType, String licenseStatus, String licenseNote, long scanDelayMs) {
        this.repository = repository;
        this.fetcher = fetcher;
        this.feedUrl = clean(feedUrl);
        this.sourceName = clean(sourceName);
        this.sourceType = clean(sourceType);
        this.licenseStatus = clean(licenseStatus).toUpperCase(Locale.ROOT);
        this.licenseNote = clean(licenseNote);
        this.scanDelayMs = scanDelayMs;
    }

    @Autowired(required = false)
    void configureEvidenceIndexer(AuthorizedEvidenceIndexer evidenceIndexer) {
        this.evidenceIndexer = evidenceIndexer;
    }

    @Scheduled(fixedDelayString = "${zhigang.source.rss.scan-delay-ms:1800000}")
    public void scheduledScan() {
        if (!enabled()) return;
        try {
            scan();
        } catch (RuntimeException ignored) {
        }
    }

    public FeedScanResult scan() {
        validateConfiguration();
        OffsetDateTime startedAt = OffsetDateTime.now();
        try {
            FeedResponse response = fetcher.fetch(URI.create(feedUrl));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new FeedFetchException(response.statusCode(), "订阅源返回 HTTP " + response.statusCode());
            }
            List<ImportedDocument> parsed = parse(response.body());
            Set<String> knownHashes = repository.findContentHashes(sourceName);
            List<ImportedDocument> accepted = parsed.stream().filter(item -> !knownHashes.contains(item.contentHash())).toList();
            String snapshotId = accepted.isEmpty() ? null : repository.saveSnapshot(sourceType, sourceName, licenseStatus,
                    "RSS", licenseNote, accepted);
            int indexedEvidenceCount = snapshotId == null || evidenceIndexer == null
                    ? 0 : evidenceIndexer.index(sourceName, licenseStatus, accepted);
            FeedScanResult result = new FeedScanResult(snapshotId, accepted.size(), parsed.size() - accepted.size(),
                    indexedEvidenceCount, response.statusCode(), "COMPLETED", startedAt, OffsetDateTime.now());
            lastStatus = status(result, null, result.completedAt());
            return result;
        } catch (FeedFetchException exception) {
            lastStatus = status(null, exception.getMessage(), null, exception.statusCode(), startedAt);
            throw new BusinessException("RSS_FETCH_FAILED", exception.getMessage());
        } catch (Exception exception) {
            String message = "RSS/Atom 解析失败，请检查订阅地址和内容格式";
            lastStatus = status(null, message, null, null, startedAt);
            throw new BusinessException("RSS_PARSE_FAILED", message, exception);
        }
    }

    public FeedStatus status() {
        if (lastStatus != null) return lastStatus;
        return new FeedStatus(enabled(), feedUrl, sourceName, sourceType, licenseStatus, licenseNote, scanDelayMs,
                null, null, null, null, null);
    }

    private void validateConfiguration() {
        if (!enabled()) throw new BusinessException("RSS_CONNECTOR_NOT_CONFIGURED", "请先配置官方 HTTPS RSS/Atom 地址和许可说明");
        URI uri;
        try {
            uri = URI.create(feedUrl);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("INVALID_RSS_URL", "RSS/Atom 地址格式无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new BusinessException("INVALID_RSS_URL", "RSS/Atom 只允许不含账号信息的 HTTPS 地址");
        }
        if (!ALLOWED_LICENSES.contains(licenseStatus) || licenseNote.isBlank()) {
            throw new BusinessException("RSS_LICENSE_REQUIRED", "在线来源必须标明 AUTHORIZED/OPEN 和许可说明");
        }
    }

    private boolean enabled() {
        return !feedUrl.isBlank() && !sourceName.isBlank();
    }

    private List<ImportedDocument> parse(byte[] body) throws Exception {
        if (body.length == 0 || body.length > MAX_RESPONSE_SIZE) {
            throw new IllegalArgumentException("feed size");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void fatalError(SAXParseException exception) throws SAXParseException {
                throw exception;
            }
        });
        Document document = builder.parse(new ByteArrayInputStream(body));
        NodeList entries = document.getElementsByTagName("item");
        if (entries.getLength() == 0) entries = document.getElementsByTagNameNS("*", "entry");
        List<ImportedDocument> documents = new ArrayList<>();
        for (int index = 0; index < entries.getLength(); index++) {
            Element entry = (Element) entries.item(index);
            String title = firstText(entry, "title");
            String content = firstText(entry, "description", "summary", "content");
            String text = (title + "\n" + content).trim();
            if (text.length() < 20 || text.length() > 100_000) continue;
            documents.add(new ImportedDocument(title.isBlank() ? "feed-entry-" + (index + 1) : title,
                    text, hash(text), publishedAt(entry)));
        }
        return List.copyOf(documents);
    }

    private String firstText(Element root, String... names) {
        for (String name : names) {
            NodeList nodes = root.getElementsByTagNameNS("*", name);
            if (nodes.getLength() == 0) nodes = root.getElementsByTagName(name);
            for (int index = 0; index < nodes.getLength(); index++) {
                Node node = nodes.item(index);
                String text = node.getTextContent();
                if (text != null && !text.isBlank()) return text.trim();
            }
        }
        return "";
    }

    private OffsetDateTime publishedAt(Element entry) {
        String value = firstText(entry, "pubDate", "published", "updated");
        if (value.isBlank()) return OffsetDateTime.now();
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();
            } catch (DateTimeParseException ignoredAgain) {
                return OffsetDateTime.now();
            }
        }
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private FeedStatus status(FeedScanResult result, String failure, OffsetDateTime lastSuccess) {
        return status(result, failure, lastSuccess, result == null ? null : result.httpStatus(), OffsetDateTime.now());
    }

    private FeedStatus status(FeedScanResult result, String failure, OffsetDateTime lastSuccess, Integer httpStatus,
                              OffsetDateTime lastAttempt) {
        return new FeedStatus(enabled(), feedUrl, sourceName, sourceType, licenseStatus, licenseNote, scanDelayMs,
                lastAttempt, lastSuccess, httpStatus, failure, result);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void setAttributeIfSupported(DocumentBuilderFactory factory, String attribute) {
        try {
            factory.setAttribute(attribute, "");
        } catch (IllegalArgumentException ignored) {
        }
    }

    interface FeedFetcher {
        FeedResponse fetch(URI uri) throws Exception;
    }

    private static final class JdkFeedFetcher implements FeedFetcher {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override
        public FeedResponse fetch(URI uri) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                    .header("User-Agent", "ZhigangMap-AuthorizedFeed/1.0").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] body = input.readNBytes(MAX_RESPONSE_SIZE + 1);
                if (body.length > MAX_RESPONSE_SIZE) throw new FeedFetchException(response.statusCode(), "订阅源响应超过 2MB 限制");
                return new FeedResponse(response.statusCode(), body);
            }
        }
    }

    record FeedResponse(int statusCode, byte[] body) {
    }

    private static final class FeedFetchException extends RuntimeException {
        private final Integer statusCode;

        private FeedFetchException(Integer statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private Integer statusCode() {
            return statusCode;
        }
    }

    public record FeedScanResult(String snapshotId, int acceptedCount, int skippedCount, int indexedEvidenceCount, int httpStatus,
                                 String status, OffsetDateTime startedAt, OffsetDateTime completedAt) {
    }

    public record FeedStatus(boolean enabled, String feedUrl, String sourceName, String sourceType, String licenseStatus,
                             String licenseNote, long scanDelayMs, OffsetDateTime lastAttemptAt,
                             OffsetDateTime lastSuccessAt, Integer lastHttpStatus, String lastFailure,
                             FeedScanResult lastResult) {
    }
}
