package com.zhigang.platform.career;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchDecisionEngineTest {
    private final MatchDecisionEngine engine = new MatchDecisionEngine();

    @Test
    void allCoreProjectEvidenceProducesReadyFromOneOrderedScore() {
        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(card("任意岗位"), bundle(
                evidence("Java", 2, "PROJECT"),
                evidence("Spring Boot", 2, "PROJECT"),
                evidence("MySQL", 2, "PROJECT")
        ), true);

        assertThat(decision.label()).isEqualTo("可申请实习/校招");
        assertThat(decision.readiness()).isGreaterThanOrEqualTo(75);
        assertThat(decision.blockingGapCount()).isZero();
        assertThat(decision.coreCoverage()).isEqualTo(1.0);
    }

    @Test
    void bonusEvidenceCannotCompensateForMissingCoreCapabilities() {
        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(card("任意岗位"), bundle(
                evidence("Docker", 4, "PRODUCTION"),
                evidence("Kubernetes", 4, "PRODUCTION")
        ), true);

        assertThat(decision.label()).isEqualTo("需先补关键能力");
        assertThat(decision.coreCoverage()).isZero();
        assertThat(decision.blockingGapCount()).isEqualTo(2);
    }

    @Test
    void addingValidEvidenceNeverLowersReadinessOrOrderedLabel() {
        MatchDecisionEngine.MatchDecisionTrace weak = engine.evaluate(card("任意岗位"), bundle(
                evidence("Java", 2, "PROJECT")
        ), true);
        MatchDecisionEngine.MatchDecisionTrace stronger = engine.evaluate(card("任意岗位"), bundle(
                evidence("Java", 2, "PROJECT"),
                evidence("Spring Boot", 2, "PROJECT")
        ), true);

        assertThat(stronger.readiness()).isGreaterThanOrEqualTo(weak.readiness());
        assertThat(rank(stronger.label())).isGreaterThanOrEqualTo(rank(weak.label()));
        assertThat(stronger.label()).isEqualTo("接近可申请");
    }

    @Test
    void roleDisplayNameCannotChangeDecision() {
        ResumeEvidenceBundle evidence = bundle(
                evidence("Java", 2, "PROJECT"), evidence("Spring Boot", 2, "PROJECT"));

        MatchDecisionEngine.MatchDecisionTrace first = engine.evaluate(card("Java 开发工程师"), evidence, true);
        MatchDecisionEngine.MatchDecisionTrace renamed = engine.evaluate(card("完全不同的展示名称"), evidence, true);

        assertThat(renamed.label()).isEqualTo(first.label());
        assertThat(renamed.readiness()).isEqualTo(first.readiness());
    }

    @Test
    void higherLevelSelfReportCannotReplaceExistingProjectCoverage() {
        MatchDecisionEngine.MatchDecisionTrace project = engine.evaluate(card("任意岗位"), bundle(
                evidence("Java", 2, "PROJECT"),
                evidence("Spring Boot", 2, "PROJECT"),
                evidence("MySQL", 2, "PROJECT")
        ), true);
        MatchDecisionEngine.MatchDecisionTrace withSelfReport = engine.evaluate(card("任意岗位"), bundle(
                evidence("Java", 2, "PROJECT"),
                evidence("Java", 3, "SELF_REPORT"),
                evidence("Spring Boot", 2, "PROJECT"),
                evidence("MySQL", 2, "PROJECT")
        ), true);

        assertThat(withSelfReport.projectRelevance()).isEqualTo(project.projectRelevance());
        assertThat(withSelfReport.readiness()).isGreaterThanOrEqualTo(project.readiness());
        assertThat(rank(withSelfReport.label())).isGreaterThanOrEqualTo(rank(project.label()));
    }

    @Test
    void weakUncoveredEvidenceCannotInflateQualityOrBypassPerGroupReadyThreshold() {
        RoleCapabilityCard strict = new RoleCapabilityCard(2L, "STRICT", "任意岗位", "test-v2",
                List.of(
                        new RoleCapabilityCard.CapabilityGroup("language", List.of("Java"), 2, 4, true),
                        new RoleCapabilityCard.CapabilityGroup("backend", List.of("Spring Boot"), 2, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("data", List.of("MySQL"), 2, 2, false)
                ), List.of(), List.of(), List.of("fixture"), List.of("reviewer"), "TEST");

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(strict, bundle(
                evidence("Java", 2, "PROJECT"),
                evidence("Spring Boot", 4, "PRODUCTION"),
                evidence("MySQL", 4, "PRODUCTION")
        ), true);

        assertThat(decision.evidenceQuality()).isBetween(0.0, 1.0);
        assertThat(decision.label()).isEqualTo("接近可申请");
    }

    @Test
    void failedEducationGateCapsReadinessBelowReadyRange() {
        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(card("任意岗位"), bundle(
                evidence("Java", 4, "PRODUCTION"),
                evidence("Spring Boot", 4, "PRODUCTION"),
                evidence("MySQL", 4, "PRODUCTION")
        ), false);

        assertThat(decision.label()).isEqualTo("接近可申请");
        assertThat(decision.readiness()).isLessThan(75);
    }

    @Test
    void twoProjectBackedCapabilitiesOutOfFiveProduceNearNotReady() {
        RoleCapabilityCard fiveGroups = new RoleCapabilityCard(3L, "FIVE_GROUPS", "任意岗位", "test-v2",
                List.of(
                        new RoleCapabilityCard.CapabilityGroup("language", List.of("Python"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("llm", List.of("LLM"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("agent", List.of("AI Agent"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("retrieval", List.of("RAG"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("engineering", List.of("Docker"), 1, 2, false)
                ), List.of(), List.of(), List.of("fixture"), List.of("reviewer"), "TEST",
                new RoleCapabilityCard.DecisionPolicy(List.of("language"), List.of(), List.of(), 0.0,
                        0.50, 0.50, 0, 0, 2));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(fiveGroups, bundle(
                evidence("Python", 2, "PROJECT"),
                evidence("LLM", 2, "PROJECT")
        ), true);

        assertThat(decision.coreCoverage()).isEqualTo(0.4);
        assertThat(decision.blockingGapCount()).isEqualTo(2);
        assertThat(decision.label()).isEqualTo("接近可申请");
    }

    @Test
    void readyRequiresAllBlockingGroupsButAllowsWeakerOptionalGroups() {
        RoleCapabilityCard card = new RoleCapabilityCard(4L, "OPTIONAL_GROUPS", "任意岗位", "test-v2",
                List.of(
                        new RoleCapabilityCard.CapabilityGroup("language", List.of("Java"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("backend", List.of("Spring Boot"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("data", List.of("MySQL"), 1, 4, false),
                        new RoleCapabilityCard.CapabilityGroup("engineering", List.of("Docker"), 1, 4, false)
                ), List.of(), List.of(), List.of("fixture"), List.of("reviewer"), "TEST");

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(card, bundle(
                evidence("Java", 2, "PROJECT"),
                evidence("Spring Boot", 2, "PROJECT"),
                evidence("MySQL", 1, "SELF_REPORT"),
                evidence("Docker", 1, "SELF_REPORT")
        ), true);

        assertThat(decision.label()).isEqualTo("可申请实习/校招");
    }

    @Test
    void nearRequiresAtLeastOneConfiguredAnchorGroup() {
        RoleCapabilityCard anchored = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("backend"), 0.75, 0.50, 0, 0));

        MatchDecisionEngine.MatchDecisionTrace genericOnly = engine.evaluate(anchored, bundle(
                evidence("Java", 1, "SELF_REPORT"),
                evidence("MySQL", 1, "SELF_REPORT")
        ), true);
        MatchDecisionEngine.MatchDecisionTrace withAnchor = engine.evaluate(anchored, bundle(
                evidence("Java", 1, "SELF_REPORT"),
                evidence("Spring Boot", 1, "SELF_REPORT")
        ), true);

        assertThat(genericOnly.label()).isEqualTo("需先补关键能力");
        assertThat(withAnchor.label()).isEqualTo("接近可申请");
    }

    @Test
    void selfReportedAnchorCannotReachNearWithoutActionableAnchorEvidence() {
        RoleCapabilityCard anchored = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("backend"), List.of(), List.of(), 0.34, 0.75, 0.50, 0, 0));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(anchored, bundle(
                evidence("MySQL", 2, "PROJECT"),
                selfReportEvidence("Spring Boot")
        ), true);

        assertThat(decision.label()).isEqualTo("需先补关键能力");
    }

    @Test
    void unrelatedProjectsCannotMakeSelfReportedAnchorActionable() {
        RoleCapabilityCard anchored = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("backend"), List.of(), List.of(), 0.20, 0.75, 0.50, 0, 0));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(anchored, bundle(
                selfReportEvidence("Spring Boot"),
                evidence("Java", 2, "PROJECT"),
                evidence("MySQL", 2, "PROJECT")
        ), true);

        assertThat(decision.projectRelevance()).isGreaterThanOrEqualTo(0.20);
        assertThat(decision.label()).isEqualTo("需先补关键能力");
    }

    @Test
    void nearCanAllowMoreBlockingGapsWhenRolePolicySaysSo() {
        RoleCapabilityCard permissive = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("backend"), List.of(), List.of(), 0.20, 0.40, 0.50, 4, 0, 1));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(permissive, bundle(
                evidence("Spring Boot", 2, "PROJECT"),
                evidence("MySQL", 2, "PROJECT")
        ), true);

        assertThat(decision.label()).isEqualTo("接近可申请");
    }

    @Test
    void skillsListedOnlyAsSelfReportCannotReachNear() {
        RoleCapabilityCard anchored = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("language", "backend"), 0.75, 0.50, 0, 0));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(anchored, new ResumeEvidenceBundle(
                "test-extractor", "abc123", List.of(
                selfReportEvidence("Java"), selfReportEvidence("Spring Boot"))), true);

        assertThat(decision.label()).isEqualTo("需先补关键能力");
    }

    @Test
    void nearCanRequireOneAdditionalCapabilityGroup() {
        RoleCapabilityCard constrained = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("backend"), List.of("data"), List.of(), 0.0, 0.75, 0.50, 0, 0, 1));

        MatchDecisionEngine.MatchDecisionTrace anchorOnly = engine.evaluate(constrained, bundle(
                evidence("Java", 1, "SELF_REPORT"),
                evidence("Spring Boot", 1, "SELF_REPORT")
        ), true);
        MatchDecisionEngine.MatchDecisionTrace withRequiredGroup = engine.evaluate(constrained, bundle(
                evidence("Spring Boot", 1, "SELF_REPORT"),
                evidence("MySQL", 1, "SELF_REPORT")
        ), true);

        assertThat(anchorOnly.label()).isEqualTo("需先补关键能力");
        assertThat(withRequiredGroup.label()).isEqualTo("接近可申请");
    }

    @Test
    void cardCanAllowOneBlockingReadyShortfallWhenProjectGatePasses() {
        RoleCapabilityCard calibrated = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("backend"), 0.75, 0.50, 0, 1));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(calibrated, bundle(
                evidence("Java", 1, "SELF_REPORT"),
                evidence("Spring Boot", 2, "PROJECT"),
                evidence("MySQL", 2, "PROJECT")
        ), true);

        assertThat(decision.label()).isEqualTo("可申请实习/校招");
    }

    @Test
    void missingConfiguredAnchorCannotReachReadyEvenWhenShortfallIsAllowed() {
        RoleCapabilityCard anchored = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("backend"), List.of(), List.of(), 0.0, 0.50, 0.50, 1, 1));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(anchored, bundle(
                evidence("Java", 2, "PROJECT"),
                evidence("MySQL", 2, "PROJECT")
        ), true);

        assertThat(decision.label()).isNotEqualTo("可申请实习/校招");
    }

    @Test
    void nearDoesNotExpandZeroBlockingGapPolicy() {
        RoleCapabilityCard strictNear = card("任意岗位", new RoleCapabilityCard.DecisionPolicy(
                List.of("backend"), List.of(), List.of(), 0.0, 0.50, 0.50, 0, 0));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(strictNear, bundle(
                evidence("Spring Boot", 1, "PROJECT")
        ), true);

        assertThat(decision.label()).isEqualTo("需先补关键能力");
    }

    @Test
    void readyAnchorRequiresProjectOrWorkContextEvenAtHighSelfReportLevel() {
        RoleCapabilityCard anchored = new RoleCapabilityCard(3L, "AGENT", "Agent", "test-v2",
                List.of(
                        new RoleCapabilityCard.CapabilityGroup("language", List.of("Python"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("llm", List.of("LLM"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("agent", List.of("AI Agent"), 1, 2, true)
                ), List.of(), List.of(), List.of("fixture"), List.of("reviewer"), "TEST",
                new RoleCapabilityCard.DecisionPolicy(List.of("agent"), List.of(), List.of(), 0.0,
                        0.60, 0.0, 0, 0));

        MatchDecisionEngine.MatchDecisionTrace decision = engine.evaluate(anchored, new ResumeEvidenceBundle(
                "test", "hash", List.of(
                selfReportEvidenceAtLevel("Python", 4),
                selfReportEvidenceAtLevel("LLM", 4),
                selfReportEvidenceAtLevel("AI Agent", 4))), true);

        assertThat(decision.label()).isEqualTo("接近可申请");
    }

    private RoleCapabilityCard card(String name) {
        return card(name, null);
    }

    private RoleCapabilityCard card(String name, RoleCapabilityCard.DecisionPolicy decisionPolicy) {
        return new RoleCapabilityCard(2L, "JAVA_ENGINEER", name, "test-v2",
                List.of(
                        new RoleCapabilityCard.CapabilityGroup("language", List.of("Java"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("backend", List.of("Spring Boot"), 1, 2, true),
                        new RoleCapabilityCard.CapabilityGroup("data", List.of("MySQL"), 1, 2, false)
                ),
                List.of("TypeScript"), List.of("Docker", "Kubernetes"), List.of("fixture"),
                List.of("reviewer"), "TEST", decisionPolicy);
    }

    private ResumeEvidenceBundle bundle(ResumeEvidenceBundle.SkillEvidence... evidence) {
        return new ResumeEvidenceBundle("test-extractor", "abc123", List.of(evidence));
    }

    private ResumeEvidenceBundle.SkillEvidence evidence(String skill, int level, String contextType) {
        return new ResumeEvidenceBundle.SkillEvidence(skill, "使用 " + skill + " 完成项目", 0, skill.length(),
                "PROJECT", "project-1", contextType, "实现", "完成项目", level, false, 0.99);
    }

    private ResumeEvidenceBundle.SkillEvidence selfReportEvidence(String skill) {
        return new ResumeEvidenceBundle.SkillEvidence(skill, skill + " 技能", 0, skill.length(),
                "SKILLS", "", "SELF_REPORT", "", "", 1, false, 0.99);
    }

    private ResumeEvidenceBundle.SkillEvidence selfReportEvidenceAtLevel(String skill, int level) {
        return new ResumeEvidenceBundle.SkillEvidence(skill, skill + " 技能", 0, skill.length(),
                "PROFILE", "", "SELF_REPORT", "", "", level, false, 0.99);
    }

    private int rank(String label) {
        return switch (label) {
            case "可申请实习/校招" -> 2;
            case "接近可申请" -> 1;
            default -> 0;
        };
    }
}
