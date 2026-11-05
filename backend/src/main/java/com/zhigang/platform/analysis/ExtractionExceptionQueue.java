package com.zhigang.platform.analysis;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.List;

@Component
public class ExtractionExceptionQueue {
    private static final int MAX_ITEMS = 1_000;
    private final ArrayDeque<ExtractionException> items = new ArrayDeque<>();

    public synchronized void enqueue(long documentId, String fieldType, String rawValue, String reason) {
        if (items.size() >= MAX_ITEMS) items.removeFirst();
        items.addLast(new ExtractionException(documentId, fieldType, rawValue, reason, OffsetDateTime.now()));
    }

    public synchronized List<ExtractionException> recent(int limit) {
        int safeLimit = Math.max(0, Math.min(limit, MAX_ITEMS));
        return items.stream().skip(Math.max(0, items.size() - safeLimit)).toList();
    }

    public record ExtractionException(long documentId, String fieldType, String rawValue,
                                      String reason, OffsetDateTime createdAt) {
    }
}
