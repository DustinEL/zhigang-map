package com.zhigang.platform.source;

import com.zhigang.platform.source.StructuredJdModels.CandidateSkillEvidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CandidateSkillAdvisor {
    private static final Set<String> FINAL_DECISIONS = Set.of(
            "APPROVED_STANDARD", "APPROVED_ALIAS", "OBSERVING", "REJECTED_NOISE"
    );
    private static final Map<String, String> COMMON_ALIASES = Map.of(
            "springboot", "Spring Boot",
            "mysqldb", "MySQL",
            "mysql数据库", "MySQL",
            "java语言", "Java"
    );
    private static final Set<String> GENERIC_NOISE = Set.of(
            "技术", "能力", "经验", "项目经验", "开发经验", "沟通能力", "学习能力"
    );

    private CandidateSkillAdvisor() {
    }

    public static Advice recommend(String skill, int companyCount, String existingDecision,
                                   String existingTarget, String knownAliasTarget,
                                   List<CandidateSkillEvidence> evidence) {
        if (FINAL_DECISIONS.contains(existingDecision)) {
            return new Advice(existingDecision, existingTarget,
                    List.of("已存在人工确认结果，建议沿用候选技能注册表决定"));
        }

        String aliasTarget = firstNonBlank(knownAliasTarget, COMMON_ALIASES.get(normalize(skill)));
        if (aliasTarget != null && !aliasTarget.equalsIgnoreCase(skill.trim())) {
            return new Advice("APPROVED_ALIAS", aliasTarget,
                    List.of("名称与已知标准技能别名一致", "确认后后续 JD 可自动复用"));
        }

        if (isNoise(skill)) {
            return new Advice("REJECTED_NOISE", null,
                    List.of("内容更像句子碎片、泛化能力或无稳定技术含义"));
        }

        boolean evidenceReliable = !evidence.isEmpty() && evidence.stream()
                .allMatch(item -> item.offsetVerified() && item.confidence() >= 0.85);
        if (companyCount >= 2 && evidenceReliable && looksTechnical(skill)) {
            return new Advice("APPROVED_STANDARD", null,
                    List.of("至少两家不同企业出现", "原文偏移有效且置信度不低于 0.85"));
        }

        List<String> reasons = new ArrayList<>();
        reasons.add(companyCount < 2 ? "目前仅有单一企业证据" : "现有证据稳定性不足");
        reasons.add("建议先作为观察项，不直接进入正式标准技能");
        return new Advice("OBSERVING", null, List.copyOf(reasons));
    }

    private static boolean isNoise(String skill) {
        if (skill == null) {
            return true;
        }
        String value = skill.trim();
        if (value.length() < 2 || value.length() > 32 || GENERIC_NOISE.contains(value)) {
            return true;
        }
        return value.matches(".*[。；！？\\n\\r].*")
                || (value.length() > 12 && value.matches(".*(负责|熟悉|掌握|具备|能力|经验|优先|要求).*"));
    }

    private static boolean looksTechnical(String skill) {
        String value = skill == null ? "" : skill.trim();
        return value.matches("[A-Za-z][A-Za-z0-9+.#/_ -]{1,30}")
                || value.matches("[\\p{IsHan}A-Za-z0-9+.#/_-]{2,18}");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    public record Advice(String decision, String target, List<String> reasons) {
    }
}
