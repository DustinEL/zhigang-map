package com.zhigang.platform.source;

import com.zhigang.platform.source.StructuredJdModels.CandidateSkillEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateSkillAdvisorTest {

    @Test
    void recommendsKnownAliasWithoutWritingDecision() {
        var advice = CandidateSkillAdvisor.recommend("SpringBoot", 1, "PENDING_REVIEW", null,
                null, List.of(evidence(1, "甲公司", 0.96, true)));

        assertThat(advice.decision()).isEqualTo("APPROVED_ALIAS");
        assertThat(advice.target()).isEqualTo("Spring Boot");
    }

    @Test
    void recommendsStandardOnlyForReliableMultiCompanyEvidence() {
        var advice = CandidateSkillAdvisor.recommend("LangGraph", 2, "PENDING_REVIEW", null,
                null, List.of(evidence(1, "甲公司", 0.93, true), evidence(2, "乙公司", 0.88, true)));

        assertThat(advice.decision()).isEqualTo("APPROVED_STANDARD");
        assertThat(advice.reasons()).hasSize(2);
    }

    @Test
    void keepsSingleCompanyTechnologyUnderObservation() {
        var advice = CandidateSkillAdvisor.recommend("MCP", 1, "PENDING_REVIEW", null,
                null, List.of(evidence(1, "甲公司", 0.95, true)));

        assertThat(advice.decision()).isEqualTo("OBSERVING");
    }

    @Test
    void rejectsSentenceFragmentsAndPreservesExistingHumanDecision() {
        var rejected = CandidateSkillAdvisor.recommend("负责完成系统开发并具备沟通能力。", 3,
                "PENDING_REVIEW", null, null, List.of(evidence(1, "甲公司", 0.99, true)));
        var reused = CandidateSkillAdvisor.recommend("内部平台", 1, "OBSERVING", null,
                null, List.of(evidence(1, "甲公司", 0.70, false)));

        assertThat(rejected.decision()).isEqualTo("REJECTED_NOISE");
        assertThat(reused.decision()).isEqualTo("OBSERVING");
        assertThat(reused.reasons()).anyMatch(reason -> reason.contains("人工确认"));
    }

    private CandidateSkillEvidence evidence(long id, String company, double confidence, boolean offsetVerified) {
        return new CandidateSkillEvidence(id, "AI Agent 工程师", company, "需要 LangGraph 工程经验",
                "REQUIRED", confidence, offsetVerified);
    }
}
