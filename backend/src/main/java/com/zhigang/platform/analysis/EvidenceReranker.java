package com.zhigang.platform.analysis;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic second-stage ordering without an additional model service. */
public final class EvidenceReranker {
    private EvidenceReranker() {
    }

    public static List<EvidenceVectorStore.EvidenceMatch> rerank(
            List<EvidenceVectorStore.EvidenceMatch> matches, int limit) {
        if (matches == null || matches.isEmpty() || limit <= 0) return List.of();
        LinkedHashMap<String, EvidenceVectorStore.EvidenceMatch> unique = new LinkedHashMap<>();
        for (EvidenceVectorStore.EvidenceMatch match : matches) {
            if (match == null || match.fragment() == null) continue;
            unique.merge(anchor(match.fragment()), match, (left, right) -> right.score() > left.score() ? right : left);
        }
        List<EvidenceVectorStore.EvidenceMatch> remaining = new ArrayList<>(unique.values());
        List<EvidenceVectorStore.EvidenceMatch> selected = new ArrayList<>();
        Set<String> sources = new HashSet<>();
        while (!remaining.isEmpty() && selected.size() < limit) {
            EvidenceVectorStore.EvidenceMatch best = remaining.stream()
                    .max(Comparator.comparingDouble(item -> adjustedScore(item, sources)))
                    .orElseThrow();
            selected.add(best);
            sources.add(source(best));
            remaining.remove(best);
        }
        return List.copyOf(selected);
    }

    private static double adjustedScore(EvidenceVectorStore.EvidenceMatch match, Set<String> sources) {
        double diversity = sources.contains(source(match)) ? 0 : 0.005;
        OffsetDateTime publishedAt = match.fragment().publishedAt();
        double freshness = publishedAt == null ? 0 : Math.min(0.01, Math.max(0, publishedAt.toEpochSecond()) / 1_000_000_000_000.0);
        return match.score() + diversity + freshness;
    }

    private static String anchor(EvidenceVectorStore.EvidenceFragment fragment) {
        return fragment.documentId() + ":" + fragment.startOffset() + ":" + fragment.endOffset();
    }

    private static String source(EvidenceVectorStore.EvidenceMatch match) {
        String source = match.fragment().source();
        return source == null ? "" : source;
    }
}
