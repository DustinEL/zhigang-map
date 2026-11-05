package com.zhigang.platform.career;

import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.api.Requests.LearningPlanRequest;
import com.zhigang.platform.api.Requests.MatchRunRequest;
import com.zhigang.platform.api.Requests.AbilityProfileRequest;
import com.zhigang.platform.api.Requests.ResumeRequest;
import com.zhigang.platform.api.Requests.ResumeFactsUpdateRequest;
import com.zhigang.platform.api.Requests.VerificationSubmissionRequest;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.DiagnosisComparison;
import com.zhigang.platform.domain.PlatformModels.LearningPlan;
import com.zhigang.platform.domain.PlatformModels.MatchReport;
import com.zhigang.platform.domain.PlatformModels.ResumeProfile;
import com.zhigang.platform.quality.DataQualityService;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import com.zhigang.platform.reporting.JobAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CareerServiceTest {
    private CareerService service;

    @BeforeEach
    void setUp() {
        DemoPlatformDataProvider provider = new DemoPlatformDataProvider();
        SkillNormalizationService normalization = new SkillNormalizationService(provider);
        DataQualityService quality = new DataQualityService(provider, 180, "v1.0.0");
        JobAnalysisService analysis = new JobAnalysisService(provider, normalization, quality, 8, 5, 2);
        service = new CareerService(normalization, provider, analysis);
    }

    @Test
    void matchUsesEvidenceLevelAndReturnsExplainableDimensions() {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "姓名：测试学生\n学历：本科 软件工程\n项目：使用 Python、RAG 和大语言模型开发知识库问答系统，完成向量数据库检索。",
                12,
                2027
        ));
        service.confirmResume(resume.id(), true);

        MatchReport report = service.match(new MatchRunRequest(resume.id(), 1L, "", ""));

        assertThat(report.graphRelease()).isEqualTo("2026.07");
        assertThat(report.dimensions()).hasSize(6);
        assertThat(report.dimensions().stream().mapToDouble(value -> value.weight()).sum()).isEqualTo(1.0);
        assertThat(report.skillDetails()).anyMatch(detail -> detail.skill().equals("RAG") && detail.evidenceLevel() >= 2);
        assertThat(report.readiness()).isBetween(0.0, 100.0);
        assertThat(report.scoringPolicyVersion()).isEqualTo("resume-match-logistic-v1.0");
        assertThat(report.calibrationDatasetHash()).startsWith("sha256:");
        assertThat(report.hardGates()).extracting("name").contains("学历硬门槛", "必备技能门槛");
    }

    @Test
    void sensitiveNameChangeDoesNotAffectReadiness() {
        String template = "姓名：%s\n学历：本科 软件工程\n项目：使用 Java、Spring Boot、MySQL、Redis 和 Docker 开发后端平台。";
        ResumeProfile first = service.parseResume(new ResumeRequest(template.formatted("张同学"), 10, 2027));
        ResumeProfile second = service.parseResume(new ResumeRequest(template.formatted("李同学"), 10, 2027));
        service.confirmResume(first.id(), true);
        service.confirmResume(second.id(), true);

        MatchReport firstReport = service.match(new MatchRunRequest(first.id(), 2L, "", ""));
        MatchReport secondReport = service.match(new MatchRunRequest(second.id(), 2L, "", ""));

        assertThat(firstReport.readiness()).isEqualTo(secondReport.readiness());
    }

    @Test
    void strongDirectSkillMatchIsReadyWithoutRepeatedEvidencePenalty() {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "学历：本科\n工作经历\n负责 Java、Spring Boot、MySQL、Redis、Linux 和 Docker 后端服务开发并上线。\n"
                        + "项目经历\n订单平台\n实现缓存、数据库与微服务治理。",
                12, 2027));
        service.confirmResume(resume.id(), true);

        MatchReport report = service.match(new MatchRunRequest(resume.id(), 2L, "", ""));

        assertThat(report.applicationState()).isEqualTo("可申请实习/校招");
    }

    @Test
    void missingBothCoreSkillsKeepsSkillGateAtRisk() {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "学历：本科\n项目经历\n校园活动管理\n负责需求整理与文档编写。", 12, 2027));
        service.confirmResume(resume.id(), true);

        MatchReport report = service.match(new MatchRunRequest(resume.id(), 2L, "", ""));

        assertThat(report.hardGates()).filteredOn(gate -> gate.name().equals("必备技能门槛"))
                .singleElement().extracting("status").isEqualTo("RISK");
        assertThat(report.applicationState()).isEqualTo("需先补关键能力");
    }

    @Test
    void unrelatedProjectDoesNotQualifyForAiApplicationBackend() {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "学历：本科\n项目经历\n校园摄影作品集\n负责照片整理、视觉排版和活动宣传。", 12, 2027));
        service.confirmResume(resume.id(), true);

        MatchReport report = service.match(new MatchRunRequest(resume.id(), 4L, "", ""));

        assertThat(report.applicationState()).isEqualTo("需先补关键能力");
    }

    @Test
    void learningPlanOrdersPrerequisitesBeforeDependentSkills() {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "姓名：路径测试\n学历：本科 软件工程\n技能：Java。",
                12,
                2027
        ));
        service.confirmResume(resume.id(), true);
        MatchReport report = service.match(new MatchRunRequest(resume.id(), 1L, "", ""));

        LearningPlan plan = service.learningPlan(new LearningPlanRequest(report.id(), 12));
        List<String> skills = plan.items().stream().map(item -> item.skill()).toList();

        assertThat(skills.indexOf("Python")).isLessThan(skills.indexOf("Embedding"));
        assertThat(skills.indexOf("Embedding")).isLessThan(skills.indexOf("向量数据库"));
        assertThat(skills.indexOf("向量数据库")).isLessThan(skills.indexOf("RAG"));
    }

    @Test
    void learningPlanCreatesOneTaskPerMissingRequiredSkill() {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "姓名：逐项计划测试\n学历：本科 软件工程\n技能：Java。",
                12,
                2027
        ));
        service.confirmResume(resume.id(), true);
        MatchReport report = service.match(new MatchRunRequest(resume.id(), 1L, "", ""));

        List<String> missingSkills = report.skillDetails().stream()
                .filter(detail -> detail.requiredLevel() >= 2 && detail.score() < 80)
                .map(detail -> detail.skill())
                .distinct()
                .toList();
        LearningPlan plan = service.learningPlan(new LearningPlanRequest(report.id(), 12));

        assertThat(plan.items()).hasSize(missingSkills.size());
        assertThat(plan.items()).extracting(item -> item.skill()).containsExactlyInAnyOrderElementsOf(missingSkills);
        assertThat(plan.items()).allSatisfy(item -> {
            assertThat(item.skill()).doesNotContain("、", ",");
            assertThat(item.practicalTask()).isNotBlank();
            assertThat(item.acceptanceCriteria()).isNotBlank();
            assertThat(item.evidenceDeliverable()).isNotBlank();
        });
        assertThat(plan.estimatedWeeks()).isBetween(4, 6);
    }

    @Test
    void unconfirmedResumeCannotStartMatching() {
        ResumeProfile resume = service.parseResume(new ResumeRequest("姓名：待确认\n学历：本科 软件工程", 8, 2027));

        assertThatThrownBy(() -> service.match(new MatchRunRequest(resume.id(), 1L, "", "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("确认简历");
    }

    @Test
    void emergingRoleCannotStartMatchingOrLearningPlan() {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "姓名：新型岗位测试\n学历：本科 软件工程\n技能：Python、RAG。", 12, 2027));
        service.confirmResume(resume.id(), true);

        assertThatThrownBy(() -> service.match(new MatchRunRequest(resume.id(), 6L, "", "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("新型岗位");
    }

    @Test
    void strongAutomatedEvidenceOnlyImprovesDiagnosisAfterProfessionalReview() {
        ResumeProfile resume = service.parseResume(new ResumeRequest("姓名：验证测试\n学历：本科 软件工程\n技能：Java。", 12, 2027));
        service.confirmResume(resume.id(), true);
        MatchReport before = service.match(new MatchRunRequest(resume.id(), 1L, "", ""));
        LearningPlan plan = service.learningPlan(new LearningPlanRequest(before.id(), 12));

        String skill = plan.items().get(0).skill();
        var submission = service.submitVerification(new VerificationSubmissionRequest(
                plan.id(), 1, "TEST_RESULT", "", "已使用 " + skill + " 完成接口测试，测试通过 18/18，覆盖率 86%"
        ));

        assertThat(submission.status()).isEqualTo("PENDING_VERIFICATION");
        assertThat(submission.reviewReason()).contains("自动校验建议：VERIFIED");
        assertThat(service.resume(resume.id()).abilities())
                .noneMatch(value -> value.skill().equals(skill) && "VERIFIED".equals(value.verificationStatus()));

        var reviewed = service.reviewVerification(submission.id(), "VERIFIED", "指标与任务产物可复核", "专业审核员");
        DiagnosisComparison comparison = service.compareDiagnosis(before.id(), reviewed.id());

        assertThat(comparison.afterReadiness()).isGreaterThan(comparison.beforeReadiness());
        assertThat(comparison.upgradedSkills()).contains(reviewed.skill());
    }

    @Test
    void emptyOrIrrelevantEvidenceCannotImproveAbility() {
        ResumeProfile resume = service.parseResume(new ResumeRequest("学历：本科 软件工程\n技能：Java。", 12, 2027));
        service.confirmResume(resume.id(), true);
        MatchReport before = service.match(new MatchRunRequest(resume.id(), 1L, "", ""));
        LearningPlan plan = service.learningPlan(new LearningPlanRequest(before.id(), 12));

        var submission = service.submitVerification(new VerificationSubmissionRequest(
                plan.id(), 1, "PROJECT_LINK", "", "与目标技能无关的空链接说明"
        ));

        assertThat(submission.status()).isEqualTo("PENDING_VERIFICATION");
        assertThat(submission.reviewReason()).contains("自动校验建议：REJECTED");
        assertThatThrownBy(() -> service.compareDiagnosis(before.id(), submission.id()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("VERIFIED");
        assertThat(service.resume(resume.id()).abilities()).allMatch(value -> !"VERIFIED".equals(value.verificationStatus()));
    }

    @Test
    void everySupportedEvidenceTypeRemainsPendingAfterSubmission() {
        ResumeProfile resume = service.parseResume(new ResumeRequest("学历：本科 软件工程\n技能：Java。", 12, 2027));
        service.confirmResume(resume.id(), true);
        MatchReport before = service.match(new MatchRunRequest(resume.id(), 1L, "", ""));
        LearningPlan plan = service.learningPlan(new LearningPlanRequest(before.id(), 12));
        String skill = plan.items().get(0).skill();
        String evidence = "使用 " + skill + " 完成代码模块、README、证书说明和接口测试，测试通过 20/20，覆盖率 90%。";

        List<String> evidenceTypes = List.of("PROJECT_LINK", "CODE_DESCRIPTION", "TEST_RESULT", "DOCUMENT", "CERTIFICATE");
        var submissions = evidenceTypes.stream().map(type -> service.submitVerification(new VerificationSubmissionRequest(
                plan.id(), 1, type, "https://example.test/evidence", evidence))).toList();

        assertThat(submissions).allMatch(value -> "PENDING_VERIFICATION".equals(value.status()))
                .allMatch(value -> value.verifiedAt() == null)
                .allMatch(value -> value.validationChecks().stream().anyMatch(check -> check.startsWith("自动校验建议=")));
        assertThat(service.resume(resume.id()).abilities())
                .noneMatch(value -> value.skill().equals(skill) && "VERIFIED".equals(value.verificationStatus()));
    }

    @Test
    void pendingEvidenceOnlyImprovesDiagnosisAfterProfessionalReview() {
        ResumeProfile resume = service.parseResume(new ResumeRequest("学历：本科 软件工程\n技能：Java。", 12, 2027));
        service.confirmResume(resume.id(), true);
        MatchReport before = service.match(new MatchRunRequest(resume.id(), 1L, "", ""));
        LearningPlan plan = service.learningPlan(new LearningPlanRequest(before.id(), 12));
        String skill = plan.items().get(0).skill();

        var pending = service.submitVerification(new VerificationSubmissionRequest(
                plan.id(), 1, "DOCUMENT", "", "使用 " + skill + " 编写项目文档并记录实现过程"
        ));

        assertThat(pending.status()).isEqualTo("PENDING_VERIFICATION");
        assertThat(service.resume(resume.id()).abilities())
                .noneMatch(value -> value.skill().equals(skill) && "VERIFIED".equals(value.verificationStatus()));
        assertThat(service.verificationSubmissions("PENDING_VERIFICATION", 0, 20).items())
                .extracting("id").containsExactly(pending.id());
        assertThatThrownBy(() -> service.compareDiagnosis(before.id(), pending.id()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("VERIFIED");

        var reviewed = service.reviewVerification(pending.id(), "VERIFIED", "实现说明与任务目标一致", "专业审核员");
        DiagnosisComparison comparison = service.compareDiagnosis(before.id(), reviewed.id());

        assertThat(reviewed.status()).isEqualTo("VERIFIED");
        assertThat(comparison.afterReadiness()).isGreaterThan(comparison.beforeReadiness());
        assertThat(comparison.upgradedSkills()).contains(skill);
        assertThatThrownBy(() -> service.reviewVerification(pending.id(), "REJECTED", "重复审核", "专业审核员"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能重复");
    }

    @Test
    void verificationQueueValidatesStatusAndPagination() {
        assertThatThrownBy(() -> service.verificationSubmissions("UNKNOWN", 0, 20))
                .isInstanceOf(BusinessException.class).hasMessageContaining("状态只能");
        assertThatThrownBy(() -> service.verificationSubmissions(null, -1, 20))
                .isInstanceOf(BusinessException.class).hasMessageContaining("页码");
        assertThatThrownBy(() -> service.verificationSubmissions(null, 0, 101))
                .isInstanceOf(BusinessException.class).hasMessageContaining("1 至 100");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedReviewPreconditionsNeverLeaveSubmissionVerified() {
        ResumeProfile resume = service.parseResume(new ResumeRequest("学历：本科 软件工程\n技能：Java。", 12, 2027));
        service.confirmResume(resume.id(), true);
        MatchReport before = service.match(new MatchRunRequest(resume.id(), 1L, "", ""));
        LearningPlan plan = service.learningPlan(new LearningPlanRequest(before.id(), 12));
        String skill = plan.items().get(0).skill();
        var pending = service.submitVerification(new VerificationSubmissionRequest(
                plan.id(), 1, "DOCUMENT", "", "使用 " + skill + " 编写项目文档并记录实现过程"));

        Map<Long, Object> planStore = (Map<Long, Object>) ReflectionTestUtils.getField(service, "plans");
        Map<Long, Object> matchStore = (Map<Long, Object>) ReflectionTestUtils.getField(service, "matches");
        Map<Long, Object> resumeStore = (Map<Long, Object>) ReflectionTestUtils.getField(service, "resumes");
        assertThat(planStore).isNotNull();
        assertThat(matchStore).isNotNull();
        assertThat(resumeStore).isNotNull();

        Object storedPlan = planStore.remove(plan.id());
        assertThatThrownBy(() -> service.reviewVerification(pending.id(), "VERIFIED", "通过", "专业审核员"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("成长计划");
        assertSubmissionPending(pending.id());
        planStore.put(plan.id(), storedPlan);

        Object storedMatch = matchStore.remove(before.id());
        assertThatThrownBy(() -> service.reviewVerification(pending.id(), "VERIFIED", "通过", "专业审核员"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("匹配任务");
        assertSubmissionPending(pending.id());
        matchStore.put(before.id(), storedMatch);

        Object storedResume = resumeStore.remove(resume.id());
        assertThatThrownBy(() -> service.reviewVerification(pending.id(), "VERIFIED", "通过", "专业审核员"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("能力档案");
        assertSubmissionPending(pending.id());
        resumeStore.put(resume.id(), storedResume);
    }

    private void assertSubmissionPending(long submissionId) {
        assertThat(service.verificationSubmissions(null, 0, 100).items())
                .filteredOn(value -> value.id() == submissionId)
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo("PENDING_VERIFICATION");
                    assertThat(value.verifiedAt()).isNull();
                });
    }

    @Test
    void redactsPersonallyIdentifiableInformationBeforeCreatingFacts() {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "姓名：张三\n电话：13800138000\n邮箱：zhangsan@example.com\n地址：南京市鼓楼区测试路 1 号\n学历：本科\n专业：软件工程\n项目：使用 Java 开发接口。",
                10, 2027));

        assertThat(resume.name()).isEqualTo("已脱敏候选人");
        assertThat(resume.redactedFields()).containsExactlyInAnyOrder("NAME", "PHONE", "EMAIL", "ADDRESS");
        assertThat(resume.facts()).isNotEmpty().allSatisfy(fact -> assertThat(fact.evidenceQuote())
                .doesNotContain("张三", "13800138000", "zhangsan@example.com", "测试路 1 号"));
    }

    @Test
    void anonymousProfilesAreIsolatedAndDeletedImmediately() {
        ResumeProfile resume = service.createAbilityProfile(new AbilityProfileRequest(
                "匿名同学", "本科", "软件工程", 2027, 10,
                List.of("Java", "Spring Boot"), List.of("后端课程项目"), List.of()
        ), "session-a");

        assertThatThrownBy(() -> service.resume(resume.id(), "session-b"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前会话");

        ResumeProfile updated = service.updateResume(resume.id(), new ResumeFactsUpdateRequest(
                true, "匿名同学", "本科", "软件工程", 2027, 10,
                List.of("Java", "Spring Boot", "MySQL"), List.of("后端课程项目"), List.of()
        ), "session-a");
        assertThat(updated.facts()).anySatisfy(fact -> {
            assertThat(fact.factType()).isEqualTo("SKILL");
            assertThat(fact.normalizedValue()).isEqualTo("MySQL");
            assertThat(fact.startOffset()).isEqualTo(-1);
            assertThat(fact.sourceType()).isEqualTo("USER_CORRECTION");
            assertThat(fact.verificationStatus()).isEqualTo("PENDING_VERIFICATION");
        });
        MatchReport report = service.match(new MatchRunRequest(updated.id(), 2L, "", ""), "session-a");
        service.deleteResume(updated.id(), "session-a");

        assertThatThrownBy(() -> service.resume(updated.id(), "session-a"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或不属于");
        assertThatThrownBy(() -> service.match(report.id(), "session-a"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("匹配任务");
    }

    @Test
    void frozenCapabilityCardAllowsDirectoryRoleDiagnosis() {
        ResumeProfile resume = service.parseResume(new ResumeRequest("学历：本科 软件工程\n技能：Java。", 8, 2027));
        service.confirmResume(resume.id(), true);

        MatchReport report = service.match(new MatchRunRequest(resume.id(), 7L, "", ""));

        assertThat(report.applicationState()).isEqualTo("需先补关键能力");
        assertThat(report.scoringPolicyVersion()).isEqualTo(MatchDecisionEngine.POLICY_VERSION);
    }

    @Test
    void nameOnlyResumeUpdatePreservesLocatedEvidence() throws Exception {
        ResumeProfile resume = service.parseResume(new ResumeRequest(
                "姓名：旧姓名\n学历：本科\n项目经历：使用 Java 和 Spring Boot 开发服务。", 10, 2027));
        ResumeEvidenceBundle before = evidenceBundle(resume.id());

        service.updateResume(resume.id(), new ResumeFactsUpdateRequest(
                true, "新姓名", null, null, null, null, null, null, null), "TEST_SESSION");
        ResumeEvidenceBundle after = evidenceBundle(resume.id());

        assertThat(after.sourceSha256()).isEqualTo(before.sourceSha256());
        assertThat(after.positiveEvidenceFor("Java")).isEqualTo(before.positiveEvidenceFor("Java"));
        assertThat(after.positiveEvidenceFor("Java")).allSatisfy(item -> assertThat(item.startOffset()).isGreaterThanOrEqualTo(0));
    }

    @Test
    void rejectsImplausibleLabeledMajorAndReplacesTheSameProfileWhenReparsed() {
        ResumeProfile original = service.parseResume(new ResumeRequest(
                "姓名：匿名同学\n学历：本科\n专业：的 我精通python\n技能：Python。", 8, 2027
        ), "session-a");

        assertThat(original.major()).isEqualTo("未明确");

        ResumeProfile reparsed = service.reparseResume(original.id(), new ResumeRequest(
                "姓名：匿名同学\n学历：本科\n专业：软件工程\n项目：使用 Java 和 Spring Boot 开发后端系统。",
                10, 2027
        ), "session-a");

        assertThat(reparsed.id()).isEqualTo(original.id());
        assertThat(reparsed.major()).isEqualTo("软件工程");
        assertThat(reparsed.abilities()).extracting("skill").contains("Java", "Spring Boot").doesNotContain("Python");
    }

    @SuppressWarnings("unchecked")
    private ResumeEvidenceBundle evidenceBundle(long resumeId) throws Exception {
        java.lang.reflect.Field field = CareerService.class.getDeclaredField("resumeEvidence");
        field.setAccessible(true);
        return ((java.util.Map<Long, ResumeEvidenceBundle>) field.get(service)).get(resumeId);
    }

    @Test
    void recognizesExplicitUndergraduateExpressionsWithoutGuessingFromUniversityName() {
        ResumeProfile explicit = service.parseResume(new ResumeRequest(
                "教育背景\n西安邮电大学\n本科在读\n专业：计算机科学与技术", 8, 2027));
        ResumeProfile unclear = service.parseResume(new ResumeRequest(
                "教育背景\n吉首大学\n专业：软件工程", 8, 2027));

        assertThat(explicit.education()).isEqualTo("本科");
        assertThat(unclear.education()).isEqualTo("未明确");
    }

    @Test
    void projectFactsExcludeSectionHeadingsSkillsAndSourceNotes() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "个人简介\n参与企业级业务系统建设\n专业技能\n开发平台：Linux、Docker\n项目经历\nSmartParking 智能停车系统\n使用 C++ 和 Qt 完成管理模块\n原始来源与使用说明\n项目来源 github.com/example", 8, 2027));

        assertThat(profile.projects()).contains("SmartParking 智能停车系统")
                .doesNotContain("项目经历", "开发平台：Linux、Docker", "原始来源与使用说明");
    }

    @Test
    void retainsProjectDescriptionsUnderBilingualChineseProjectHeading() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "项目经历（Projects）\n智能停车系统\n负责使用 Java 和 Spring Boot 实现订单管理与 Redis 缓存\n教育背景\n本科", 8, 2027));

        assertThat(profile.projects()).containsExactly(
                "智能停车系统",
                "负责使用 Java 和 Spring Boot 实现订单管理与 Redis 缓存");
    }

    @Test
    void retainsProjectDescriptionsUnderEnglishProjectExperienceHeading() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "Project Experience\nRecommendation Platform\nBuilt a recommendation service with Python and FastAPI\nSkills\nPython", 8, 2027));

        assertThat(profile.projects()).containsExactly(
                "Recommendation Platform",
                "Built a recommendation service with Python and FastAPI");
    }

    @Test
    void normalizesCommonEnglishDegreeExpressions() {
        ResumeProfile bachelor = service.parseResume(new ResumeRequest(
                "Education\nBachelor of Engineering in Electronics", 8, 2027));
        ResumeProfile abbreviatedBachelor = service.parseResume(new ResumeRequest(
                "Education\nB.Tech in Computer Science", 8, 2027));
        ResumeProfile bachelorEngineering = service.parseResume(new ResumeRequest(
                "Education\nB.Eng. in Electronics", 8, 2027));
        ResumeProfile bachelorScience = service.parseResume(new ResumeRequest(
                "Education\nBSc in Computer Science", 8, 2027));
        ResumeProfile master = service.parseResume(new ResumeRequest(
                "Education\nMaster's in Software Engineering", 8, 2027));
        ResumeProfile masterScience = service.parseResume(new ResumeRequest(
                "Education\nMSci in Computer Science", 8, 2027));
        ResumeProfile doctorate = service.parseResume(new ResumeRequest(
                "Education\nPh.D. in Computer Science", 8, 2027));
        ResumeProfile diploma = service.parseResume(new ResumeRequest(
                "Education\nDiploma in Computer Science and Technology", 8, 2027));

        assertThat(bachelor.education()).isEqualTo("本科");
        assertThat(abbreviatedBachelor.education()).isEqualTo("本科");
        assertThat(bachelorEngineering.education()).isEqualTo("本科");
        assertThat(bachelorScience.education()).isEqualTo("本科");
        assertThat(master.education()).isEqualTo("硕士");
        assertThat(masterScience.education()).isEqualTo("硕士");
        assertThat(doctorate.education()).isEqualTo("博士");
        assertThat(diploma.education()).isEqualTo("专科");
    }

    @Test
    void treatsProfessionalExperienceAsProjectEvidenceForMatching() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "Professional Experience\nSenior\nSoftware\nEngineer\nDeveloped Java services and improved latency by 40%\nEducation\nBachelor of Science", 8, 2027));

        assertThat(profile.projects())
                .contains("Developed Java services and improved latency by 40%")
                .doesNotContain("Senior", "Software", "Engineer");
    }

    @Test
    void recognizesKeyProjectsHeading() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "Key Projects\nTinyMathReason\nPretrained a transformer with Python and PyTorch\nEducation\nBachelor of Technology", 8, 2027));

        assertThat(profile.projects()).containsExactly(
                "TinyMathReason",
                "Pretrained a transformer with Python and PyTorch");
    }

    @Test
    void prefersExplicitProjectsOverEarlierProfessionalExperience() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "Professional Experience\nDeveloped backend APIs with Java\nKey Projects\nTinyMathReason\nPretrained a transformer with Python and PyTorch\nEducation\nBachelor of Technology", 8, 2027));

        assertThat(profile.projects()).containsExactly(
                "TinyMathReason",
                "Pretrained a transformer with Python and PyTorch");
    }

    @Test
    void recognizesProjectTitleInsideProfessionalExperience() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "Professional Experience\nProject Title Novo Remitall\nDeveloped payment APIs with Java and Spring Boot\nProject Title Authentication\nImplemented OAuth2 login flows\nEducation\nBachelor of Technology", 8, 2027));

        assertThat(profile.projects()).containsExactly(
                "Project Title Novo Remitall",
                "Developed payment APIs with Java and Spring Boot",
                "Project Title Authentication",
                "Implemented OAuth2 login flows");
    }

    @Test
    void navigationProjectsHeadingDoesNotCaptureFollowingNavigationItems() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "Home\nAbout\nProjects\nJohn Doe\nDeveloped React interfaces\nEducation\nBachelor of Technology", 8, 2027));

        assertThat(profile.projects()).isEmpty();
    }

    @Test
    void businessTermsDoNotImplyEnglishEducation() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "Professional Experience\nBuilt a master data platform and diploma verification service", 8, 2027));

        assertThat(profile.education()).isEqualTo("未明确");
    }

    @Test
    void topNavigationDoesNotHideLaterRealProjectsSection() {
        ResumeProfile profile = service.parseResume(new ResumeRequest(
                "Home\nAbout\nContact\nSummary\nBackend engineer\nProjects\nDashboard Project\nBuilt React interfaces\nEducation\nBachelor of Engineering", 8, 2027));

        assertThat(profile.projects()).containsExactly(
                "Dashboard Project",
                "Built React interfaces");
    }
}
