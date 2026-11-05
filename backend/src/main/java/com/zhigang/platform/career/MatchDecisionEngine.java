package com.zhigang.platform.career;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class MatchDecisionEngine {
    public static final String POLICY_VERSION = "ordered-capability-v2.10";

    public MatchDecisionTrace evaluate(RoleCapabilityCard card, ResumeEvidenceBundle bundle, boolean educationPassed) {
        int groupCount = card.coreCapabilities().size();
        int covered = 0;
        int blockingGaps = 0;
        int projectBacked = 0;
        int blockingGroupCount = 0;
        int blockingReadySatisfiedCount = 0;
        int levelSum = 0;
        boolean hasActionableEvidence = false;
        boolean derivedAgentAnchor = false;
        List<CapabilityAssessment> assessments = new ArrayList<>();

        for (RoleCapabilityCard.CapabilityGroup group : card.coreCapabilities()) {
            ResumeEvidenceBundle.SkillEvidence strongest = null;
            String matchedSkill = "";
            boolean hasProjectEvidence = false;
            for (String skill : group.alternatives()) {
                ResumeEvidenceBundle.SkillEvidence candidate = bundle.positiveEvidenceFor(skill).stream()
                        .filter(item -> !"聊天机器人".equals(skill) || hasLlmChatbotCompanion(bundle, item))
                        .max(Comparator.comparingInt(ResumeEvidenceBundle.SkillEvidence::evidenceLevel)
                                .thenComparingDouble(ResumeEvidenceBundle.SkillEvidence::confidence))
                        .orElse(null);
                // Explicit project, internship, and work sections count toward relevance
                // even when extraction assigns L1; skills-list mentions do not.
                hasProjectEvidence |= bundle.positiveEvidenceFor(skill).stream().anyMatch(item ->
                        ("PROJECT".equals(item.contextType()) || "PRODUCTION".equals(item.contextType())
                                || "INTERNSHIP".equals(item.contextType()) || "WORK".equals(item.contextType())
                                || "PROJECT".equals(item.section()) || !item.projectId().isBlank())
                                && item.evidenceLevel() >= 1);
                hasActionableEvidence |= bundle.positiveEvidenceFor(skill).stream().anyMatch(item ->
                        item.evidenceLevel() >= 2 || "PROJECT".equals(item.contextType())
                                || "PRODUCTION".equals(item.contextType())
                                || "INTERNSHIP".equals(item.contextType())
                                || "PROJECT".equals(item.section()) || "WORK".equals(item.section()));
                if (candidate != null && (strongest == null || strongestComparator().compare(candidate, strongest) > 0)) {
                    strongest = candidate;
                    matchedSkill = skill;
                }
            }
            if (strongest == null && "agent".equals(group.id())) {
                ResumeEvidenceBundle.SkillEvidence derived = compositeAgentEvidence(bundle);
                if (derived != null) {
                    strongest = derived;
                    matchedSkill = "AI Agent";
                    hasProjectEvidence = true;
                    hasActionableEvidence = true;
                    derivedAgentAnchor = true;
                }
            }
            int level = strongest == null ? 0 : strongest.evidenceLevel();
            boolean nearSatisfied = level >= group.nearEvidenceLevel();
            boolean readySatisfied = level >= group.readyEvidenceLevel();
            if (nearSatisfied) covered++;
            if (group.blocking()) {
                blockingGroupCount++;
                if (!nearSatisfied) blockingGaps++;
                if (readySatisfied) blockingReadySatisfiedCount++;
            }
            if (hasProjectEvidence) projectBacked++;
            if (nearSatisfied) levelSum += level;
            assessments.add(new CapabilityAssessment(group.id(), matchedSkill, level, nearSatisfied,
                    readySatisfied, hasProjectEvidence));
        }

        double coreCoverage = covered / (double) groupCount;
        double averageEvidenceLevel = covered == 0 ? 0 : levelSum / (double) covered;
        double evidenceQuality = Math.min(1.0, averageEvidenceLevel / 4.0);
        double projectRelevance = projectBacked / (double) groupCount;
        double transferableCoverage = transferableCoverage(card, bundle);
        double readiness = round(coreCoverage * 50 + evidenceQuality * 25
                + projectRelevance * 20 + transferableCoverage * 5);
        if (!educationPassed) readiness = Math.min(readiness, 74.99);

        RoleCapabilityCard.DecisionPolicy policy = card.effectiveDecisionPolicy();
        int blockingReadyShortfall = blockingGroupCount - blockingReadySatisfiedCount;
        boolean anchorSatisfied = policy.nearAnchorGroupIds().isEmpty() || assessments.stream()
                .anyMatch(item -> policy.nearAnchorGroupIds().contains(item.capabilityId())
                        && item.nearSatisfied() && actionableAnchor(item)
                        && projectRelevance >= policy.nearMinimumProjectRelevance());
        boolean requiredAnySatisfied = policy.nearRequiredAnyGroupIds().isEmpty() || assessments.stream()
                .anyMatch(item -> policy.nearRequiredAnyGroupIds().contains(item.capabilityId()) && item.nearSatisfied());
        boolean requiredAllSatisfied = policy.nearRequiredAllGroupIds().isEmpty() || policy.nearRequiredAllGroupIds().stream()
                .allMatch(required -> assessments.stream()
                        .anyMatch(item -> required.equals(item.capabilityId()) && item.nearSatisfied()));
        boolean readyAnchorsSatisfied = policy.nearAnchorGroupIds().isEmpty() || card.coreCapabilities().stream()
                .filter(group -> group.blocking() && policy.nearAnchorGroupIds().contains(group.id()))
                .allMatch(group -> assessments.stream()
                        .anyMatch(item -> group.id().equals(item.capabilityId()) && item.readySatisfied()
                                && readyAnchor(item)));
        boolean readyRequiredAnySatisfied = policy.readyRequiredAnyGroupIds().isEmpty() || assessments.stream()
                .anyMatch(item -> policy.readyRequiredAnyGroupIds().contains(item.capabilityId()) && item.readySatisfied());
        boolean readyRequiredAllSatisfied = policy.readyRequiredAllGroupIds().isEmpty() || policy.readyRequiredAllGroupIds().stream()
                .allMatch(required -> assessments.stream()
                        .anyMatch(item -> required.equals(item.capabilityId()) && item.readySatisfied()));
        boolean ready = educationPassed
                && blockingGaps <= policy.readyMaximumBlockingGaps()
                && coreCoverage >= policy.readyMinimumCoreCoverage()
                && blockingReadyShortfall <= policy.readyMaximumBlockingReadyShortfall()
                && projectRelevance >= policy.readyMinimumProjectRelevance()
                && readyAnchorsSatisfied
                && readyRequiredAnySatisfied
                && readyRequiredAllSatisfied
                && !derivedAgentAnchor;
        // A skills-list mention alone is not actionable evidence. Near-match requires
        // at least one project/production item or a higher evidence level.
        int nearMaximumBlockingGaps = policy.effectiveNearMaximumBlockingGaps();
        boolean near = coreCoverage >= 0.40 && levelSum >= 2 && blockingGaps <= nearMaximumBlockingGaps
                && anchorSatisfied && requiredAnySatisfied && requiredAllSatisfied && hasActionableEvidence;
        String label = ready ? "可申请实习/校招" : near ? "接近可申请" : "需先补关键能力";

        List<String> reasons = new ArrayList<>();
        if (!educationPassed) reasons.add("BASE_EDUCATION_GATE_RISK");
        if (blockingGaps > 0) reasons.add("BLOCKING_CORE_GAP");
        if (coreCoverage < 0.40) reasons.add("CORE_COVERAGE_LOW");
        if (projectRelevance < 0.60) reasons.add("PROJECT_EVIDENCE_INSUFFICIENT");
        if (ready) reasons.add("READY_ALL_GATES_PASSED");
        else if (near) reasons.add("NEAR_PARTIAL_CORE_COVERAGE");
        else reasons.add("GAP_CORE_REQUIREMENTS_NOT_MET");

        return new MatchDecisionTrace(label, readiness, round(coreCoverage), round(evidenceQuality),
                round(projectRelevance), round(transferableCoverage), blockingGaps, List.copyOf(reasons),
                List.copyOf(assessments), card.version(), POLICY_VERSION, bundle.sourceSha256(), bundle.extractorVersion());
    }

    private Comparator<ResumeEvidenceBundle.SkillEvidence> strongestComparator() {
        return Comparator.comparingInt(ResumeEvidenceBundle.SkillEvidence::evidenceLevel)
                .thenComparingDouble(ResumeEvidenceBundle.SkillEvidence::confidence)
                .thenComparing(ResumeEvidenceBundle.SkillEvidence::skill)
                .thenComparing(ResumeEvidenceBundle.SkillEvidence::quote);
    }

    private boolean hasLlmChatbotCompanion(ResumeEvidenceBundle bundle,
                                           ResumeEvidenceBundle.SkillEvidence chatbot) {
        List<String> companions = List.of("LLM", "大语言模型", "GPT-2", "GPT-3", "GPT-4",
                "OpenAI", "Claude", "DeepSeek", "Gemini", "RAG", "AI Agent", "LangChain",
                "Prompt Engineering", "MCP", "工具调用");
        return companions.stream().anyMatch(skill -> bundle.positiveEvidenceFor(skill).stream()
                .anyMatch(item -> sameEvidenceContext(chatbot, item)));
    }

    private ResumeEvidenceBundle.SkillEvidence compositeAgentEvidence(ResumeEvidenceBundle bundle) {
        List<ResumeEvidenceBundle.SkillEvidence> llm = bundle.evidence().stream()
                .filter(item -> !item.negated() && item.evidenceLevel() >= 2
                        && Set.of("LLM API", "OpenAI API", "OpenAI", "GPT-2", "GPT-3", "GPT-4",
                        "Claude", "DeepSeek", "Gemini", "LLM", "大语言模型").contains(item.skill()))
                .toList();
        List<ResumeEvidenceBundle.SkillEvidence> retrieval = bundle.evidence().stream()
                .filter(item -> !item.negated() && item.evidenceLevel() >= 2
                        && Set.of("RAG", "向量数据库").contains(item.skill()))
                .toList();
        for (ResumeEvidenceBundle.SkillEvidence left : llm) {
            for (ResumeEvidenceBundle.SkillEvidence right : retrieval) {
                if (!sameEvidenceContext(left, right)) continue;
                return new ResumeEvidenceBundle.SkillEvidence(
                        "AI Agent", left.quote() + " + " + right.quote(), left.startOffset(), right.endOffset(),
                        left.section(), left.projectId(), left.contextType(), "组合派生", "", 1,
                        false, Math.min(left.confidence(), right.confidence()));
            }
        }
        return null;
    }

    private boolean sameEvidenceContext(ResumeEvidenceBundle.SkillEvidence left,
                                        ResumeEvidenceBundle.SkillEvidence right) {
        if (left == right) return false;
        if (!left.projectId().isBlank() && left.projectId().equals(right.projectId())) return true;
        return left.projectId().isBlank() && right.projectId().isBlank()
                && !left.contextType().isBlank() && left.contextType().equals(right.contextType())
                && !left.section().isBlank() && left.section().equals(right.section());
    }

    private double transferableCoverage(RoleCapabilityCard card, ResumeEvidenceBundle bundle) {
        if (card.transferableSkills().isEmpty()) return 0;
        long covered = card.transferableSkills().stream().filter(skill -> bundle.positiveEvidenceFor(skill).stream()
                .anyMatch(item -> item.evidenceLevel() >= 2)).count();
        return covered / (double) card.transferableSkills().size();
    }

    private boolean actionableAnchor(CapabilityAssessment assessment) {
        return assessment.projectBacked() || assessment.evidenceLevel() >= 2;
    }

    private boolean readyAnchor(CapabilityAssessment assessment) {
        return assessment.projectBacked();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record MatchDecisionTrace(
            String label,
            double readiness,
            double coreCoverage,
            double evidenceQuality,
            double projectRelevance,
            double transferableCoverage,
            int blockingGapCount,
            List<String> reasonCodes,
            List<CapabilityAssessment> capabilities,
            String roleCardVersion,
            String policyVersion,
            String evidenceSha256,
            String extractorVersion
    ) {
    }

    public record CapabilityAssessment(
            String capabilityId,
            String matchedSkill,
            int evidenceLevel,
            boolean nearSatisfied,
            boolean readySatisfied,
            boolean projectBacked
    ) {
    }
}
