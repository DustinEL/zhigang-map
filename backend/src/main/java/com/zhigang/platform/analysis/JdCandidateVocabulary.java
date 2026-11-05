package com.zhigang.platform.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

@Component
public final class JdCandidateVocabulary {
    private static final String RESOURCE = "analysis/jd-candidate-snapshot-v1.json";
    private static final String REVIEWED_SPANS_RESOURCE = "analysis/jd-reviewed-skill-spans-v1.json";
    private static final String COMPOSITE_REVIEWED_SPANS_RESOURCE = "analysis/jd-reviewed-skill-spans-composite-v1.json";
    private static final String GOLD_REVIEWED_SPANS_RESOURCE = "analysis/jd-reviewed-skill-spans-gold-v1.json";
    private static final String GOLD_REVIEWED_SPANS_V2_RESOURCE = "analysis/jd-reviewed-skill-spans-gold-v2.json";
    private static final String GOLD_REVIEWED_SPANS_V3_RESOURCE = "analysis/jd-reviewed-skill-spans-gold-v3.json";

    private final List<Entry> entries;

    public JdCandidateVocabulary() {
        this.entries = load().entries();
    }

    List<Entry> entries() {
        return entries;
    }

    List<String> reviewedSpans() {
        try (InputStream input = JdCandidateVocabulary.class.getClassLoader()
                .getResourceAsStream(REVIEWED_SPANS_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing reviewed JD spans: " + REVIEWED_SPANS_RESOURCE);
            ObjectMapper mapper = new ObjectMapper();
            List<String> terms = new ArrayList<>(mapper.readValue(input, ReviewedSpans.class).terms());
            try (InputStream composite = JdCandidateVocabulary.class.getClassLoader()
                    .getResourceAsStream(COMPOSITE_REVIEWED_SPANS_RESOURCE)) {
                if (composite != null) {
                    terms.addAll(mapper.readValue(composite, ReviewedSpans.class).terms());
                }
            }
            try (InputStream gold = JdCandidateVocabulary.class.getClassLoader()
                    .getResourceAsStream(GOLD_REVIEWED_SPANS_RESOURCE)) {
                if (gold != null) terms.addAll(mapper.readValue(gold, ReviewedSpans.class).terms());
            }
            try (InputStream goldV2 = JdCandidateVocabulary.class.getClassLoader()
                    .getResourceAsStream(GOLD_REVIEWED_SPANS_V2_RESOURCE)) {
                if (goldV2 != null) terms.addAll(mapper.readValue(goldV2, ReviewedSpans.class).terms());
            }
            try (InputStream goldV3 = JdCandidateVocabulary.class.getClassLoader()
                    .getResourceAsStream(GOLD_REVIEWED_SPANS_V3_RESOURCE)) {
                if (goldV3 != null) terms.addAll(mapper.readValue(goldV3, ReviewedSpans.class).terms());
            }
            return List.copyOf(terms);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load reviewed JD spans: " + REVIEWED_SPANS_RESOURCE, exception);
        }
    }

    List<String> compositeReviewedSpans() {
        try (InputStream input = JdCandidateVocabulary.class.getClassLoader()
                .getResourceAsStream(COMPOSITE_REVIEWED_SPANS_RESOURCE)) {
            if (input == null) return List.of();
            return List.copyOf(new ObjectMapper().readValue(input, ReviewedSpans.class).terms());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load composite reviewed JD spans", exception);
        }
    }

    private static Snapshot load() {
        try (InputStream input = JdCandidateVocabulary.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing JD candidate snapshot: " + RESOURCE);
            Snapshot snapshot = new ObjectMapper().readValue(input, Snapshot.class);
            if (!"1.0.0".equals(snapshot.schemaVersion()) || snapshot.entries() == null || snapshot.entries().isEmpty()) {
                throw new IllegalStateException("Invalid JD candidate snapshot: " + RESOURCE);
            }
            return snapshot;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load JD candidate snapshot: " + RESOURCE, exception);
        }
    }

    record Snapshot(String schemaVersion, List<Entry> entries) {
    }

    record Entry(String alias, String canonical, boolean exactCanonical) {
    }

    record ReviewedSpans(String schemaVersion, String source, double minimumConditionalPrecision,
                         List<String> terms) {
    }
}
