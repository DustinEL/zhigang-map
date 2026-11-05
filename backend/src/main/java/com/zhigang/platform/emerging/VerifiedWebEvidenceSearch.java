package com.zhigang.platform.emerging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import com.zhigang.platform.repository.PlatformDataProvider;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VerifiedWebEvidenceSearch {
    private static final int MAX_SEARCH_BYTES = 512 * 1024;
    private static final int MAX_PAGE_BYTES = 768 * 1024;
    private static final int MAX_EVIDENCE = 8;
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style[^>]*>.*?</style>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern CHARSET = Pattern.compile("(?i)charset=([a-zA-Z0-9._-]+)");
    private static final List<String> ROLE_SUFFIXES = List.of("工程师", "架构师", "专家", "经理", "开发", "研发");

    private final String endpoint;
    private final SearchClient client;
    private final PlatformDataProvider dataProvider;

    @Autowired
    public VerifiedWebEvidenceSearch(
            @Value("${zhigang.ai.web-evidence.search-endpoint:https://www.bing.com/search?format=rss&q=}") String endpoint,
            @Value("${zhigang.ai.web-evidence.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${zhigang.ai.web-evidence.read-timeout-ms:15000}") int readTimeoutMs,
            PlatformDataProvider dataProvider) {
        this(endpoint, new JdkSearchClient(connectTimeoutMs, readTimeoutMs), dataProvider);
    }

    VerifiedWebEvidenceSearch(String endpoint, SearchClient client) {
        this(endpoint, client, null);
    }

    VerifiedWebEvidenceSearch(String endpoint, SearchClient client, PlatformDataProvider dataProvider) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.client = client;
        this.dataProvider = dataProvider;
    }

    public List<VerifiedEvidence> search(String query) {
        String cleaned = query == null ? "" : query.trim();
        if (cleaned.length() < 2) return List.of();
        String core = coreTerm(cleaned);
        if (endpoint.isBlank()) return searchPublishedJdEvidence(cleaned, core);
        Map<String, SearchCandidate> candidates = new LinkedHashMap<>();
        for (String searchQuery : searchQueries(cleaned, core)) {
            try {
                SearchResponse response = client.fetch(searchUri(searchQuery), MAX_SEARCH_BYTES);
                if (response.statusCode() != 200) continue;
                for (SearchCandidate candidate : parseSearchResults(response.body())) {
                    candidates.putIfAbsent(normalizeUrl(candidate.url()), candidate);
                }
            } catch (Exception ignored) {
            }
        }

        List<VerifiedEvidence> evidence = new ArrayList<>();
        Map<String, Boolean> domains = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates.values()) {
            if (evidence.size() >= MAX_EVIDENCE) break;
            if (!candidateRelevant(candidate.title(), cleaned, core)) continue;
            try {
                String normalizedUrl = normalizeUrl(candidate.url());
                if (normalizedUrl.isBlank()) continue;
                String domain = domain(normalizedUrl);
                if (domain.isBlank() || domains.containsKey(domain)) continue;
                SearchResponse page = client.fetch(URI.create(normalizedUrl), MAX_PAGE_BYTES);
                if (page.statusCode() != 200 || !textContent(page.contentType())) continue;
                String plainText = plainText(page.body(), page.contentType());
                if (!relevant(plainText, cleaned, core)) continue;
                String excerpt = excerpt(plainText, cleaned, core);
                if (excerpt.length() < 80) continue;
                domains.put(domain, true);
                evidence.add(new VerifiedEvidence("E" + (evidence.size() + 1), truncate(candidate.title(), 500),
                        domain, normalizedUrl, "", truncate(excerpt, 1200)));
            } catch (Exception ignored) {
            }
        }
        if (evidence.size() < MAX_EVIDENCE) {
            appendPublishedJdEvidence(evidence, cleaned, core);
        }
        return List.copyOf(evidence);
    }

    private void appendPublishedJdEvidence(List<VerifiedEvidence> target, String query, String core) {
        List<VerifiedEvidence> local = searchPublishedJdEvidence(query, core);
        Set<String> domains = target.stream().map(item -> domain(item.url())).collect(java.util.stream.Collectors.toSet());
        for (VerifiedEvidence item : local) {
            if (target.size() >= MAX_EVIDENCE) break;
            String domain = domain(item.url());
            if (domain.isBlank() || domains.contains(domain)) continue;
            domains.add(domain);
            target.add(new VerifiedEvidence("E" + (target.size() + 1), item.title(), item.source(),
                    item.url(), item.publishedAt(), item.excerpt()));
        }
    }

    private List<VerifiedEvidence> searchPublishedJdEvidence(String query, String core) {
        if (dataProvider == null) return List.of();
        List<VerifiedEvidence> result = new ArrayList<>();
        Map<String, Boolean> domains = new LinkedHashMap<>();
        for (PlatformDataProvider.DiscoveryEvidence document : dataProvider.findPublishedDiscoveryEvidence()) {
            if (result.size() >= MAX_EVIDENCE) break;
            String url = normalizeUrl(document.sourceUrl());
            String domain = domain(url);
            if (url.isBlank() || domain.isBlank() || domains.containsKey(domain)) continue;
            String searchable = document.title() + " " + document.content();
            if (!relevant(searchable, query, core)) continue;
            String excerpt = excerpt(searchable, query, core);
            if (excerpt.length() < 80) continue;
            domains.put(domain, true);
            result.add(new VerifiedEvidence("E" + (result.size() + 1),
                    truncate(document.title(), 500), truncate(document.source() + " / " + document.company(), 250),
                    url, truncate(document.publishedAt(), 60), truncate(excerpt, 1200)));
        }
        return List.copyOf(result);
    }

    private URI searchUri(String query) {
        return URI.create(endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    private List<String> searchQueries(String query, String core) {
        List<String> result = new ArrayList<>();
        result.add("\"" + query + "\" 招聘 岗位职责 技能");
        if (!core.equals(query)) result.add("\"" + core + "\" 人工智能 工程 岗位 技能");
        result.add(query + " AI 大模型 工作 职责");
        result.add("\"" + query + "\" site:liepin.com");
        result.add("\"" + query + "\" site:cnblogs.com");
        result.add("\"" + query + "\" site:runoob.com");
        return result.stream().distinct().toList();
    }

    private String coreTerm(String query) {
        for (String suffix : ROLE_SUFFIXES) {
            if (query.endsWith(suffix) && query.length() - suffix.length() >= 2) {
                return query.substring(0, query.length() - suffix.length());
            }
        }
        return query;
    }

    private List<SearchCandidate> parseSearchResults(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
        setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
        NodeList items = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body)).getElementsByTagName("item");
        List<SearchCandidate> results = new ArrayList<>();
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            String title = text(item, "title");
            String link = text(item, "link");
            if (!title.isBlank() && !link.isBlank()) {
                results.add(new SearchCandidate(title, link, text(item, "pubDate")));
            }
        }
        return List.copyOf(results);
    }

    private String text(Element item, String name) {
        NodeList nodes = item.getElementsByTagName(name);
        return nodes.getLength() == 0 || nodes.item(0).getTextContent() == null
                ? "" : nodes.item(0).getTextContent().trim();
    }

    private String plainText(byte[] body, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        Matcher matcher = CHARSET.matcher(contentType == null ? "" : contentType);
        if (matcher.find()) {
            try {
                charset = Charset.forName(matcher.group(1));
            } catch (Exception ignored) {
            }
        }
        String html = new String(body, charset);
        return HtmlUtils.htmlUnescape(TAG.matcher(STYLE.matcher(SCRIPT.matcher(html).replaceAll(" "))
                .replaceAll(" ")).replaceAll(" ")).replaceAll("\\s+", " ").trim();
    }

    private boolean relevant(String text, String query, String core) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean term = lower.contains(query.toLowerCase(Locale.ROOT))
                || core.length() >= 2 && lower.contains(core.toLowerCase(Locale.ROOT));
        return term && List.of("岗位", "招聘", "职责", "技能", "工程", "prompt", "人工智能", "大模型")
                .stream().anyMatch(value -> lower.contains(value.toLowerCase(Locale.ROOT)));
    }

    private boolean candidateRelevant(String title, String query, String core) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains(query.toLowerCase(Locale.ROOT))
                || core.length() >= 2 && lower.contains(core.toLowerCase(Locale.ROOT))
                || lower.contains("prompt");
    }

    private String excerpt(String text, String query, String core) {
        String lower = text.toLowerCase(Locale.ROOT);
        int position = lower.indexOf(query.toLowerCase(Locale.ROOT));
        if (position < 0) position = lower.indexOf(core.toLowerCase(Locale.ROOT));
        if (position < 0) return "";
        int start = Math.max(0, position - 160);
        int end = Math.min(text.length(), position + Math.max(query.length(), core.length()) + 700);
        return text.substring(start, end).trim();
    }

    private boolean textContent(String contentType) {
        String value = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return value.isBlank() || value.contains("text/") || value.contains("application/xhtml+xml");
    }

    private String normalizeUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) return "";
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(),
                    uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String domain(String value) {
        try {
            return URI.create(value).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void setAttribute(DocumentBuilderFactory factory, String name) {
        try {
            factory.setAttribute(name, "");
        } catch (IllegalArgumentException ignored) {
        }
    }

    interface SearchClient {
        SearchResponse fetch(URI uri, int maxBytes) throws Exception;
    }

    record SearchResponse(int statusCode, URI finalUri, String contentType, byte[] body) {
    }

    private static final class JdkSearchClient implements SearchClient {
        private final HttpClient client;
        private final Duration readTimeout;

        private JdkSearchClient(int connectTimeoutMs, int readTimeoutMs) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            readTimeout = Duration.ofMillis(readTimeoutMs);
        }

        @Override
        public SearchResponse fetch(URI uri, int maxBytes) throws Exception {
            URI current = uri;
            for (int redirect = 0; redirect <= 3; redirect++) {
                validatePublicHttps(current);
                HttpRequest request = HttpRequest.newBuilder(current).timeout(readTimeout)
                        .header("Accept", "text/html, application/xhtml+xml, application/rss+xml, application/xml")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("User-Agent", "ZhigangMap-EvidenceValidator/1.0")
                        .GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location").orElse("");
                    response.body().close();
                    if (location.isBlank() || redirect == 3) throw new IllegalArgumentException("redirect");
                    current = current.resolve(location);
                    continue;
                }
                try (InputStream input = response.body()) {
                    byte[] body = input.readNBytes(maxBytes + 1);
                    if (body.length > maxBytes) throw new IllegalArgumentException("response too large");
                    return new SearchResponse(response.statusCode(), current,
                            response.headers().firstValue("Content-Type").orElse(""), body);
                }
            }
            throw new IllegalArgumentException("redirect");
        }

        private void validatePublicHttps(URI uri) throws Exception {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) throw new IllegalArgumentException("unsafe url");
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("private address");
                }
            }
        }
    }

    private record SearchCandidate(String title, String url, String publishedAt) {
    }

    public record VerifiedEvidence(String key, String title, String source, String url,
                                   String publishedAt, String excerpt) {
    }
}
