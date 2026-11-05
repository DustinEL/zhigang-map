package com.zhigang.platform.emerging;

import com.zhigang.platform.domain.PlatformModels.RawDocument;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmergingDiscoveryServiceTest {
    @Test
    void deduplicatesPublishedRealDocumentsByContentHash() {
        RawDocument first = document(10, "same-hash", false, "内容一");
        RawDocument duplicate = document(11, "SAME-HASH", false, "内容一重复采集");
        RawDocument unique = document(12, "other-hash", false, "内容二");
        RawDocument demo = document(13, "demo-hash", true, "演示内容");

        List<RawDocument> result = EmergingDiscoveryService.deduplicateEligible(
                List.of(duplicate, demo, unique, first));

        assertThat(result).extracting(RawDocument::id).containsExactly(10L, 12L);
    }

    private RawDocument document(long id, String hash, boolean demo, String content) {
        return new RawDocument(id, "JD", "来源", "AUTHORIZED", "企业", "北京", "软件", "中级",
                "岗位", "", content, OffsetDateTime.now(), hash, demo);
    }
}
