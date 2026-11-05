USE zhigang_map;

INSERT IGNORE INTO data_source (id, source_code, source_name, source_type, license_status, connector_type) VALUES
  (1, 'RECRUITMENT_AUTHORIZED', '授权招聘快照', 'RECRUITMENT_JD', 'AUTHORIZED', 'FILE'),
  (2, 'COURSE_OPEN', '高校培养方案', 'COURSE', 'OPEN', 'FILE'),
  (3, 'POLICY_OPEN', '人工智能产业报告摘要', 'POLICY', 'OPEN', 'FILE');

INSERT IGNORE INTO dataset_snapshot (id, snapshot_code, snapshot_name, status, policy_version, raw_count, effective_count, quality_score, publishable, frozen_at) VALUES
  (1, 'demo-snapshot-2026-07', 'AI 应用研发岗位族演示快照', 'FROZEN', 'v1.0.0', 10, 10, 0.94, 1, '2026-07-10 16:00:00');

INSERT IGNORE INTO domain_pack (id, pack_code, pack_name, version, status, description) VALUES
  (1, 'AI_APP_RND', 'AI 应用研发领域包', '1.0.0', 'PUBLISHED', '覆盖 RAG、AI Agent、Java 云原生与 AI 集成岗位');

INSERT IGNORE INTO job_family (id, domain_pack_id, family_code, family_name, description) VALUES
  (1, 1, 'AI_APPLICATION', 'AI 应用研发', '面向大模型、RAG 和智能体应用落地'),
  (2, 1, 'BACKEND_CLOUD', '后端与云原生', 'Java 企业后端、分布式系统与 AI 集成'),
  (3, 1, 'AI_PLATFORM', 'AI 平台工程', '模型服务、平台和 LLMOps'),
  (4, 1, 'DATA_KNOWLEDGE', '数据与知识工程', 'AI 数据、知识和检索基础设施'),
  (5, 1, 'QUALITY_SECURITY', 'AI 质量与安全', '大模型评测与应用安全测试');

INSERT IGNORE INTO job_level (id, level_code, level_name, sort_order) VALUES
  (1, 'INTERN_JUNIOR', '初中级', 1),
  (2, 'MIDDLE', '中级', 2),
  (3, 'SENIOR', '高级', 3);

INSERT IGNORE INTO capability (id, capability_code, capability_name, sort_order) VALUES
  (1, 'PROGRAMMING', '编程语言', 10),
  (2, 'BACKEND', '后端框架', 20),
  (3, 'DATABASE', '数据库', 30),
  (4, 'ENGINEERING', '工程化', 40),
  (5, 'CLOUD_NATIVE', '云原生', 50),
  (6, 'AI_APPLICATION', 'AI应用', 60),
  (7, 'AI_INFRA', 'AI数据基础设施', 70),
  (8, 'AI_ENGINEERING', 'AI工程化', 80),
  (9, 'LEGACY', '传统技术', 90);

INSERT IGNORE INTO skill (id, capability_id, skill_code, skill_name, status) VALUES
  (1, 1, 'JAVA', 'Java', 'PUBLISHED'),
  (2, 1, 'PYTHON', 'Python', 'PUBLISHED'),
  (3, 2, 'SPRING_BOOT', 'Spring Boot', 'PUBLISHED'),
  (4, 2, 'SPRING_CLOUD', 'Spring Cloud', 'PUBLISHED'),
  (5, 3, 'MYSQL', 'MySQL', 'PUBLISHED'),
  (6, 3, 'REDIS', 'Redis', 'PUBLISHED'),
  (7, 4, 'LINUX', 'Linux', 'PUBLISHED'),
  (8, 5, 'DOCKER', 'Docker', 'PUBLISHED'),
  (9, 5, 'KUBERNETES', 'Kubernetes', 'PUBLISHED'),
  (10, 6, 'LLM', '大语言模型', 'PUBLISHED'),
  (11, 6, 'PROMPT_ENGINEERING', 'Prompt Engineering', 'PUBLISHED'),
  (12, 6, 'EMBEDDING', 'Embedding', 'PUBLISHED'),
  (13, 7, 'VECTOR_DATABASE', '向量数据库', 'PUBLISHED'),
  (14, 6, 'RAG', 'RAG', 'PUBLISHED'),
  (15, 6, 'LANGCHAIN', 'LangChain', 'PUBLISHED'),
  (16, 8, 'MODEL_EVALUATION', '模型评测', 'PUBLISHED'),
  (17, 6, 'AI_AGENT', 'AI Agent', 'PUBLISHED'),
  (18, 9, 'JSP_SERVLET', 'JSP/Servlet', 'PUBLISHED');

INSERT IGNORE INTO skill_alias (skill_id, alias_name, match_type, confidence) VALUES
  (3, 'SpringBoot', 'EXACT', 1), (3, 'spring-boot', 'EXACT', 1),
  (9, 'K8s', 'EXACT', 1), (9, '容器编排', 'SEMANTIC', 0.96),
  (10, 'LLM', 'EXACT', 1), (10, '大模型', 'EXACT', 1),
  (13, 'Milvus', 'PRODUCT_OF', 0.95), (13, 'Chroma', 'PRODUCT_OF', 0.95),
  (14, '检索增强生成', 'EXACT', 1), (14, '知识库问答', 'SEMANTIC', 0.9),
  (17, '智能体', 'EXACT', 1), (17, 'Agent', 'EXACT', 1);

INSERT IGNORE INTO skill_prerequisite (skill_id, prerequisite_skill_id) VALUES
  (3, 1), (4, 3), (8, 7), (9, 8), (11, 10), (12, 2), (13, 12), (14, 10), (14, 12), (14, 13), (15, 2), (15, 10), (16, 14), (17, 10), (17, 11);

INSERT IGNORE INTO job_role (id, family_id, role_code, role_name, is_emerging, definition, salary_range, status) VALUES
  (1, 1, 'RAG_APP_ENGINEER', 'RAG 应用开发工程师', 1, '面向企业知识库、智能客服和研发助手，负责检索增强生成链路、证据引用、评测与工程治理。', '18k-35k', 'PUBLISHED'),
  (2, 2, 'JAVA_ENGINEER', 'Java 开发工程师', 0, '负责企业级后端服务、数据访问和分布式系统开发，并逐步承担云原生部署与 AI 能力集成。', '12k-26k', 'PUBLISHED'),
  (3, 1, 'AI_AGENT_ENGINEER', 'AI Agent 开发工程师', 1, '负责智能体任务规划、工具调用、记忆检索、流程编排和效果评测。', '22k-40k', 'PUBLISHED'),
  (4, 1, 'AI_APP_BACKEND_ENGINEER', 'AI 应用后端工程师', 0, '负责将大模型、RAG 和智能体能力接入稳定的后端服务。', '15k-30k', 'PUBLISHED'),
  (5, 3, 'LLMOPS_ENGINEER', 'LLMOps / 模型服务工程师', 0, '负责大模型服务部署、版本管理、监控告警和稳定性治理。', '20k-38k', 'PUBLISHED'),
  (6, 5, 'LLM_EVALUATION_ENGINEER', '大模型评测工程师', 1, '负责构建评测集、定义指标、执行自动化评测并分析错误。', '16k-32k', 'PUBLISHED'),
  (7, 1, 'LLM_APP_ENGINEER', '大模型应用开发工程师', 0, '面向业务场景开发大模型应用，目录画像待真实数据补充。', '样本不足', 'PUBLISHED'),
  (8, 3, 'AI_PLATFORM_ENGINEER', 'AI 平台开发工程师', 0, '建设模型接入、应用编排和运营治理平台，目录画像待补充。', '样本不足', 'PUBLISHED'),
  (9, 4, 'AI_DATA_ENGINEER', 'AI 数据工程师', 0, '建设 AI 应用所需的数据处理和质量管道，目录画像待补充。', '样本不足', 'PUBLISHED'),
  (10, 4, 'KNOWLEDGE_ENGINEER', '知识工程师', 0, '负责知识建模、治理和知识应用，目录画像待补充。', '样本不足', 'PUBLISHED'),
  (11, 4, 'VECTOR_RETRIEVAL_ENGINEER', '向量检索工程师', 0, '负责向量索引、召回和检索性能优化，目录画像待补充。', '样本不足', 'PUBLISHED'),
  (12, 5, 'AI_SECURITY_TEST_ENGINEER', 'AI 安全测试工程师', 0, '负责提示注入、数据泄露和模型应用安全测试，目录画像待补充。', '样本不足', 'PUBLISHED');

INSERT IGNORE INTO responsibility (id, responsibility_code, responsibility_name) VALUES
  (1, 'RAG_KB', '构建企业知识库问答系统'),
  (2, 'RAG_PIPELINE', '设计文档切分、检索、重排和生成链路'),
  (3, 'RAG_EVAL', '建立引用与幻觉评测'),
  (4, 'JAVA_SERVICE', '开发后端接口与业务服务'),
  (5, 'JAVA_DATA', '设计数据库和缓存方案'),
  (6, 'JAVA_AI', '接入云原生和 AI 应用能力');

INSERT IGNORE INTO industry_scenario (id, scenario_code, scenario_name) VALUES
  (1, 'ENTERPRISE_KB', '企业知识库'),
  (2, 'SMART_SERVICE', '智能客服'),
  (3, 'RND_ASSISTANT', '研发助手'),
  (4, 'ENTERPRISE_SOFTWARE', '企业信息化'),
  (5, 'FINTECH', '金融科技');

INSERT IGNORE INTO graph_release (id, release_version, snapshot_id, policy_version, status, publisher_id, release_note, published_at) VALUES
  (1, '2026.07', 1, 'v1.0.0', 'PUBLISHED', 1, '岗位能力图谱体验资料首个发布版本', '2026-07-10 16:00:00');

INSERT IGNORE INTO release_job_skill (release_id, job_role_id, job_level_id, skill_id, requirement_type, demand_rate, evidence_count, independent_company_count, trend_type, effective_from) VALUES
  (1, 1, 1, 2, 'REQUIRED', 0.82, 24, 10, 'STABLE', '2026-07-10'),
  (1, 1, 1, 10, 'REQUIRED', 0.91, 30, 10, 'STABLE', '2026-07-10'),
  (1, 1, 1, 11, 'REQUIRED', 0.72, 19, 9, 'STABLE', '2026-07-10'),
  (1, 1, 1, 12, 'REQUIRED', 0.76, 21, 9, 'INCREASED', '2026-07-10'),
  (1, 1, 1, 13, 'REQUIRED', 0.79, 22, 9, 'INCREASED', '2026-07-10'),
  (1, 1, 1, 14, 'REQUIRED', 0.88, 28, 10, 'INCREASED', '2026-07-10'),
  (1, 1, 1, 15, 'BONUS', 0.63, 16, 8, 'STABLE', '2026-07-10'),
  (1, 1, 1, 16, 'BONUS', 0.52, 13, 7, 'ADDED', '2026-07-10'),
  (1, 2, 1, 1, 'REQUIRED', 0.96, 36, 10, 'STABLE', '2026-07-10'),
  (1, 2, 1, 3, 'REQUIRED', 0.91, 34, 10, 'STABLE', '2026-07-10'),
  (1, 2, 1, 5, 'REQUIRED', 0.86, 31, 10, 'STABLE', '2026-07-10'),
  (1, 2, 1, 6, 'REQUIRED', 0.73, 26, 9, 'STABLE', '2026-07-10'),
  (1, 2, 1, 7, 'REQUIRED', 0.64, 22, 8, 'STABLE', '2026-07-10'),
  (1, 2, 1, 8, 'BONUS', 0.68, 24, 9, 'INCREASED', '2026-07-10'),
  (1, 2, 1, 9, 'BONUS', 0.48, 17, 7, 'INCREASED', '2026-07-10'),
  (1, 2, 1, 14, 'BONUS', 0.21, 8, 5, 'ADDED', '2026-07-10'),
  (1, 2, 1, 10, 'BONUS', 0.26, 9, 5, 'ADDED', '2026-07-10'),
  (1, 2, 1, 18, 'BONUS', 0.09, 4, 3, 'WEAKENED', '2026-07-10'),
  (1, 3, 2, 2, 'REQUIRED', 0.85, 16, 6, 'STABLE', '2026-07-10'),
  (1, 3, 2, 10, 'REQUIRED', 0.92, 17, 6, 'STABLE', '2026-07-10'),
  (1, 3, 2, 17, 'REQUIRED', 0.89, 16, 6, 'INCREASED', '2026-07-10'),
  (1, 3, 2, 11, 'REQUIRED', 0.75, 13, 6, 'STABLE', '2026-07-10'),
  (1, 4, 1, 1, 'REQUIRED', 0.78, 14, 7, 'STABLE', '2026-07-10'),
  (1, 4, 1, 3, 'REQUIRED', 0.74, 13, 7, 'STABLE', '2026-07-10'),
  (1, 4, 1, 10, 'REQUIRED', 0.71, 12, 6, 'INCREASED', '2026-07-10'),
  (1, 4, 1, 14, 'REQUIRED', 0.62, 10, 6, 'INCREASED', '2026-07-10'),
  (1, 5, 2, 2, 'REQUIRED', 0.82, 15, 8, 'STABLE', '2026-07-10'),
  (1, 5, 2, 7, 'REQUIRED', 0.86, 16, 8, 'STABLE', '2026-07-10'),
  (1, 5, 2, 8, 'REQUIRED', 0.79, 14, 7, 'STABLE', '2026-07-10'),
  (1, 5, 2, 9, 'REQUIRED', 0.72, 13, 7, 'INCREASED', '2026-07-10'),
  (1, 5, 2, 10, 'REQUIRED', 0.76, 14, 7, 'INCREASED', '2026-07-10'),
  (1, 6, 1, 2, 'REQUIRED', 0.81, 13, 7, 'STABLE', '2026-07-10'),
  (1, 6, 1, 10, 'REQUIRED', 0.88, 15, 8, 'STABLE', '2026-07-10'),
  (1, 6, 1, 16, 'REQUIRED', 0.91, 16, 8, 'INCREASED', '2026-07-10'),
  (1, 6, 1, 14, 'REQUIRED', 0.58, 9, 5, 'INCREASED', '2026-07-10');

INSERT IGNORE INTO release_job_responsibility (release_id, job_role_id, job_level_id, responsibility_id, evidence_count, weight) VALUES
  (1, 1, 1, 1, 9, 1), (1, 1, 1, 2, 8, 0.9), (1, 1, 1, 3, 7, 0.8),
  (1, 2, 1, 4, 10, 1), (1, 2, 1, 5, 9, 0.9), (1, 2, 1, 6, 6, 0.6);

INSERT IGNORE INTO release_job_scenario (release_id, job_role_id, scenario_id, evidence_count) VALUES
  (1, 1, 1, 8), (1, 1, 2, 7), (1, 1, 3, 6), (1, 2, 4, 9), (1, 2, 5, 5);

INSERT IGNORE INTO learning_resource (id, resource_name, resource_type, provider_name, review_status, status) VALUES
  (1, 'Java 程序设计', 'COURSE', '高校课程', 'APPROVED', 'ACTIVE'),
  (2, '云原生应用开发', 'COURSE', '行业课程', 'APPROVED', 'ACTIVE'),
  (3, '大模型应用工程', 'COURSE', '领域包', 'APPROVED', 'ACTIVE'),
  (4, '向量检索与知识库问答', 'PROJECT', '领域包', 'APPROVED', 'ACTIVE'),
  (5, '企业知识库问答系统', 'PROJECT', '领域包', 'APPROVED', 'ACTIVE');

INSERT IGNORE INTO learning_resource_skill (resource_id, skill_id, relation_type) VALUES
  (1, 1, 'COVERS'), (2, 8, 'COVERS'), (2, 9, 'COVERS'),
  (3, 10, 'COVERS'), (3, 11, 'COVERS'), (3, 14, 'COVERS'),
  (4, 12, 'VALIDATES'), (4, 13, 'VALIDATES'), (4, 14, 'VALIDATES'),
  (5, 14, 'VALIDATES'), (5, 16, 'VALIDATES');

INSERT IGNORE INTO scoring_template (id, template_code, version, job_family_id, required_skill_weight, responsibility_weight, bonus_skill_weight, proficiency_weight, scenario_weight, certificate_weight, status) VALUES
  (1, 'AI_APP_MATCH', '1.0.0', 1, 0.45, 0.25, 0.10, 0.10, 0.05, 0.05, 'PUBLISHED');
