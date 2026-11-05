package com.zhigang.platform.source;

import com.zhigang.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizedFeedConnectorTest {

    @Test
    void importsAuthorizedFeedAndSkipsUnchangedEntries() {
        byte[] body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>官方招聘</title>
                  <item><title>RAG 应用开发工程师</title><description>负责企业知识库问答，要求掌握 Python、RAG、Embedding 和向量数据库。</description><pubDate>Mon, 13 Jul 2026 10:00:00 GMT</pubDate></item>
                  <item><title>Java 开发工程师</title><description>负责 Java、Spring Boot、MySQL 后端服务和容器化部署。</description></item>
                </channel></rss>
                """.getBytes(StandardCharsets.UTF_8);
        InMemorySourceImportRepository repository = new InMemorySourceImportRepository();
        AuthorizedFeedConnector connector = connector(repository, uri -> new AuthorizedFeedConnector.FeedResponse(200, body));

        var first = connector.scan();
        var second = connector.scan();

        assertThat(first.acceptedCount()).isEqualTo(2);
        assertThat(second.acceptedCount()).isZero();
        assertThat(second.skippedCount()).isEqualTo(2);
        assertThat(connector.status().lastHttpStatus()).isEqualTo(200);
        assertThat(connector.status().lastSuccessAt()).isNotNull();
    }

    @Test
    void rejectsMissingLicenseInsecureUrlsAndExternalEntities() {
        AuthorizedFeedConnector noLicense = new AuthorizedFeedConnector(new InMemorySourceImportRepository(),
                uri -> new AuthorizedFeedConnector.FeedResponse(200, new byte[0]), "https://example.org/jobs.xml",
                "官方招聘", "RECRUITMENT_JD", "", "", 1000);
        AuthorizedFeedConnector insecure = new AuthorizedFeedConnector(new InMemorySourceImportRepository(),
                uri -> new AuthorizedFeedConnector.FeedResponse(200, new byte[0]), "http://example.org/jobs.xml",
                "官方招聘", "RECRUITMENT_JD", "OPEN", "官方开放订阅", 1000);
        byte[] xxe = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&secret;</title><description>足够长的岗位正文内容用于安全测试</description></item></channel></rss>"
                .getBytes(StandardCharsets.UTF_8);
        AuthorizedFeedConnector externalEntity = connector(new InMemorySourceImportRepository(),
                uri -> new AuthorizedFeedConnector.FeedResponse(200, xxe));

        assertThatThrownBy(noLicense::scan).isInstanceOf(BusinessException.class).hasMessageContaining("许可说明");
        assertThatThrownBy(insecure::scan).isInstanceOf(BusinessException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(externalEntity::scan).isInstanceOf(BusinessException.class).hasMessageContaining("解析失败");
    }

    private AuthorizedFeedConnector connector(InMemorySourceImportRepository repository,
                                              AuthorizedFeedConnector.FeedFetcher fetcher) {
        return new AuthorizedFeedConnector(repository, fetcher, "https://example.org/jobs.xml", "官方招聘",
                "RECRUITMENT_JD", "OPEN", "官方开放 RSS，仅用于岗位研究", 1000);
    }
}
