package com.zhigang.platform.emerging;

import com.zhigang.platform.repository.PlatformDataProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifiedWebEvidenceSearchTest {
    @Test
    void keepsOnlyAccessibleRelevantHttpsPagesFromDistinctDomains() {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss><channel>
                  <item><title>提示词工程师招聘</title><link>https://jobs.example.cn/prompt</link></item>
                  <item><title>提示词工程指南</title><link>https://docs.example.org/prompt</link></item>
                  <item><title>无关页面</title><link>https://noise.example.net/page</link></item>
                  <item><title>不安全链接</title><link>http://unsafe.example.com/page</link></item>
                </channel></rss>
                """;
        Map<String, VerifiedWebEvidenceSearch.SearchResponse> pages = Map.of(
                "jobs.example.cn", page("提示词工程师岗位招聘：负责设计 Prompt 策略、评估大模型输出质量，要求掌握 Python 和 RAG。"),
                "docs.example.org", page("Prompt Engineering 提示词工程技能指南，介绍提示工程师在人工智能应用中的职责和评测方法。"),
                "noise.example.net", page("这是一篇介绍传统园艺和植物养护方法的普通文章，与计算机岗位无关。")
        );
        VerifiedWebEvidenceSearch search = new VerifiedWebEvidenceSearch("https://search.example.test/?q=", (uri, maxBytes) -> {
            if ("search.example.test".equals(uri.getHost())) {
                return new VerifiedWebEvidenceSearch.SearchResponse(200, uri, "application/rss+xml; charset=utf-8",
                        rss.getBytes(StandardCharsets.UTF_8));
            }
            return pages.getOrDefault(uri.getHost(), new VerifiedWebEvidenceSearch.SearchResponse(
                    404, uri, "text/html; charset=utf-8", new byte[0]));
        });

        var evidence = search.search("提示词工程师");

        assertThat(evidence).hasSize(2);
        assertThat(evidence).extracting(item -> URI.create(item.url()).getHost())
                .containsExactly("jobs.example.cn", "docs.example.org");
        assertThat(evidence).extracting(VerifiedWebEvidenceSearch.VerifiedEvidence::key)
                .containsExactly("E1", "E2");
        assertThat(evidence).extracting(VerifiedWebEvidenceSearch.VerifiedEvidence::publishedAt)
                .containsOnly("");
    }

    @Test
    void fallsBackToPublishedJdEvidenceWhenWebSearchIsDisabled() {
        PlatformDataProvider dataProvider = mock(PlatformDataProvider.class);
        when(dataProvider.findPublishedDiscoveryEvidence()).thenReturn(List.of(
                jd(1, "大模型推理加速工程师", "平台甲", "企业甲", "https://jobs.example.cn/a"),
                jd(2, "大模型推理优化研发", "平台甲", "企业乙", "https://jobs.example.cn/b"),
                jd(3, "大模型推理性能工程师", "平台乙", "企业丙", "https://career.example.org/c")
        ));

        var evidence = new VerifiedWebEvidenceSearch("", (uri, maxBytes) -> {
            throw new AssertionError("关闭在线搜索时不应发起网络请求");
        }, dataProvider).search("大模型推理加速");

        assertThat(evidence).hasSize(2);
        assertThat(evidence).extracting(item -> URI.create(item.url()).getHost())
                .containsExactly("jobs.example.cn", "career.example.org");
        assertThat(evidence).extracting(VerifiedWebEvidenceSearch.VerifiedEvidence::source)
                .containsExactly("平台甲 / 企业甲", "平台乙 / 企业丙");
        assertThat(evidence).extracting(VerifiedWebEvidenceSearch.VerifiedEvidence::publishedAt)
                .containsOnly("2026-08-01");
    }

    @Test
    void appendsPublishedJdEvidenceWithoutRepeatingWebDomains() {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss><channel>
                  <item><title>大模型推理加速岗位</title><link>https://jobs.example.cn/live</link></item>
                </channel></rss>
                """;
        PlatformDataProvider dataProvider = mock(PlatformDataProvider.class);
        when(dataProvider.findPublishedDiscoveryEvidence()).thenReturn(List.of(
                jd(1, "大模型推理加速工程师", "平台甲", "企业甲", "https://jobs.example.cn/local"),
                jd(2, "大模型推理优化研发", "平台乙", "企业乙", "https://career.example.org/local")
        ));
        VerifiedWebEvidenceSearch search = new VerifiedWebEvidenceSearch("https://search.example.test/?q=", (uri, maxBytes) -> {
            if ("search.example.test".equals(uri.getHost())) {
                return new VerifiedWebEvidenceSearch.SearchResponse(200, uri, "application/rss+xml; charset=utf-8",
                        rss.getBytes(StandardCharsets.UTF_8));
            }
            if ("jobs.example.cn".equals(uri.getHost())) {
                return page("大模型推理加速岗位招聘，负责模型压缩、推理性能分析和部署优化，要求掌握 Python、CUDA 与性能评测。");
            }
            return new VerifiedWebEvidenceSearch.SearchResponse(404, uri, "text/html; charset=utf-8", new byte[0]);
        }, dataProvider);

        var evidence = search.search("大模型推理加速");

        assertThat(evidence).hasSize(2);
        assertThat(evidence).extracting(item -> URI.create(item.url()).getHost())
                .containsExactly("jobs.example.cn", "career.example.org");
        assertThat(evidence).extracting(VerifiedWebEvidenceSearch.VerifiedEvidence::key)
                .containsExactly("E1", "E2");
    }

    private PlatformDataProvider.DiscoveryEvidence jd(long id, String title, String source,
                                                        String company, String url) {
        String content = (title + "岗位招聘：负责大模型推理加速、模型压缩、服务部署、性能分析与稳定性优化，"
                + "要求掌握 Python、CUDA、深度学习框架、推理引擎和性能评测方法。").repeat(4);
        return new PlatformDataProvider.DiscoveryEvidence(id, title, source, company, url, "2026-08-01", content);
    }

    private VerifiedWebEvidenceSearch.SearchResponse page(String text) {
        String html = "<html><body>" + text.repeat(8) + "</body></html>";
        return new VerifiedWebEvidenceSearch.SearchResponse(200, URI.create("https://example.test"),
                "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }
}
