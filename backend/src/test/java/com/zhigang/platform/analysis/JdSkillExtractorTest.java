package com.zhigang.platform.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdSkillExtractorTest {
    private final JdSkillExtractor extractor = new JdSkillExtractor(
            new JdSkillPolicy(), new JdCandidateVocabulary());

    @Test
    void prefersReviewedCompositeSpansInUnlabeledOfficialJdFormat() {
        var facts = extractor.extract(201L, """
                AI Agent系统架构师
                任职要求
                熟悉 Python/Go/Java，Dify/vLLM/Mlflow 和 RESTful API 设计。
                """);

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .containsExactly("Python/Go/Java", "Dify/vLLM/Mlflow", "RESTful API 设计");
    }

    @Test
    void keepsAtomicPolicyForLabeledRegressionJdFormat() {
        var facts = extractor.extract(202L, """
                岗位名称：后端工程师
                任职要求：熟悉 Python/Go/Java。
                """);

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .contains("Python", "Java").doesNotContain("Python/Go/Java");
    }

    @Test
    void recognizesReviewedModernAgentAndDataSkillsFromRequirements() {
        var facts = extractor.extract(115L,
                "任职要求：熟悉 AutoGen、Golang、Tool Calling、Function Calling、Scala 和 API设计。");

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .contains("AutoGen", "Golang", "Tool Calling", "Function Calling", "Scala", "API设计");
    }

    @Test
    void inheritsTechnicalContextFromRequirementListHeading() {
        String text = "任职要求：\n专业技能：\n- Go\n- SQL\n";

        var facts = extractor.extract(101L, text);

        assertThat(facts).extracting(fact -> fact.normalizedValue()).contains("Go", "SQL");
    }

    @Test
    void resolvesStableJdAliasesToOneCanonicalName() {
        String text = "任职要求：熟悉 LLM、大模型、NLP、微调、Transformers、Vue3 和 MyBatis-Plus。";

        var facts = extractor.extract(102L, text);

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .contains("大语言模型", "自然语言处理", "模型微调", "Transformer", "Vue 3", "MyBatis Plus")
                .doesNotContain("LLM", "大模型", "NLP", "微调", "Transformers", "MyBatis");
    }

    @Test
    void keepsLongerTechnologyNamesWithoutInferringParents() {
        String text = "任职要求：熟悉 Spring Boot、Spring Cloud Alibaba、MyBatis Plus、SQL优化和 SQL Server。";

        var facts = extractor.extract(103L, text);

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .contains("Spring Boot", "Spring Cloud Alibaba", "MyBatis Plus", "SQL优化", "SQL Server")
                .doesNotContain("Spring", "Spring Cloud", "MyBatis", "SQL");
    }

    @Test
    void doesNotMatchJavaInsideJavaScript() {
        var javascriptOnly = extractor.extract(104L, "任职要求：精通 JavaScript。 ");
        var both = extractor.extract(105L, "任职要求：精通 Java 和 JavaScript。 ");

        assertThat(javascriptOnly).extracting(fact -> fact.normalizedValue())
                .containsExactly("JavaScript");
        assertThat(both).extracting(fact -> fact.normalizedValue())
                .contains("Java", "JavaScript");
    }

    @Test
    void requiresAnUnambiguousEntityInsteadOfIsolatedAi() {
        var technical = extractor.extract(106L, "任职要求：负责人工智能模型和 AI Agent 开发。 ");
        var ambiguous = extractor.extract(107L, "岗位职责：负责 AI 业务支持。 ");

        assertThat(technical).extracting(fact -> fact.normalizedValue()).contains("人工智能", "AI Agent");
        assertThat(ambiguous).extracting(fact -> fact.normalizedValue()).doesNotContain("AI");
    }

    @Test
    void marksNegatedSkillEvidenceInsteadOfPublishingItAsRequired() {
        var facts = extractor.extract(108L, "任职要求：掌握 Python，无需 Java；熟悉 SQL。 ");

        assertThat(facts).filteredOn(fact -> fact.normalizedValue().equals("Java"))
                .singleElement().satisfies(fact -> assertThat(fact.requirementType()).isEqualTo("NEGATED"));
        assertThat(facts).filteredOn(fact -> fact.normalizedValue().equals("SQL"))
                .singleElement().satisfies(fact -> assertThat(fact.requirementType()).isEqualTo("REQUIRED"));
    }

    @Test
    void laterRequiredOccurrenceOverridesEarlierNegation() {
        var facts = extractor.extract(109L, "无需 Java 经验；核心模块必须掌握 Java。 ");

        assertThat(facts).filteredOn(fact -> fact.normalizedValue().equals("Java"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.requirementType()).isEqualTo("REQUIRED");
                    assertThat(fact.startOffset()).isEqualTo("无需 Java 经验；核心模块必须掌握 Java。 ".lastIndexOf("Java"));
                });
    }

    @Test
    void preservesFlexibleWhitespaceEvidenceAndShadowsParentSkills() {
        String text = "\u4efb\u804c\u8981\u6c42\uff1a\u719f\u6089 Spring   Boot \u548c RESTful\tAPI\u3002";

        var facts = extractor.extract(110L, text);

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .contains("Spring Boot", "RESTful API")
                .doesNotContain("Spring", "API");
        assertThat(facts).filteredOn(fact -> fact.normalizedValue().equals("Spring Boot"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.rawValue()).isEqualTo("Spring   Boot");
                    assertThat(text.substring(fact.startOffset(), fact.endOffset())).isEqualTo(fact.rawValue());
                });
        assertThat(facts).filteredOn(fact -> fact.normalizedValue().equals("RESTful API"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.rawValue()).isEqualTo("RESTful\tAPI");
                    assertThat(text.substring(fact.startOffset(), fact.endOffset())).isEqualTo(fact.rawValue());
                });
    }

    @Test
    void choosesEarlierFlexibleWhitespaceMatchBeforeLaterLiteralMatch() {
        String text = "Requirements: proficient in Spring   Boot; Spring Boot not required.";

        var facts = extractor.extract(111L, text);

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .containsExactly("Spring Boot");
        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.rawValue()).isEqualTo("Spring   Boot");
            assertThat(fact.requirementType()).isEqualTo("REQUIRED");
        });
    }

    @Test
    void canonicalizesSpringFrameworkWithoutDroppingSpring() {
        var facts = extractor.extract(112L, "Requirements: proficient in Spring Framework.");

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .containsExactly("Spring");
    }

    @Test
    void recognizesContextualSkillsInEnglishRequirements() {
        var facts = extractor.extract(113L, "Requirements: proficient in SQL and experienced building LLM systems.");

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .contains("SQL", "\u5927\u8bed\u8a00\u6a21\u578b");
    }


    @Test
    void preservesExplicitDistributedArchitectureCanonicalName() {
        var facts = extractor.extract(114L, "\u4efb\u804c\u8981\u6c42\uff1a\u5177\u5907\u5927\u578b\u5206\u5e03\u5f0f\u67b6\u6784\u8bbe\u8ba1\u7ecf\u9a8c\u3002");

        assertThat(facts).extracting(fact -> fact.normalizedValue())
                .contains("\u5206\u5e03\u5f0f\u67b6\u6784")
                .doesNotContain("\u5206\u5e03\u5f0f\u7cfb\u7edf");
    }

    @Test
    void rejectsAliasThatMapsToTwoCanonicalNames() {
        var document = new JdSkillPolicy.Document(
                "test", "", Map.of(
                        "分布式系统", List.of("分布式架构"),
                        "分布式计算", List.of("分布式架构")),
                List.of(), List.of(), List.of());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new JdSkillPolicy(document)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分布式架构");
    }
}
