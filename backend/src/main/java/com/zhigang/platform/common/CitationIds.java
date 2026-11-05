package com.zhigang.platform.common;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class CitationIds {
    private CitationIds() {
    }

    public static String fromAnchor(long documentId, int startOffset, int endOffset) {
        String anchor = documentId + ":" + startOffset + ":" + endOffset;
        return UUID.nameUUIDFromBytes(anchor.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
