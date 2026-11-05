package com.zhigang.platform.career;

public interface EvidenceUrlChecker {
    UrlCheck check(String url);

    record UrlCheck(boolean accessible, boolean pending, String detail) {
    }
}
