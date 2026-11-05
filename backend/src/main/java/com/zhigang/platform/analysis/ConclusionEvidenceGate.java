package com.zhigang.platform.analysis;

import com.zhigang.platform.domain.PlatformModels.Evidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class ConclusionEvidenceGate {
    private final int minIndependentCompanies;
    private final int minIndependentSources;

    public ConclusionEvidenceGate(
            @Value("${zhigang.analysis.conclusion-min-companies:2}") int minIndependentCompanies,
            @Value("${zhigang.analysis.conclusion-min-sources:1}") int minIndependentSources
    ) {
        this.minIndependentCompanies = Math.max(1, minIndependentCompanies);
        this.minIndependentSources = Math.max(1, minIndependentSources);
    }

    public GateDecision evaluate(List<Evidence> evidence) {
        List<Evidence> valid = evidence == null ? List.of() : evidence.stream().filter(this::valid).toList();
        long companies = valid.stream().map(Evidence::company).map(this::normalize).filter(value -> !value.isBlank())
                .distinct().count();
        long sources = valid.stream().map(Evidence::source).map(this::normalize).filter(value -> !value.isBlank())
                .distinct().count();
        boolean passed = companies >= minIndependentCompanies && sources >= minIndependentSources;
        String message = passed ? "证据门禁通过" : "证据不足";
        return new GateDecision(passed, message, (int) companies, (int) sources,
                minIndependentCompanies, minIndependentSources, valid);
    }

    private boolean valid(Evidence evidence) {
        return evidence != null && evidence.documentId() > 0 && evidence.quote() != null && !evidence.quote().isBlank()
                && evidence.startOffset() >= 0 && evidence.endOffset() > evidence.startOffset()
                && !"QUARANTINED".equalsIgnoreCase(evidence.gateStatus())
                && !"INVALID".equalsIgnoreCase(evidence.gateStatus());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record GateDecision(boolean passed, String message, int independentCompanies, int independentSources,
                               int requiredCompanies, int requiredSources, List<Evidence> evidence) {
    }
}
