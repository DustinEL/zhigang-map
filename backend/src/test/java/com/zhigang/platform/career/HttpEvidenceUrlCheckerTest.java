package com.zhigang.platform.career;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEvidenceUrlCheckerTest {
    private final HttpEvidenceUrlChecker checker = new HttpEvidenceUrlChecker();

    @Test
    void rejectsLocalPrivateAndCredentialBearingUrlsWithoutNetworkAccess() {
        assertThat(checker.check("http://127.0.0.1:8080/admin").accessible()).isFalse();
        assertThat(checker.check("http://localhost/secret").accessible()).isFalse();
        assertThat(checker.check("https://user:pass@example.com/repo").accessible()).isFalse();
        assertThat(checker.check("file:///etc/passwd").accessible()).isFalse();
    }
}
