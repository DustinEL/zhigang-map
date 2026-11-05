package com.zhigang.platform.career;

import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.api.Requests.MatchRunRequest;
import com.zhigang.platform.api.Requests.ResumeRequest;
import com.zhigang.platform.domain.PlatformModels.MatchReport;
import com.zhigang.platform.domain.PlatformModels.ResumeProfile;
import com.zhigang.platform.quality.DataQualityService;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import com.zhigang.platform.reporting.JobAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CareerServiceUnifiedDecisionTest {
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
    void transferableFrontendProjectDoesNotBypassAiBackendCoreCapabilities() {
        MatchReport report = match(4L, """
                学历：本科
                项目经历
                个人作品站
                使用 TypeScript 和 Vue 开发页面并部署上线。
                """);

        assertThat(report.applicationState()).isEqualTo("需先补关键能力");
        assertThat(report.scoringPolicyVersion()).isEqualTo(MatchDecisionEngine.POLICY_VERSION);
    }

    @Test
    void frozenCapabilityCardMakesDirectoryRoleEvaluable() {
        ResumeProfile resume = service.parseResume(new ResumeRequest("""
                学历：本科
                项目经历
                企业知识库助手
                使用 Python、大语言模型、RAG 和 AI Agent 开发问答系统并通过测试。
                """, 12, 2027));
        assertThat(resume.abilities()).extracting("skill")
                .contains("Python", "LLM", "RAG", "AI Agent");
        service.confirmResume(resume.id(), true);
        MatchReport report = service.match(new MatchRunRequest(resume.id(), 7L, "", ""));

        assertThat(report.applicationState()).isEqualTo("可申请实习/校招");
        assertThat(report.calibrationDatasetHash()).startsWith("sha256:");
    }

    @Test
    void reportCategoryAndReadinessComeFromTheSameOrderedDecision() {
        MatchReport gap = match(2L, "学历：本科\n项目经历\n活动网站\n使用 Vue 开发页面。");
        MatchReport near = match(2L, "学历：本科\n项目经历\n接口项目\n使用 Java 和 Spring Boot 开发接口。");
        MatchReport ready = match(2L, "学历：本科\n项目经历\n订单系统\n使用 Java、Spring Boot、MySQL 和 Docker 开发服务并上线。");

        assertThat(gap.applicationState()).isEqualTo("需先补关键能力");
        assertThat(near.applicationState()).isEqualTo("接近可申请");
        assertThat(ready.applicationState()).isEqualTo("可申请实习/校招");
        assertThat(gap.readiness()).isLessThan(near.readiness());
        assertThat(near.readiness()).isLessThan(ready.readiness());
    }

    @Test
    void unknownEducationRequiresVerificationWithoutBlockingStrongMatch() {
        MatchReport report = match(7L, """
                项目经历
                企业知识库助手
                使用 Python、LLM API、RAG 和 LangChain 开发问答系统并通过测试。
                """);

        assertThat(report.applicationState()).isEqualTo("可申请实习/校招");
        assertThat(report.hardGates()).filteredOn(gate -> gate.name().equals("学历硬门槛"))
                .singleElement().satisfies(gate -> assertThat(gate.status()).isEqualTo("RISK"));
    }

    private MatchReport match(long roleId, String text) {
        ResumeProfile resume = service.parseResume(new ResumeRequest(text, 12, 2027));
        service.confirmResume(resume.id(), true);
        return service.match(new MatchRunRequest(resume.id(), roleId, "", ""));
    }
}
