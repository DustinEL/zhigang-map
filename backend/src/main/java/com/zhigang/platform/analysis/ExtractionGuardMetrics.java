package com.zhigang.platform.analysis;

import com.zhigang.platform.domain.PlatformModels.ExtractionMetrics;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

@Component
public class ExtractionGuardMetrics {
    private final LongAdder conclusionCount = new LongAdder();
    private final LongAdder evidenceBackedCount = new LongAdder();
    private final LongAdder citationCount = new LongAdder();
    private final LongAdder consistentCitationCount = new LongAdder();
    private final LongAdder unsupportedRequestCount = new LongAdder();
    private final LongAdder unsupportedRefusalCount = new LongAdder();
    private final LongAdder unsupportedAcceptedCount = new LongAdder();
    private final LongAdder injectionAttemptCount = new LongAdder();
    private final LongAdder injectionBlockedCount = new LongAdder();

    public ExtractionMetrics record(Observation observation) {
        conclusionCount.add(observation.conclusionCount());
        evidenceBackedCount.add(observation.evidenceBackedCount());
        citationCount.add(observation.citationCount());
        consistentCitationCount.add(observation.consistentCitationCount());
        if (observation.unsupportedRequest()) {
            unsupportedRequestCount.increment();
            if (observation.refused()) unsupportedRefusalCount.increment();
            else unsupportedAcceptedCount.increment();
        }
        if (observation.injectionAttempt()) {
            injectionAttemptCount.increment();
            if (observation.injectionBlocked()) injectionBlockedCount.increment();
        }
        return snapshot();
    }

    public ExtractionMetrics snapshot() {
        long conclusions = conclusionCount.sum();
        long evidenceBacked = evidenceBackedCount.sum();
        long citations = citationCount.sum();
        long consistentCitations = consistentCitationCount.sum();
        long unsupported = unsupportedRequestCount.sum();
        long refused = unsupportedRefusalCount.sum();
        long injections = injectionAttemptCount.sum();
        return new ExtractionMetrics(
                ratio(evidenceBacked, conclusions),
                ratio(consistentCitations, citations),
                ratio(refused, unsupported),
                ratio(unsupportedAcceptedCount.sum(), unsupported),
                ratio(injectionBlockedCount.sum(), injections),
                Math.toIntExact(Math.min(Integer.MAX_VALUE, conclusions)),
                Math.toIntExact(Math.min(Integer.MAX_VALUE, evidenceBacked)),
                Math.toIntExact(Math.min(Integer.MAX_VALUE, refused))
        );
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : Math.round(numerator * 10_000.0 / denominator) / 10_000.0;
    }

    public record Observation(int conclusionCount, int evidenceBackedCount, int citationCount,
                              int consistentCitationCount, boolean unsupportedRequest, boolean refused,
                              boolean injectionAttempt, boolean injectionBlocked) {
    }
}
