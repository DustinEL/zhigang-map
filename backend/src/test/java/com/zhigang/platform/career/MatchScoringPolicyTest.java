package com.zhigang.platform.career;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class MatchScoringPolicyTest {
    @Test
    void loadsFrozenWeightsAndMatchesValidationDatasetHash() throws Exception {
        MatchScoringPolicy policy = new MatchScoringPolicy(new ObjectMapper());
        byte[] validation = Files.readAllBytes(Path.of("..", "test-data", "evaluation", "resume-match-validation.jsonl"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(validation));

        double total = policy.weight(MatchScoringPolicy.REQUIRED_SKILLS)
                + policy.weight(MatchScoringPolicy.RESPONSIBILITIES)
                + policy.weight(MatchScoringPolicy.BONUS_SKILLS)
                + policy.weight(MatchScoringPolicy.PROFICIENCY)
                + policy.weight(MatchScoringPolicy.SCENARIOS)
                + policy.weight(MatchScoringPolicy.CERTIFICATES);

        assertThat(total).isEqualTo(1.0);
        assertThat(policy.validationDatasetHash()).isEqualTo("sha256:" + hash);
        assertThat(policy.calibrationSummary()).contains("brier=0.099", "n=6", "scope=smoke-fixture");
    }
}
