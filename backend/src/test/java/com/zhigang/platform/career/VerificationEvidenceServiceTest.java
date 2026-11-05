package com.zhigang.platform.career;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationEvidenceServiceTest {
    @Test
    void verifiesOnlyAccessibleRelevantAndMeasurableEvidence() {
        VerificationEvidenceService service = new VerificationEvidenceService(url ->
                new EvidenceUrlChecker.UrlCheck(true, false, "页面可访问"));

        var verified = service.assess("RAG", "PROJECT_LINK", "https://example.test/rag",
                "使用 RAG 实现检索与引用模块，提交代码、README 和测试结果，Recall@5 为 86%。");
        var rejected = service.assess("RAG", "PROJECT_LINK", "", "完成了一个项目");
        var invalidMetric = service.assess("RAG", "TEST_RESULT", "", "RAG 测试已经完成");

        assertThat(verified.status()).isEqualTo("VERIFIED");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(invalidMetric.status()).isEqualTo("REJECTED");
    }

    @Test
    void keepsTimedOutPagePendingForManualConfirmation() {
        VerificationEvidenceService service = new VerificationEvidenceService(url ->
                new EvidenceUrlChecker.UrlCheck(false, true, "页面访问超时"));

        var result = service.assess("Java", "PROJECT_LINK", "https://example.test/java",
                "使用 Java 开发接口并完成测试通过 20/20");

        assertThat(result.status()).isEqualTo("PENDING_VERIFICATION");
    }
}
