package com.zhigang.platform.career;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record ResumeEvidenceBundle(
        String extractorVersion,
        String sourceSha256,
        List<SkillEvidence> evidence
) {
    public ResumeEvidenceBundle {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public List<SkillEvidence> evidenceFor(String skill) {
        String wanted = normalizeSkill(skill);
        return evidence.stream().filter(item -> normalizeSkill(item.skill()).equals(wanted)).toList();
    }

    public List<SkillEvidence> positiveEvidenceFor(String skill) {
        String wanted = normalizeSkill(skill);
        return evidence.stream().filter(item -> normalizeSkill(item.skill()).equals(wanted) && !item.negated()).toList();
    }

    public Optional<SkillEvidence> strongest(String skill) {
        return evidenceFor(skill).stream().max(Comparator
                .comparing((SkillEvidence item) -> !item.negated())
                .thenComparingInt(SkillEvidence::evidenceLevel)
                .thenComparingDouble(SkillEvidence::confidence));
    }

    /** Stable comparison key for controlled skill aliases; original names remain in evidence. */
    public static String normalizeSkill(String skill) {
        if (skill == null) return "";
        String key = skill.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-_./]+", "");
        return switch (key) {
            case "fastapi" -> "fastapi";
            case "gpt", "gpt2", "gpt3", "gpt4", "llm", "大语言模型", "大模型" -> "llm";
            case "llmapi", "openaiapi" -> "llmapi";
            case "k8s", "kubernetes" -> "kubernetes";
            default -> key;
        };
    }

    public record SkillEvidence(
            String skill,
            String quote,
            int startOffset,
            int endOffset,
            String section,
            String projectId,
            String contextType,
            String action,
            String outcome,
            int evidenceLevel,
            boolean negated,
            double confidence
    ) {
    }
}
