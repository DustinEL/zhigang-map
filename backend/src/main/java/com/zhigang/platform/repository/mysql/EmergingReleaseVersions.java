package com.zhigang.platform.repository.mysql;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

final class EmergingReleaseVersions {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private EmergingReleaseVersions() {
    }

    static String next(String action, long identity) {
        String prefix = action == null || action.isBlank() ? "graph" : action.trim().toLowerCase();
        long unique = Instant.now().toEpochMilli() * 1000 + Math.floorMod(SEQUENCE.getAndIncrement(), 1000);
        return (prefix + "-" + Long.toString(identity, 36) + "-" + Long.toString(unique, 36)).substring(
                0, Math.min(32, prefix.length() + 2 + Long.toString(identity, 36).length()
                        + Long.toString(unique, 36).length()));
    }
}
