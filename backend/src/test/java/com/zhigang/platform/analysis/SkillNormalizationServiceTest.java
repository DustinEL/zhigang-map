package com.zhigang.platform.analysis;

import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.repository.DemoPlatformDataProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillNormalizationServiceTest {
    private final SkillNormalizationService service = new SkillNormalizationService(new DemoPlatformDataProvider());

    @Test
    void normalizesAliasesWithoutDuplicatingCanonicalSkill() {
        List<ExtractedFact> facts = service.extract("要求熟悉 SpringBoot、spring-boot、K8s 和 Milvus。");

        assertThat(facts)
                .extracting(ExtractedFact::normalizedValue)
                .contains("Spring Boot", "Kubernetes", "Milvus")
                .doesNotContain("Spring", "向量数据库");
    }

    @Test
    void preservesNegatedRequirementForGateFiltering() {
        List<ExtractedFact> facts = service.extract("岗位不要求 Kubernetes，但必须掌握 Java。");

        assertThat(facts)
                .filteredOn(fact -> fact.normalizedValue().equals("Kubernetes"))
                .singleElement()
                .extracting(ExtractedFact::requirementType)
                .isEqualTo("NEGATED");
        assertThat(facts)
                .filteredOn(fact -> fact.normalizedValue().equals("Java"))
                .singleElement()
                .extracting(ExtractedFact::requirementType)
                .isEqualTo("REQUIRED");
    }

    @Test
    void doesNotExtractJavaFromJavascript() {
        List<ExtractedFact> facts = service.extract("项目：使用 JavaScript 开发管理平台。");

        assertThat(facts)
                .extracting(ExtractedFact::normalizedValue)
                .doesNotContain("Java");
    }

    @Test
    void extractsCommonResumeSkillsAcrossNonAiDomains() {
        List<ExtractedFact> facts = service.extract(
                "Vue 3、Nuxt、TypeScript、CSS、HTML、JavaScript、Git；MQTT、STM32、嵌入式、ESP32；iOS、Swift、Objective-C、WebRTC、FFmpeg、音视频");

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("Vue", "Nuxt", "TypeScript", "CSS", "HTML", "JavaScript", "Git",
                        "MQTT", "STM32", "嵌入式", "ESP32", "iOS", "Swift", "Objective-C", "WebRTC", "FFmpeg", "音视频");
    }
    @Test
    void overlappingAliasesDoNotSuppressDistinctCanonicalSkills() {
        List<ExtractedFact> facts = service.extract("使用 AI Agent、Agent、C++ 和 C语言开发系统");

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("AI Agent", "C++", "C");
    }

    @Test
    void preservesLiteralSkillWhenDatabaseAliasMapsItToBroaderCategory() {
        List<ExtractedFact> facts = service.extract("负责 Kafka 集群与消息队列平台建设");

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("Kafka", "消息队列");
    }

    @Test
    void extractsCommonDataOrchestrationAndContainerizationEvidence() {
        List<ExtractedFact> facts = service.extract(
                "使用 Airflow、dbt、Dagster 和 PySpark 构建数据管道，并容器化 FastAPI 服务。");

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("Airflow", "dbt", "Dagster", "PySpark", "Docker");
    }

    @Test
    void normalizesHighPrecisionAgentAndDataPipelinePhrases() {
        List<ExtractedFact> facts = service.extract(
                "Engineered AI agents with multi-agent orchestration and owned data ingestion pipelines.");

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("AI Agent", "数据管道");
    }

    @Test
    void matchesCompatibilityIdeographsInResumeText() {
        List<ExtractedFact> facts = service.extract("项目经历：使用⼤模型和⼈工智能构建问答服务");

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("LLM", "人工智能");
    }

    @Test
    void prefersLaterActionBackedAliasOverEarlierSkillListMention() {
        String text = "技术技能\nDocker、Linux\n工作经历\n容器化 FastAPI 服务并上线生产环境。";

        ExtractedFact docker = service.extract(text).stream()
                .filter(fact -> fact.normalizedValue().equals("Docker"))
                .findFirst()
                .orElseThrow();

        assertThat(docker.rawValue()).isEqualTo("容器化");
        assertThat(docker.startOffset()).isEqualTo(text.indexOf("容器化"));
    }

    @Test
    void normalizesReviewedDataProjectEvidence() {
        List<ExtractedFact> facts = service.extract(
                "项目经历\n创建 2000 条训练数据集，并使用 Python 读取和分析 CSV 航班数据。");

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("数据集构建", "CSV", "Python");
    }

    @Test
    void doesNotExpandKafkaToMessageQueueWithoutLiteralEvidence() {
        assertThat(service.extract("任职要求：熟悉 Kafka 集群运维。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Kafka")
                .doesNotContain("消息队列");
    }

    @Test
    void extractsHighPrecisionJdSkillsFromLiteralEvidence() {
        List<ExtractedFact> facts = service.extract(
                "任职要求：熟悉 SQL、模型微调、分布式系统、自然语言处理、强化学习、Transformer、数据仓库和推荐算法。");

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("SQL", "微调", "分布式系统", "NLP", "强化学习",
                        "Transformer", "数据仓库", "推荐算法");
    }

    @Test
    void normalizesStableJdConceptAliasesWithoutDatabaseDefinitions() {
        assertThat(service.extract("任职要求：掌握微调、NLP、RLHF、推荐系统和分布式架构。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("微调", "NLP", "强化学习", "推荐算法", "分布式系统");
    }

    @Test
    void appliesReviewedGranularityPolicyToAliases() {
        assertThat(service.extract("任职要求：掌握 LLM 和大模型。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("LLM")
                .doesNotContain("大语言模型", "大模型");

        assertThat(service.extract("任职要求：熟悉 Spring Cloud。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Spring Cloud")
                .doesNotContain("Spring");

        assertThat(service.extract("任职要求：熟悉 Spring Boot 和 Spring MVC。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Spring Boot", "Spring MVC")
                .doesNotContain("Spring");

        assertThat(service.extract("任职要求：熟悉 spring-boot 和 SQL Server。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Spring Boot", "SQL Server")
                .doesNotContain("Spring", "SQL");

        assertThat(service.extract("任职要求：熟悉 Spring Cloud Alibaba 和 MyBatis Plus。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Spring Cloud Alibaba", "MyBatis Plus")
                .doesNotContain("Spring Cloud", "Spring", "MyBatis");

        assertThat(service.extract("任职要求：掌握 SQL优化 和 SQL调优。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("SQL优化", "SQL调优")
                .doesNotContain("SQL");
    }

    @Test
    void gatesAmbiguousShortSkillsByTechnicalContext() {
        assertThat(service.extract("我们用 AI 改善业务，让 Agent 服务客户，团队成员 go further。"))
                .extracting(ExtractedFact::normalizedValue)
                .doesNotContain("AI", "Agent", "Go");

        assertThat(service.extract("任职要求：熟悉 Go 开发，掌握 AI Agent 框架。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Go", "AI Agent");
    }

    @Test
    void continuesAfterRejectedShortWordOccurrence() {
        assertThat(service.extract("团队口号是 go further；任职要求：熟悉 Go 服务开发。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Go");
    }

    @Test
    void acceptsGoWhenServiceDevelopmentProvidesTechnicalContext() {
        assertThat(service.extract("任职要求：熟悉 SQL 查询优化、Kafka 消息处理和 Go 服务开发。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Go");
    }

    @Test
    void doesNotTreatSoftwareEngineeringMajorAsSoftwareDevelopmentSkill() {
        assertThat(service.extract("计算机、软件工程、人工智能等相关专业本科及以上学历。"))
                .extracting(ExtractedFact::normalizedValue)
                .doesNotContain("软件开发");

        assertThat(service.extract("具备三年以上软件开发经验。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("软件开发");
    }

    @Test
    void canonicalizesStandaloneSkillAndRejectsResponsibilitySentence() {
        assertThat(service.canonicalizeSkillName("K8s")).contains("Kubernetes");
        assertThat(service.canonicalizeSkillName("Build and optimize scalable inference services for production")).isEmpty();
    }

    @Test
    void extractsHumanReviewedResumeLiteralsAcrossDomains() {
        List<ExtractedFact> facts = service.extract("""
                专业技能：React、Next.js、REST API、AWS、Firebase、Figma、GraphQL、WebSocket；
                Power BI、DAX、BPMN、Scrum；Rust、Boost.Asio、Active Directory；
                Flutter、Dart、Google Maps API、Hugging Face、BM25、MCP、LLM-as-a-Judge。
                """);

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("React", "Next.js", "REST API", "AWS", "Firebase", "Figma", "GraphQL", "WebSocket",
                        "Power BI", "DAX", "BPMN", "Scrum", "Rust", "Boost.Asio", "Active Directory",
                        "Flutter", "Dart", "Google Maps API", "Hugging Face", "BM25", "MCP", "LLM-as-a-Judge");
    }

    @Test
    void appliesFrozenResumeCanonicalNames() {
        assertThat(service.extract("Vue 3、Nuxt 4、NaiveUI、WiFi、RESTful API、NLP、模型微调"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Vue", "Nuxt", "Naive UI", "Wi-Fi", "REST API", "NLP", "微调")
                .doesNotContain("Vue 3", "Nuxt 4", "NaiveUI", "WiFi", "RESTful API", "自然语言处理", "模型微调");
    }

    @Test
    void ignoresGithubInsideSourceUrlAndLongerGithubActionsSkill() {
        assertThat(service.extract("原始来源：https://github.com/example/resume"))
                .extracting(ExtractedFact::normalizedValue)
                .doesNotContain("GitHub");
        assertThat(service.extract("专业技能：GitHub Actions"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("GitHub Actions")
                .doesNotContain("GitHub");
        assertThat(service.extract("专业技能：Git/GitHub"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("GitHub");
    }

    @Test
    void normalizesResponsiveLayoutWithPdfWhitespace() {
        assertThat(service.extract("使用 Vue 组件化、CSS3 响应式布局、Ajax 和 Git 协作"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("响应式设计");
    }

    @Test
    void suppressesShortSkillInsideLongerSkillButFindsLaterStandaloneOccurrence() {
        assertThat(service.extract("C++、C#、Objective-C、TCP/IP、REST API、LLM API、GitHub Actions"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("C++", "C#", "Objective-C", "TCP/IP", "REST API", "LLM API", "GitHub Actions")
                .doesNotContain("C", "TCP", "API", "LLM", "GitHub");

        assertThat(service.extract("使用 REST API 集成，并负责 API 设计"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("REST API", "API");
    }

    @Test
    void normalizesPdfLineBreaksInsideCompoundSkills() {
        assertThat(service.extract("CSS3 响应式布局；REST\nAPI；OpenAI\nAPI"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("CSS", "响应式设计", "REST API", "OpenAI API")
                .doesNotContain("CSS3", "API");
    }

    @Test
    void rejectsReviewedModelSkillWhenRawEvidenceDoesNotMatch() {
        assertThat(service.isReviewedSkill("系统", "Java")).isFalse();
        assertThat(service.isReviewedSkill("K8s", "Kubernetes")).isTrue();
    }

    @Test
    void extractsFrozenMarketResumeSkillsAndCanonicalizesTheirSurfaceForms() {
        assertThat(service.extract("""
                Technical Skills
                Django, Kotlin, VS Code, Microservices, Bitbucket, Integration Testing,
                .NET MVC, EC2, Beautiful Soup, Xilinx Vivado Design Suite, SQL
                """))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Django", "Kotlin", "VS Code", "微服务", "Bitbucket",
                        "Integration Testing", "ASP.NET MVC", "Amazon EC2", "Beautiful Soup",
                        "Xilinx Vivado", "SQL");
    }

    @Test
    void doesNotPromoteGenericNarrativeWordsToSpecificSkills() {
        assertThat(service.extract("""
                Improved system reliability through cross-functional integration.
                Built analytics dashboards with zero regressions.
                """))
                .extracting(ExtractedFact::normalizedValue)
                .doesNotContain("System Testing", "Functional Testing", "Integration Testing",
                        "Google Analytics", "ZeRO");
    }

    @Test
    void suppressesShortCloudSkillsWhenPdfBreaksCompoundNamesAcrossLines() {
        assertThat(service.extract("Azure\nOpenAI; Google Cloud\nTPU; Transformers"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Azure OpenAI", "Google Cloud TPU", "Transformer")
                .doesNotContain("OpenAI", "Google Cloud", "Transformers");
    }

    @Test
    void extractsExplicitPlatformAndRoboticsTermsFromResumeSections() {
        assertThat(service.extract("""
                技术技能：Protocol Buffers、InfluxDB、Telegraf、Redfish、Podman、Gazebo、PX4、RANSAC、ICP、PCA、KNN、SVM、Naive Bayes、Levenshtein、Rocky Linux、Kickstart、cloud-init、WSL2。
                DevOps：Kind、Keycloak、i18next、npm、Bitbucket Pipelines、Frappe、ERPNext、Django REST Framework、SolidWorks。
                机器学习：RF-DETR、SAM、RBF、BentoML、OWL、RDF、VLA、PV-RCNN、DGCNN、CenterPoint、BEV、AB3DMOT、DoA、TensorRT、ONNX、MuJoCo、SciPy、FMCW Radar、IMU、LiDAR、RealSense、Zivid、SORT、IMM、Kalman、PP-LiteSeg、U-Net、YOLO、INT8 量化、知识蒸馏、剪枝、多目标跟踪、多视图几何、相机标定、视觉 SLAM、语义分割、超分辨率、概率建模、3D 感知。
                """))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Protocol Buffers", "InfluxDB", "Telegraf", "Redfish", "Podman", "Gazebo", "PX4",
                        "RANSAC", "ICP", "PCA", "KNN", "SVM", "Naive Bayes", "Levenshtein", "Rocky Linux",
                        "Kickstart", "cloud-init", "WSL2", "Kind", "Keycloak", "i18next", "npm",
                        "Bitbucket Pipelines", "Frappe", "ERPNext", "Django REST Framework", "SolidWorks",
                        "RF-DETR", "SAM", "RBF", "BentoML", "OWL", "RDF", "VLA", "PV-RCNN", "DGCNN",
                        "CenterPoint", "BEV", "AB3DMOT", "DoA", "TensorRT", "ONNX", "MuJoCo", "SciPy",
                        "FMCW Radar", "IMU", "LiDAR", "RealSense", "Zivid", "SORT", "IMM", "Kalman",
                        "PP-LiteSeg", "U-Net", "YOLO", "INT8 量化", "知识蒸馏", "剪枝", "多目标跟踪",
                        "多视图几何", "相机标定", "视觉 SLAM", "语义分割", "超分辨率", "概率建模", "3D 感知");
    }

    @Test
    void extractsCompoundCAndGoFromProgrammingLanguagesWithoutTreatingContactGithubAsSkill() {
        assertThat(service.extract("技术技能 编程语言 | Java、C/C++、Go、Python\nGitHub：github.com/example"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Java", "C", "C++", "Go", "Python")
                .doesNotContain("GitHub");
    }

    @Test
    void ignoresGithubAnywhereOnContactOrSourceLines() {
        assertThat(service.extract("邮箱：person@example.com | GitHub：github.com/example\n技术技能：Git、Java"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Git", "Java")
                .doesNotContain("GitHub");

        assertThat(service.extract("原始公开资料：.resume_sources/extracted/github_resume/main\n技术技能：Python"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Python")
                .doesNotContain("GitHub");

        assertThat(service.extract("邮箱：person@example.com | GitHub：\ngithub.com/example\n技术技能：GitHub Actions"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("GitHub Actions")
                .doesNotContain("GitHub");
    }

    @Test
    void normalizesPdfLineBreakAfterCompoundHyphen() {
        assertThat(service.extract("PP-\nLiteSeg/U-Net 车道分割"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("PP-LiteSeg", "U-Net");
    }

    @Test
    void extractsCommonGptModelNamesAsExplicitLlmEvidence() {
        assertThat(service.extract("使用 GPT-2 构建聊天机器人 Chatbot，并通过 GPT-3 与 GPT-4 API 生成内容"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("GPT-2", "GPT-3", "GPT-4", "聊天机器人");
    }

    @Test
    void extractsProductAndAppliedMachineLearningSkillsFromReviewedResumeEvidence() {
        assertThat(service.extract("""
                能够从用户反馈和业务数据中完成需求分析、PRD、原型与交互，并使用机器学习和智能语音技术支持产品判断。
                技术能力：LightGBM、SHAP、MFCC、微信小程序、大模型 API。
                """))
                .extracting(ExtractedFact::normalizedValue)
                .contains("需求分析", "PRD", "原型设计", "交互设计", "LightGBM", "SHAP", "MFCC",
                        "智能语音", "微信小程序", "LLM API")
                .doesNotContain("LLM", "API");
    }

    @Test
    void extractsDataAnalysisSkillsAndCanonicalizesReviewedAliases() {
        assertThat(service.extract("""
                具备 SPSS、数据建模及数据库测试环境实践，关注数据校验、数据质量与数据清洗。
                使用 Python 和 Pandas 完成探索分析与自动化，并通过星型模型、透视分析和情景分析支持业务判断。
                """))
                .extracting(ExtractedFact::normalizedValue)
                .contains("SPSS", "数据建模", "数据库测试", "数据校验", "数据质量", "数据清洗",
                        "探索性数据分析", "自动化", "星型模型", "透视分析", "情景分析")
                .doesNotContain("探索分析");
    }

    @Test
    void extractsSoftwareTestingSkillsWithoutLeakingPartsOfCompoundNames() {
        assertThat(service.extract("""
                拥有软件测试经验，覆盖功能、兼容性、接口、性能、自动化和回归测试，负责 App（Android/iOS）、Web、H5 全端测试并开发 UI 自动化脚本。
                工具：Allure、MeterSphere、DBeaver、CRM、Charles、Fiddler、Navicat、Requests、JMeter；指标：TPS、P50/P95/P99；报告：JUnit XML。
                使用数据驱动、等价类、边界值、场景法和错误推测法设计用例，并覆盖弱网场景。
                """))
                .extracting(ExtractedFact::normalizedValue)
                .contains("功能测试", "Compatibility Testing", "API Testing", "Performance Testing", "自动化测试",
                        "UI Testing", "Regression Testing", "App Testing", "Web Testing", "H5 Testing",
                        "Android Testing", "iOS Testing", "Allure", "MeterSphere", "DBeaver", "CRM", "Charles",
                        "Fiddler", "Navicat", "Requests", "TPS", "P50/P95/P99", "JUnit XML", "数据驱动测试",
                        "弱网测试", "等价类", "BVA", "场景法", "错误推测法")
                .doesNotContain("Android", "iOS", "JUnit", "XML", "性能测试", "边界值");
    }

    @Test
    void doesNotLeakTestingContextAcrossLinesToStandalonePlatformSkills() {
        String text = "移动开发：Android、iOS、App、Web。\n与软件测试团队协作";

        assertThat(service.extract(text))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Android", "iOS")
                .doesNotContain("Android Testing", "iOS Testing", "App Testing", "Web Testing");
    }

    @Test
    void doesNotLeakTestingContextAcrossClausesOnTheSameLine() {
        String text = "移动开发：Android、iOS、App、Web；与软件测试团队协作";

        assertThat(service.extract(text))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Android", "iOS")
                .doesNotContain("Android Testing", "iOS Testing", "App Testing", "Web Testing");
    }

    @Test
    void recognizesExplicitTestingEvidenceWithinTheSameClause() {
        String text = """
                功能与兼容性测试 | 等价类、边界值、场景法、错误推测法；iOS/Android 多机型、多系统与弱网场景 接口与性能测试 | Postman
                使用 Python 开发接口和 UI 自动化脚本，搭建版本回归体系。
                """;

        assertThat(service.extract(text))
                .extracting(ExtractedFact::normalizedValue)
                .contains("功能测试", "Android Testing", "iOS Testing", "弱网测试", "UI Testing")
                .doesNotContain("Android", "iOS");
    }

    @Test
    void ignoresDocumentProcessingCompatibilityDisclaimer() {
        assertThat(service.extract("本文件仅用于网站内部解析、排版和兼容性测试。"))
                .extracting(ExtractedFact::normalizedValue)
                .doesNotContain("Compatibility Testing");

        assertThat(service.extract("负责文件解析、排版和兼容性测试，并完成验收。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("Compatibility Testing");
    }

    @Test
    void preservesActualWhitespaceMatchSpanForJunitXml() {
        String text = "报告：JUnit\r\nXML。";
        int start = text.indexOf("JUnit");

        assertThat(service.extract(text))
                .filteredOn(fact -> fact.normalizedValue().equals("JUnit XML"))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.rawValue()).isEqualTo("JUnit\r\nXML");
                    assertThat(fact.startOffset()).isEqualTo(start);
                    assertThat(fact.endOffset()).isEqualTo(start + "JUnit\r\nXML".length());
                });
    }

    @Test
    void preservesActualWhitespaceMatchSpanForLargeModelApi() {
        String text = "技术能力：大模型\nAPI。";
        int start = text.indexOf("大模型");

        assertThat(service.extract(text))
                .filteredOn(fact -> fact.normalizedValue().equals("LLM API"))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.rawValue()).isEqualTo("大模型\nAPI");
                    assertThat(fact.startOffset()).isEqualTo(start);
                    assertThat(fact.endOffset()).isEqualTo(start + "大模型\nAPI".length());
                });
    }

    @Test
    void extractsReviewedAiApplicationAndDataEngineeringContextAliases() {
        List<ExtractedFact> facts = service.extract(
                "项目经历：建设 AI大脑 的模型统一调用接口，负责工具编排，生成智能教案和自动报告；\n"
                        + "工作经历：负责数据中台、数据采集、增量同步和数据质量处理。"
        );

        assertThat(facts).extracting(ExtractedFact::normalizedValue)
                .contains("LLM", "AI Agent", "数据管道", "数据清洗", "数据采集");
    }

    @Test
    void distinguishesExplicitModelServingEvidenceFromGenericLlmUse() {
        List<ExtractedFact> serving = service.extract(
                "负责大模型私有化部署，并封装模型统一调用接口。"
        );
        List<ExtractedFact> generic = service.extract(
                "使用 LLM 生成智能教案，并完成模型微调。"
        );

        assertThat(serving).extracting(ExtractedFact::normalizedValue)
                .contains("模型服务")
                .doesNotContain("AI Agent");
        assertThat(generic).extracting(ExtractedFact::normalizedValue)
                .doesNotContain("模型服务");
    }

    @Test
    void recognizesModelServingWhenModelAndUnifiedInterfaceAreSeparatedByServiceVerb() {
        assertThat(service.extract("封装多种算法模型并提供统一调用接口。"))
                .extracting(ExtractedFact::normalizedValue)
                .contains("模型服务");
    }

}
