package com.zhigang.platform.career;

import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.domain.PlatformModels.AbilityEvidence;
import com.zhigang.platform.emerging.EmbeddingClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SkillAlignmentService {
    private static final double SEMANTIC_THRESHOLD = 0.82;
    private final SkillNormalizationService normalizationService;
    private final EmbeddingClient embeddingClient;

    @Autowired
    public SkillAlignmentService(SkillNormalizationService normalizationService, EmbeddingClient embeddingClient) {
        this.normalizationService = normalizationService;
        this.embeddingClient = embeddingClient;
    }

    SkillAlignmentService(SkillNormalizationService normalizationService) {
        this(normalizationService, null);
    }

    public Map<String, Alignment> align(List<String> roleSkills, List<AbilityEvidence> abilities) {
        Map<String, Alignment> result = new LinkedHashMap<>();
        for (String roleSkill : roleSkills) {
            for (AbilityEvidence ability : abilities) {
                String canonical = normalizationService.canonicalize(ability.skill()).orElse(ability.skill());
                if (roleSkill.equalsIgnoreCase(canonical)) {
                    String method = roleSkill.equalsIgnoreCase(ability.skill()) ? "EXACT" : "ALIAS";
                    result.put(roleSkill, new Alignment(ability, method, 1.0, true));
                    break;
                }
            }
        }
        List<String> unmatched = roleSkills.stream().filter(skill -> !result.containsKey(skill)).toList();
        if (unmatched.isEmpty() || abilities.isEmpty() || embeddingClient == null || !embeddingClient.available()) {
            return Map.copyOf(result);
        }
        try {
            List<String> texts = new ArrayList<>(unmatched);
            texts.addAll(abilities.stream().map(AbilityEvidence::skill).toList());
            List<List<Double>> vectors = embeddingClient.embed(texts);
            for (int roleIndex = 0; roleIndex < unmatched.size(); roleIndex++) {
                double best = -1;
                AbilityEvidence bestAbility = null;
                for (int abilityIndex = 0; abilityIndex < abilities.size(); abilityIndex++) {
                    double similarity = cosine(vectors.get(roleIndex), vectors.get(unmatched.size() + abilityIndex));
                    if (similarity > best) {
                        best = similarity;
                        bestAbility = abilities.get(abilityIndex);
                    }
                }
                if (bestAbility != null && best >= SEMANTIC_THRESHOLD) {
                    result.put(unmatched.get(roleIndex), new Alignment(bestAbility, "SEMANTIC_CONFIRMED", best, true));
                }
            }
        } catch (RuntimeException ignored) {
            // 向量服务不可用时只保留精确与别名结果，避免猜测式匹配。
        }
        return Map.copyOf(result);
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    public record Alignment(AbilityEvidence evidence, String method, double similarity, boolean manualConfirmed) {
    }
}
