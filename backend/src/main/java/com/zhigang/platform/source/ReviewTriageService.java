package com.zhigang.platform.source;

import com.zhigang.platform.source.StructuredJdModels.ReviewQueueSummary;
import com.zhigang.platform.source.StructuredJdModels.TriageAssignment;
import com.zhigang.platform.source.StructuredJdModels.TriageCandidate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReviewTriageService {
    private final StructuredJdRepository repository;
    private final String policyVersion;
    private final double qualityThreshold;
    private final double confidenceThreshold;
    private final double sampleRate;

    public ReviewTriageService(
            StructuredJdRepository repository,
            @Value("${zhigang.structured-jd.triage-policy-version:jd-triage-v2.0}") String policyVersion,
            @Value("${zhigang.structured-jd.auto-quality-threshold:0.95}") double qualityThreshold,
            @Value("${zhigang.structured-jd.auto-confidence-threshold:0.85}") double confidenceThreshold,
            @Value("${zhigang.structured-jd.sample-rate:0.10}") double sampleRate
    ) {
        this.repository = repository;
        this.policyVersion = policyVersion;
        this.qualityThreshold = qualityThreshold;
        this.confidenceThreshold = confidenceThreshold;
        this.sampleRate = Math.max(0, Math.min(1, sampleRate));
    }

    public ReviewQueueSummary retriage(Long importId) {
        List<TriageCandidate> candidates = repository.findTriageCandidates(importId);
        Map<Long, List<TriageCandidate>> imports = new LinkedHashMap<>();
        candidates.forEach(candidate -> imports.computeIfAbsent(candidate.importId(), ignored -> new ArrayList<>())
                .add(candidate));

        List<TriageAssignment> assignments = new ArrayList<>(candidates.size());
        imports.values().forEach(group -> assignments.addAll(assign(group)));
        repository.saveTriageAssignments(assignments, policyVersion);
        return repository.reviewQueueSummary(importId);
    }

    public ReviewQueueSummary summary(Long importId) {
        return repository.reviewQueueSummary(importId);
    }

    private List<TriageAssignment> assign(List<TriageCandidate> candidates) {
        List<TriageCandidate> lowRisk = new ArrayList<>();
        List<TriageAssignment> result = new ArrayList<>(candidates.size());
        for (TriageCandidate candidate : candidates) {
            if (isHumanRejected(candidate)) {
                result.add(assignment(candidate, "HIGH", "BLOCKED", List.of("人工审核已隔离或拒绝"), "HUMAN", false));
            } else if (!"ELIGIBLE".equals(candidate.status())) {
                result.add(assignment(candidate, "HIGH", "BLOCKED", List.of("自动质量门禁未通过"), source(candidate), false));
            } else if (candidate.unresolvedCandidateCount() > 0) {
                result.add(assignment(candidate, "MEDIUM", "WAITING_SKILL",
                        List.of("仍有未解决候选技能"), source(candidate), false));
            } else {
                List<String> reasons = elevatedReasons(candidate);
                if (!reasons.isEmpty()) {
                    String route = isHumanApproved(candidate) ? "HUMAN_APPROVED" : "EXCEPTION_REVIEW";
                    result.add(assignment(candidate, "HIGH", route, reasons, source(candidate), false));
                } else {
                    lowRisk.add(candidate);
                }
            }
        }

        long humanReviewed = lowRisk.stream().filter(this::isHumanApproved).count();
        int targetSamples = lowRisk.isEmpty() ? 0 : Math.max(1, (int) Math.ceil(lowRisk.size() * sampleRate));
        int samplesNeeded = Math.max(0, targetSamples - (int) humanReviewed);
        List<TriageCandidate> pending = lowRisk.stream()
                .filter(candidate -> !isHumanApproved(candidate))
                .sorted(Comparator.comparing(TriageCandidate::contentHash).thenComparingLong(TriageCandidate::id))
                .toList();
        for (int index = 0; index < pending.size(); index++) {
            TriageCandidate candidate = pending.get(index);
            if (index < samplesNeeded) {
                result.add(assignment(candidate, "LOW", "SAMPLE_REVIEW",
                        List.of("低风险批次确定性抽检"), "NONE", false));
            } else {
                boolean createApproval = !("APPROVE".equals(candidate.latestDecision())
                        && "SYSTEM".equals(candidate.latestReviewSource()));
                result.add(assignment(candidate, "LOW", "AUTO_APPROVED",
                        List.of("哈希、来源、证据偏移和质量门禁全部通过"), "SYSTEM", createApproval));
            }
        }
        lowRisk.stream().filter(this::isHumanApproved).forEach(candidate -> result.add(
                assignment(candidate, "LOW", "HUMAN_APPROVED", List.of("人工审核已通过"), "HUMAN", false)));
        return result;
    }

    private List<String> elevatedReasons(TriageCandidate candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate.qualityScore() < qualityThreshold) reasons.add("质量分低于自动通过阈值");
        if (candidate.minimumConfidence() < confidenceThreshold) reasons.add("存在低置信度事实");
        if (candidate.blockingIssueCount() > 0) reasons.add("存在阻断质量问题");
        if (candidate.elevatedIssueCount() > 0) reasons.add("存在中高风险质量问题");
        return reasons;
    }

    private TriageAssignment assignment(TriageCandidate candidate, String riskLevel, String route,
                                         List<String> reasons, String reviewSource, boolean createApproval) {
        return new TriageAssignment(candidate.id(), riskLevel, route, reasons, reviewSource, createApproval);
    }

    private boolean isHumanApproved(TriageCandidate candidate) {
        return "APPROVE".equals(candidate.latestDecision()) && "HUMAN".equals(candidate.latestReviewSource());
    }

    private boolean isHumanRejected(TriageCandidate candidate) {
        return "HUMAN".equals(candidate.latestReviewSource())
                && ("REJECT".equals(candidate.latestDecision()) || "QUARANTINE".equals(candidate.latestDecision()));
    }

    private String source(TriageCandidate candidate) {
        return candidate.latestReviewSource() == null ? "NONE" : candidate.latestReviewSource();
    }
}
