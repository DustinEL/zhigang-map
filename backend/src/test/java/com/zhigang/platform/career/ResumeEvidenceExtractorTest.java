package com.zhigang.platform.career;

import com.zhigang.platform.domain.PlatformModels.ResumeFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeEvidenceExtractorTest {
    private final ResumeEvidenceExtractor extractor = new ResumeEvidenceExtractor();

    @Test
    void evidenceLookupNormalizesSkillAliasesAndFormatting() {
        ResumeEvidenceBundle bundle = new ResumeEvidenceBundle("test", "hash", List.of(
                new ResumeEvidenceBundle.SkillEvidence("FastApi", "FastApi", 0, 7,
                        "PROJECT", "project-1", "PROJECT", "实现", "", 2, false, 1.0),
                new ResumeEvidenceBundle.SkillEvidence("GPT", "GPT", 8, 11,
                        "PROJECT", "project-1", "PROJECT", "调用", "", 2, false, 1.0),
                new ResumeEvidenceBundle.SkillEvidence("LLM API", "LLM API", 12, 19,
                        "PROJECT", "project-1", "PROJECT", "调用", "", 2, false, 1.0),
                new ResumeEvidenceBundle.SkillEvidence("K8s", "K8s", 20, 23,
                        "PROJECT", "project-1", "PROJECT", "部署", "", 2, false, 1.0)));

        assertThat(bundle.positiveEvidenceFor("fast api")).hasSize(1);
        assertThat(bundle.positiveEvidenceFor("FastAPI")).hasSize(1);
        assertThat(bundle.positiveEvidenceFor("大语言模型")).hasSize(1);
        assertThat(bundle.positiveEvidenceFor("LLM API")).hasSize(1);
        assertThat(bundle.positiveEvidenceFor("Kubernetes")).hasSize(1);
    }

    @Test
    void strongestEvidenceUsesLaterProjectPracticeInsteadOfFirstSkillMention() {
        String text = "专业技能\nPython（学习中）\n项目经历\n推荐系统\n使用 Python 开发 ETL 管道并上线生产环境。";
        int first = text.indexOf("Python");
        ResumeFact fact = skillFact("Python", first);

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(fact));

        assertThat(bundle.evidenceFor("Python")).hasSize(2);
        assertThat(bundle.strongest("Python")).get().satisfies(evidence -> {
            assertThat(evidence.evidenceLevel()).isEqualTo(4);
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
            assertThat(evidence.projectId()).isEqualTo("project-1");
            assertThat(evidence.quote()).contains("上线生产环境");
        });
    }

    @Test
    void productionContainerizationAliasCreatesStrongDockerEvidence() {
        String text = "工作经历\n容器化 FastAPI 服务并上线生产环境。";
        int start = text.indexOf("容器化");
        ResumeFact fact = new ResumeFact("SKILL", "容器化", "Docker", start, start + 3,
                "容器化 FastAPI 服务并上线生产环境", 0, "RESUME_TEXT",
                "PENDING_VERIFICATION", 0.96);

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(fact));

        assertThat(bundle.strongest("Docker")).hasValueSatisfying(evidence -> {
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
            assertThat(evidence.evidenceLevel()).isEqualTo(4);
            assertThat(evidence.action()).isEqualTo("容器化");
        });
    }

    @Test
    void negatedSkillMentionNeverCreatesPositiveEvidence() {
        String text = "项目经历\n课程平台\n本项目未使用 Docker，采用本地脚本部署。";
        int start = text.indexOf("Docker");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Docker", start)));

        assertThat(bundle.strongest("Docker")).get().satisfies(evidence -> {
            assertThat(evidence.negated()).isTrue();
            assertThat(evidence.evidenceLevel()).isZero();
        });
        assertThat(bundle.positiveEvidenceFor("Docker")).isEmpty();
    }

    @Test
    void projectEvidenceRetainsExactSourceOffsets() {
        String text = "项目经历\n订单系统\n负责使用 Java 实现订单接口并通过 20 项测试。\n教育背景\n本科";
        int start = text.indexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).get().satisfies(evidence -> {
            assertThat(evidence.startOffset()).isEqualTo(start);
            assertThat(evidence.endOffset()).isEqualTo(start + 4);
            assertThat(text.substring(evidence.startOffset(), evidence.endOffset())).isEqualTo("Java");
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.projectId()).isEqualTo("project-1");
            assertThat(evidence.action()).isEqualTo("实现");
            assertThat(evidence.outcome()).contains("20 项测试");
        });
    }

    @Test
    void workExperienceTechnologyListWithoutActionOrOutcomeIsNotProductionLevelFour() {
        String text = "工作经历\n技术栈：Java、Spring Boot、MySQL";
        int start = text.indexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).get().satisfies(evidence -> {
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
            assertThat(evidence.evidenceLevel()).isLessThan(4);
        });
    }

    @Test
    void englishProfessionalExperienceProducesProductionEvidence() {
        String text = "Professional Experience\nDeveloped Java services and improved latency by 40%.";
        int start = text.indexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).get().satisfies(evidence -> {
            assertThat(evidence.section()).isEqualTo("WORK");
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
            assertThat(evidence.evidenceLevel()).isEqualTo(4);
            assertThat(evidence.action()).isNotBlank();
            assertThat(evidence.outcome()).contains("40%");
        });
    }

    @Test
    void commonResumeActionVerbsProduceProductionEvidence() {
        String text = "Professional Experience\n"
                + "Engineered a Python data pipeline for payroll processing.\n"
                + "Owned ETL ingestion and normalization across jurisdictions.\n"
                + "Configured Docker workloads and containerized backend services.";

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(
                skillFact("Python", text.indexOf("Python")),
                skillFact("ETL", text.indexOf("ETL")),
                skillFact("Docker", text.indexOf("Docker"))));

        assertThat(bundle.strongest("Python")).hasValueSatisfying(evidence ->
                assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(3));
        assertThat(bundle.strongest("ETL")).hasValueSatisfying(evidence ->
                assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(3));
        assertThat(bundle.strongest("Docker")).hasValueSatisfying(evidence ->
                assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(3));
    }

    @Test
    void keyProjectsProducesProjectEvidence() {
        String text = "Key Projects\nTinyMathReason\nBuilt a Python training pipeline and passed integration tests.";
        int start = text.indexOf("Python");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Python", start)));

        assertThat(bundle.strongest("Python")).get().satisfies(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.contextType()).isEqualTo("PROJECT");
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(2);
            assertThat(evidence.projectId()).isEqualTo("project-1");
        });
    }

    @Test
    void indentedProjectEvidenceKeepsSourceOffsets() {
        String text = "Projects\r\n\t    Built with Java and passed integration tests.";
        int start = text.indexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).get().satisfies(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.startOffset()).isEqualTo(start);
            assertThat(text.substring(evidence.startOffset(), evidence.endOffset())).isEqualTo("Java");
        });
    }

    @Test
    void deeplyIndentedNegatedEvidenceDoesNotBreakLineOffsets() {
        String text = "Projects\n                    未使用 Java";
        int start = text.indexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).get().satisfies(evidence -> {
            assertThat(evidence.negated()).isTrue();
            assertThat(evidence.evidenceLevel()).isZero();
        });
    }

    @Test
    void shortAsciiSkillsDoNotMatchInsideUnrelatedWords() {
        String text = "Skills\nR\nProfessional Experience\nCreated customer reports and improved reliability by 40%.";
        int start = text.indexOf("\nR\n") + 1;

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("R", start)));

        assertThat(bundle.evidenceFor("R")).singleElement().satisfies(evidence -> {
            assertThat(evidence.section()).isEqualTo("SKILLS");
            assertThat(evidence.evidenceLevel()).isEqualTo(1);
        });
    }

    @Test
    void explicitSkillListIsLevelOneWhileUnsectionedMentionRemainsLevelZero() {
        String text = "Spring Boot\nSkills\nSpring Boot";
        int first = text.indexOf("Spring Boot");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Spring Boot", first)));

        assertThat(bundle.evidenceFor("Spring Boot")).hasSize(2);
        assertThat(bundle.evidenceFor("Spring Boot")).extracting(
                ResumeEvidenceBundle.SkillEvidence::section,
                ResumeEvidenceBundle.SkillEvidence::evidenceLevel
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PROFILE", 0),
                org.assertj.core.groups.Tuple.tuple("SKILLS", 1)
        );
    }

    @Test
    void bilingualSkillsHeadingMarksListedSkillsAsLevelOneEvidence() {
        String text = "专业技能（Skills）\nPython、LangChain、Docker\n项目经历（Projects）\n示例项目";
        ResumeFact python = skillFact("Python", text.indexOf("Python"));

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(python));

        assertThat(bundle.strongest("Python")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("SKILLS");
            assertThat(evidence.evidenceLevel()).isEqualTo(1);
        });
    }

    @Test
    void compatibilityIdeographsMatchCanonicalSkillNames() {
        String text = "项目经历\n使用 ⼤模型 与 AI Agent 构建问答服务。";
        int start = text.indexOf("⼤模型");
        ResumeFact fact = new ResumeFact("SKILL", "大模型", "大模型", -1, -1,
                "⼤模型", 0, "RESUME_TEXT", "PENDING_VERIFICATION", 0.99);

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(fact));

        assertThat(bundle.strongest("大模型")).hasValueSatisfying(evidence -> {
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(2);
            assertThat(evidence.quote()).contains("⼤模型");
            assertThat(evidence.startOffset()).isEqualTo(start);
        });
    }

    @Test
    void commonChineseSkillHeadingsMarkListedSkillsAsLevelOneEvidence() {
        String text = "技能\nPandas、GPT-3\n技术技能\nDocker";
        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(
                skillFact("Pandas", text.indexOf("Pandas")),
                skillFact("GPT-3", text.indexOf("GPT-3")),
                skillFact("Docker", text.indexOf("Docker"))));

        assertThat(bundle.strongest("Pandas")).hasValueSatisfying(evidence ->
                assertThat(evidence.evidenceLevel()).isEqualTo(1));
        assertThat(bundle.strongest("GPT-3")).hasValueSatisfying(evidence ->
                assertThat(evidence.evidenceLevel()).isEqualTo(1));
        assertThat(bundle.strongest("Docker")).hasValueSatisfying(evidence ->
                assertThat(evidence.evidenceLevel()).isEqualTo(1));
    }

    @Test
    void compositeResearchAndProjectHeadingsPreserveExperienceEvidence() {
        String text = "工作与研究经历\n使用 GPT-3 构建聊天机器人并上线。\n"
                + "项目、论文与荣誉\n使用 RAG 实现知识库问答。";
        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(
                skillFact("GPT-3", text.indexOf("GPT-3")),
                skillFact("RAG", text.indexOf("RAG"))));

        assertThat(bundle.strongest("GPT-3")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("WORK");
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(3);
        });
        assertThat(bundle.strongest("RAG")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    void navigationProjectsDoesNotTurnHeroTextIntoProjectEvidence() {
        String text = "Home\nAbout\nProjects\nJohn Doe\nDeveloped React interfaces\nEducation\nBachelor of Technology";
        int start = text.indexOf("React");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("React", start)));

        assertThat(bundle.strongest("React")).get().satisfies(evidence -> {
            assertThat(evidence.section()).isNotEqualTo("PROJECT");
            assertThat(evidence.projectId()).isEmpty();
        });
    }

    @Test
    void topNavigationDoesNotHideLaterProjectEvidence() {
        String text = "Home\nAbout\nContact\nSummary\nBackend engineer\nProjects\nDashboard Project\nBuilt React interfaces";
        int start = text.indexOf("React");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("React", start)));

        assertThat(bundle.strongest("React")).get().satisfies(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.projectId()).isNotEmpty();
        });
    }

    @Test
    void numberedPluralProjectsHeadingStartsProjectEvidence() {
        String text = "Home\nAbout\nProjects\nSummary\n03 - Projects\nAI Chatbot\nBuilt a RAG pipeline using LangChain.";
        int start = text.indexOf("LangChain");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("LangChain", start)));

        assertThat(bundle.strongest("LangChain")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    void numberedUnknownSectionClosesPreviousProjectEvidence() {
        String text = "03 - Projects\nBuilt a RAG chatbot.\n04 - Journey\nCompleted a Java basics course.";
        int start = text.indexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isNotEqualTo("PROJECT");
            assertThat(evidence.projectId()).isEmpty();
        });
    }

    @Test
    void pluralExperiencesHeadingStartsWorkEvidence() {
        String text = "EXPERIENCES\nBuilt and maintained AI agents using Python.";
        int start = text.indexOf("Python");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Python", start)));

        assertThat(bundle.strongest("Python")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("WORK");
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
        });
    }

    @Test
    void inlineProjectTitlesCreateProjectBoundariesInsideWorkExperience() {
        String text = "Professional Experience\nProject Title Dashboard Project\nDeveloped Java APIs and passed integration tests.";
        int start = text.indexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).get().satisfies(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.projectId()).isEqualTo("project-1");
        });
    }

    @Test
    void compactLatexSectionMarkersRestoreWorkAndSkillsContext() {
        String text = "\\ 职业经历 华为 FDE \\ 大模型训练推理：使用 vLLM 完成模型部署并上线生产环境 \\ 技术栈 vLLM Docker";

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(
                skillFact("vLLM", text.indexOf("vLLM")),
                skillFact("Docker", text.lastIndexOf("Docker"))));

        assertThat(bundle.evidenceFor("vLLM")).anySatisfy(evidence -> {
            assertThat(evidence.section()).isEqualTo("WORK");
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(3);
        });
        assertThat(bundle.strongest("Docker")).hasValueSatisfying(evidence ->
                assertThat(evidence.section()).isEqualTo("SKILLS"));
    }

    @Test
    void flattenedMarkdownProjectHeadingCreatesProjectEvidence() {
        String text = "## 技能 Python Docker ## 项目经历 ### 智能问答项目 使用 Python 和 RAG 构建问答服务并通过测试 ## 教育背景 本科";
        int start = text.indexOf("Python", text.indexOf("项目经历"));

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Python", start)));

        assertThat(bundle.strongest("Python")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.projectId()).isNotBlank();
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    void exportedProjectRecordsCarryProjectContextToTechnologyFields() {
        String text = "export const skills = ['Java']; export const projects = [ name: '订单系统', tech: ['Spring Boot'], content: '负责使用 Spring Boot 开发接口并上线。' ];";
        int start = text.lastIndexOf("Spring Boot");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Spring Boot", start)));

        assertThat(bundle.strongest("Spring Boot")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.projectId()).isNotBlank();
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    void compactSkillsArrayDoesNotBecomeProjectEvidence() {
        String text = "export const skills = ['Python', 'RAG']; export const summary = '熟悉 Python 与 RAG';";

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(
                skillFact("Python", text.indexOf("Python")),
                skillFact("RAG", text.indexOf("RAG"))));

        assertThat(bundle.evidenceFor("Python")).allSatisfy(evidence -> {
            assertThat(evidence.section()).isNotEqualTo("PROJECT");
            assertThat(evidence.projectId()).isBlank();
        });
        assertThat(bundle.evidenceFor("RAG")).allSatisfy(evidence ->
                assertThat(evidence.projectId()).isBlank());
    }

    @Test
    void companyRoleDateRecordStartsWorkContextWithoutWorkHeading() {
        String text = "SKILLS\nJava Spring Boot\nSELECTED PROJECTS\nDemo app\nSummary\nSenior engineer\nSyfe | Software Engineer | May 2021 – Jun 2022 | Singapore\nImproved dashboard performance through backend optimizations using Spring Boot.";
        int start = text.lastIndexOf("Spring Boot");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Spring Boot", start)));

        assertThat(bundle.strongest("Spring Boot")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("WORK");
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void selectedWorkHeadingCreatesProductionEvidence() {
        String text = "SELECTED WORK\nBuilt data pipelines with Spring Boot and deployed online.";
        int start = text.indexOf("Spring Boot");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Spring Boot", start)));

        assertThat(bundle.strongest("Spring Boot")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("WORK");
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
            assertThat(evidence.evidenceLevel()).isGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void exportedWorksRecordsCreateWorkEvidenceAndProjectsRemainProjectEvidence() {
        String text = "export const works = [{ content: 'Built data pipelines with Spring Boot and deployed online.' }]; "
                + "export const projects = [{ content: 'Built a recommendation service with Python.' }];";
        int workStart = text.indexOf("Spring Boot");
        int projectStart = text.indexOf("Python");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(
                skillFact("Spring Boot", workStart), skillFact("Python", projectStart)));

        assertThat(bundle.strongest("Spring Boot")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("WORK");
            assertThat(evidence.contextType()).isEqualTo("PRODUCTION");
            assertThat(evidence.projectId()).isBlank();
        });
        assertThat(bundle.strongest("Python")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.projectId()).isNotBlank();
        });
    }

    @Test
    void projectTextExtractionUsesExportedRecordBoundaries() {
        String text = "export const skills = ['Java']; export const works = [{ content: 'Built APIs with Spring Boot and deployed online.' }]; "
                + "export const projects = [{ content: 'Built a RAG service with Python and passed tests.' }];";

        List<String> projectTexts = extractor.extractProjectTexts(text);

        assertThat(projectTexts).anySatisfy(item -> assertThat(item).contains("RAG service"));
        assertThat(projectTexts).anySatisfy(item -> assertThat(item).contains("Spring Boot"));
    }

    @Test
    void profileActionQuoteCannotBePromotedToProductionEvidence() {
        String text = "Summary Built an AI Agent platform and improved response time by 40%.";
        int start = text.indexOf("AI Agent");
        ResumeFact fact = new ResumeFact("SKILL", "AI Agent", "AI Agent", start, start + 8,
                "Built an AI Agent platform and improved response time by 40%.", 0,
                "RESUME_TEXT", "PENDING_VERIFICATION", 0.99);

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(fact));

        assertThat(bundle.strongest("AI Agent")).hasValueSatisfying(evidence -> {
            assertThat(evidence.section()).isEqualTo("OTHER");
            assertThat(evidence.contextType()).isNotEqualTo("PRODUCTION");
            assertThat(evidence.evidenceLevel()).isLessThan(4);
        });
    }

    @Test
    void adjacentEvidenceDoesNotMergeAcrossAnUnboundedSectionLine() {
        String text = "工作经历\n" + "x".repeat(1200) + "\n使用 Java 开发接口并上线生产环境。";
        int start = text.lastIndexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).hasValueSatisfying(evidence ->
                assertThat(evidence.quote().length()).isLessThan(500));
    }

    @Test
    void projectNameWithoutColonCreatesProjectBoundary() {
        String text = "Work Experience\nDashboard Project\nImplemented Java services.";
        int start = text.indexOf("Java");

        ResumeEvidenceBundle bundle = extractor.extract(text, List.of(skillFact("Java", start)));

        assertThat(bundle.strongest("Java")).get().satisfies(evidence -> {
            assertThat(evidence.section()).isEqualTo("PROJECT");
            assertThat(evidence.projectId()).isEqualTo("project-1");
        });
    }

    private ResumeFact skillFact(String skill, int start) {
        return new ResumeFact("SKILL", skill, skill, start, start + skill.length(), skill, 0,
                "RESUME_TEXT", "PENDING_VERIFICATION", 0.99);
    }
}
