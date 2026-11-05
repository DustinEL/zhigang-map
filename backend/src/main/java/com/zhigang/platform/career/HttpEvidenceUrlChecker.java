package com.zhigang.platform.career;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Component
public class HttpEvidenceUrlChecker implements EvidenceUrlChecker {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public UrlCheck check(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!ALLOWED_SCHEMES.contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                return new UrlCheck(false, false, "仅支持无账号信息的 HTTP/HTTPS 地址");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    return new UrlCheck(false, false, "证据地址不能指向本机或内网");
                }
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "ZhigangEvidenceVerifier/1.0").method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 405) {
                request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6))
                        .header("User-Agent", "ZhigangEvidenceVerifier/1.0")
                        .header("Range", "bytes=0-1023").GET().build();
                status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            }
            return status >= 200 && status < 400
                    ? new UrlCheck(true, false, "页面可访问，HTTP " + status)
                    : new UrlCheck(false, false, "页面返回 HTTP " + status);
        } catch (java.net.http.HttpTimeoutException exception) {
            return new UrlCheck(false, true, "页面访问超时，等待人工复核");
        } catch (Exception exception) {
            return new UrlCheck(false, false, "页面无法访问或地址无效");
        }
    }
}
