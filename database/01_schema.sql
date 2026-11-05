CREATE DATABASE IF NOT EXISTS zhigang_map
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE zhigang_map;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS data_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_code VARCHAR(64) NOT NULL UNIQUE,
  source_name VARCHAR(128) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  license_status VARCHAR(32) NOT NULL,
  license_note VARCHAR(512),
  connector_type VARCHAR(32) NOT NULL DEFAULT 'FILE',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_data_source_type (source_type, enabled)
) ENGINE=InnoDB COMMENT='多源数据来源与授权信息';

CREATE TABLE IF NOT EXISTS dataset_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  snapshot_code VARCHAR(64) NOT NULL UNIQUE,
  snapshot_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  policy_version VARCHAR(32) NOT NULL,
  raw_count INT NOT NULL DEFAULT 0,
  effective_count INT NOT NULL DEFAULT 0,
  quality_score DECIMAL(6,3),
  publishable TINYINT NOT NULL DEFAULT 0,
  frozen_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_snapshot_status_created (status, created_at)
) ENGINE=InnoDB COMMENT='冻结数据快照';

CREATE TABLE IF NOT EXISTS raw_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_id BIGINT NOT NULL,
  snapshot_id BIGINT NOT NULL,
  document_title VARCHAR(255) NOT NULL,
  company_name VARCHAR(128),
  city VARCHAR(64),
  industry_name VARCHAR(128),
  level_name VARCHAR(64),
  salary_range VARCHAR(64),
  raw_text MEDIUMTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  published_at DATETIME,
  collected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  field_completeness DECIMAL(6,3),
  time_weight DECIMAL(8,6),
  is_demo TINYINT NOT NULL DEFAULT 0,
  deleted_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_raw_source FOREIGN KEY (source_id) REFERENCES data_source(id),
  CONSTRAINT fk_raw_snapshot FOREIGN KEY (snapshot_id) REFERENCES dataset_snapshot(id),
  INDEX idx_raw_snapshot_type_time (snapshot_id, published_at),
  INDEX idx_raw_company_title_time (company_name, document_title, published_at),
  INDEX idx_raw_hash (content_hash),
  FULLTEXT INDEX ft_raw_text (document_title, raw_text)
) ENGINE=InnoDB COMMENT='原始文档，只读留存';

CREATE TABLE IF NOT EXISTS processing_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_code VARCHAR(64) NOT NULL UNIQUE,
  snapshot_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  current_step VARCHAR(64),
  progress INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64),
  error_message VARCHAR(512),
  started_at DATETIME,
  completed_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_run_snapshot FOREIGN KEY (snapshot_id) REFERENCES dataset_snapshot(id),
  INDEX idx_run_snapshot_status (snapshot_id, status)
) ENGINE=InnoDB COMMENT='可重试数据处理任务';

CREATE TABLE IF NOT EXISTS duplicate_cluster (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  snapshot_id BIGINT NOT NULL,
  representative_document_id BIGINT NOT NULL,
  similarity_threshold DECIMAL(6,3) NOT NULL,
  member_count INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dup_snapshot FOREIGN KEY (snapshot_id) REFERENCES dataset_snapshot(id),
  CONSTRAINT fk_dup_representative FOREIGN KEY (representative_document_id) REFERENCES raw_document(id),
  INDEX idx_dup_snapshot_status (snapshot_id, status)
) ENGINE=InnoDB COMMENT='近重复文档簇';

CREATE TABLE IF NOT EXISTS duplicate_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cluster_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  similarity DECIMAL(6,3) NOT NULL,
  contribution_weight DECIMAL(6,3) NOT NULL DEFAULT 0,
  CONSTRAINT fk_dup_member_cluster FOREIGN KEY (cluster_id) REFERENCES duplicate_cluster(id),
  CONSTRAINT fk_dup_member_doc FOREIGN KEY (document_id) REFERENCES raw_document(id),
  UNIQUE KEY uk_cluster_document (cluster_id, document_id),
  INDEX idx_dup_document (document_id)
) ENGINE=InnoDB COMMENT='近重复簇成员';

CREATE TABLE IF NOT EXISTS quality_issue (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  snapshot_id BIGINT NOT NULL,
  document_id BIGINT,
  issue_type VARCHAR(64) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  detail VARCHAR(1024) NOT NULL,
  action_status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
  resolution VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at DATETIME,
  CONSTRAINT fk_issue_snapshot FOREIGN KEY (snapshot_id) REFERENCES dataset_snapshot(id),
  CONSTRAINT fk_issue_document FOREIGN KEY (document_id) REFERENCES raw_document(id),
  INDEX idx_issue_snapshot_type_status (snapshot_id, issue_type, action_status)
) ENGINE=InnoDB COMMENT='噪声、通胀、注入等质量问题';

CREATE TABLE IF NOT EXISTS domain_pack (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  pack_code VARCHAR(64) NOT NULL UNIQUE,
  pack_name VARCHAR(128) NOT NULL,
  version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  description VARCHAR(1024),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='可扩展领域包';

CREATE TABLE IF NOT EXISTS job_family (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  domain_pack_id BIGINT NOT NULL,
  family_code VARCHAR(64) NOT NULL,
  family_name VARCHAR(128) NOT NULL,
  description VARCHAR(1024),
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fk_family_pack FOREIGN KEY (domain_pack_id) REFERENCES domain_pack(id),
  UNIQUE KEY uk_pack_family_code (domain_pack_id, family_code)
) ENGINE=InnoDB COMMENT='岗位族';

CREATE TABLE IF NOT EXISTS job_level (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  level_code VARCHAR(32) NOT NULL UNIQUE,
  level_name VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL
) ENGINE=InnoDB COMMENT='实习、初级、中级、高级职级';

CREATE TABLE IF NOT EXISTS job_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  family_id BIGINT NOT NULL,
  role_code VARCHAR(64) NOT NULL UNIQUE,
  role_name VARCHAR(128) NOT NULL,
  is_emerging TINYINT NOT NULL DEFAULT 0,
  definition TEXT NOT NULL,
  salary_range VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_role_family FOREIGN KEY (family_id) REFERENCES job_family(id),
  INDEX idx_role_family_status (family_id, status),
  INDEX idx_role_emerging_status (is_emerging, status)
) ENGINE=InnoDB COMMENT='岗位标准概念';

CREATE TABLE IF NOT EXISTS capability (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  capability_code VARCHAR(64) NOT NULL UNIQUE,
  capability_name VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0
) ENGINE=InnoDB COMMENT='技能所属能力类别';

CREATE TABLE IF NOT EXISTS skill (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  capability_id BIGINT NOT NULL,
  skill_code VARCHAR(64) NOT NULL UNIQUE,
  skill_name VARCHAR(128) NOT NULL UNIQUE,
  description VARCHAR(1024),
  status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_skill_capability FOREIGN KEY (capability_id) REFERENCES capability(id),
  INDEX idx_skill_capability_status (capability_id, status)
) ENGINE=InnoDB COMMENT='标准技能本体';

CREATE TABLE IF NOT EXISTS skill_alias (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  skill_id BIGINT NOT NULL,
  alias_name VARCHAR(128) NOT NULL,
  match_type VARCHAR(32) NOT NULL DEFAULT 'EXACT',
  confidence DECIMAL(6,3) NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_alias_skill FOREIGN KEY (skill_id) REFERENCES skill(id),
  UNIQUE KEY uk_skill_alias (skill_id, alias_name),
  INDEX idx_alias_name_status (alias_name, status)
) ENGINE=InnoDB COMMENT='技能别名、缩写和常见写法';

CREATE TABLE IF NOT EXISTS skill_prerequisite (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  skill_id BIGINT NOT NULL,
  prerequisite_skill_id BIGINT NOT NULL,
  weight DECIMAL(6,3) NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
  CONSTRAINT fk_pre_skill FOREIGN KEY (skill_id) REFERENCES skill(id),
  CONSTRAINT fk_pre_required FOREIGN KEY (prerequisite_skill_id) REFERENCES skill(id),
  UNIQUE KEY uk_skill_prerequisite (skill_id, prerequisite_skill_id)
) ENGINE=InnoDB COMMENT='学习路径前置技能有向无环图';

CREATE TABLE IF NOT EXISTS responsibility (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  responsibility_code VARCHAR(64) NOT NULL UNIQUE,
  responsibility_name VARCHAR(255) NOT NULL,
  description VARCHAR(1024),
  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED'
) ENGINE=InnoDB COMMENT='标准岗位职责';

CREATE TABLE IF NOT EXISTS industry_scenario (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scenario_code VARCHAR(64) NOT NULL UNIQUE,
  scenario_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED'
) ENGINE=InnoDB COMMENT='典型行业应用场景';

CREATE TABLE IF NOT EXISTS learning_resource (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resource_name VARCHAR(255) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  provider_name VARCHAR(128),
  source_url VARCHAR(1024),
  review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_resource_type_review (resource_type, review_status, status)
) ENGINE=InnoDB COMMENT='课程、项目、证书和测评资源';

CREATE TABLE IF NOT EXISTS learning_resource_skill (
  resource_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  relation_type VARCHAR(32) NOT NULL DEFAULT 'COVERS',
  PRIMARY KEY (resource_id, skill_id),
  CONSTRAINT fk_resource_skill_resource FOREIGN KEY (resource_id) REFERENCES learning_resource(id),
  CONSTRAINT fk_resource_skill_skill FOREIGN KEY (skill_id) REFERENCES skill(id)
) ENGINE=InnoDB COMMENT='资源覆盖或验证技能';

CREATE TABLE IF NOT EXISTS extracted_fact (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  fact_type VARCHAR(32) NOT NULL,
  raw_value VARCHAR(512) NOT NULL,
  normalized_type VARCHAR(32),
  normalized_id BIGINT,
  requirement_type VARCHAR(32),
  confidence DECIMAL(6,3) NOT NULL,
  gate_status VARCHAR(32) NOT NULL,
  extractor_type VARCHAR(32) NOT NULL,
  prompt_version VARCHAR(32),
  input_hash CHAR(64),
  output_hash CHAR(64),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_fact_document FOREIGN KEY (document_id) REFERENCES raw_document(id),
  INDEX idx_fact_doc_type_gate (document_id, fact_type, gate_status),
  INDEX idx_fact_normalized (normalized_type, normalized_id, gate_status)
) ENGINE=InnoDB COMMENT='模型或规则生成的候选事实';

CREATE TABLE IF NOT EXISTS fact_evidence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  fact_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  evidence_quote VARCHAR(1024) NOT NULL,
  evidence_direction VARCHAR(16) NOT NULL DEFAULT 'SUPPORT',
  anchor_valid TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_evidence_fact FOREIGN KEY (fact_id) REFERENCES extracted_fact(id),
  CONSTRAINT fk_evidence_document FOREIGN KEY (document_id) REFERENCES raw_document(id),
  INDEX idx_evidence_fact_valid (fact_id, anchor_valid),
  INDEX idx_evidence_document_offset (document_id, start_offset)
) ENGINE=InnoDB COMMENT='支持或反对事实的原文锚点';

CREATE TABLE IF NOT EXISTS change_proposal (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  proposal_code VARCHAR(64) NOT NULL UNIQUE,
  proposal_type VARCHAR(32) NOT NULL,
  title VARCHAR(255) NOT NULL,
  reason TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  creator_id BIGINT NOT NULL,
  snapshot_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_proposal_snapshot FOREIGN KEY (snapshot_id) REFERENCES dataset_snapshot(id),
  INDEX idx_proposal_status_created (status, created_at)
) ENGINE=InnoDB COMMENT='新岗位与能力变化提案';

CREATE TABLE IF NOT EXISTS proposal_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  proposal_id BIGINT NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  change_type VARCHAR(32) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT,
  payload_json JSON,
  evidence_count INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_item_proposal FOREIGN KEY (proposal_id) REFERENCES change_proposal(id),
  INDEX idx_item_proposal_type (proposal_id, item_type)
) ENGINE=InnoDB COMMENT='提案中的岗位、技能和职责变化';

CREATE TABLE IF NOT EXISTS review_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  proposal_id BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  decision VARCHAR(16) NOT NULL,
  comment VARCHAR(1024),
  reviewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_review_proposal FOREIGN KEY (proposal_id) REFERENCES change_proposal(id),
  INDEX idx_review_proposal_time (proposal_id, reviewed_at)
) ENGINE=InnoDB COMMENT='不可覆盖的审核记录';

CREATE TABLE IF NOT EXISTS graph_release (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_version VARCHAR(32) NOT NULL UNIQUE,
  snapshot_id BIGINT NOT NULL,
  policy_version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  publisher_id BIGINT NOT NULL,
  release_note VARCHAR(2048),
  published_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_release_snapshot FOREIGN KEY (snapshot_id) REFERENCES dataset_snapshot(id),
  INDEX idx_release_status_published (status, published_at)
) ENGINE=InnoDB COMMENT='可回滚图谱版本';

CREATE TABLE IF NOT EXISTS release_job_skill (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_id BIGINT NOT NULL,
  job_role_id BIGINT NOT NULL,
  job_level_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  requirement_type VARCHAR(32) NOT NULL,
  demand_rate DECIMAL(8,6) NOT NULL,
  strong_demand_rate DECIMAL(8,6),
  evidence_count INT NOT NULL,
  independent_company_count INT NOT NULL,
  trend_type VARCHAR(32) NOT NULL DEFAULT 'STABLE',
  effective_from DATETIME NOT NULL,
  CONSTRAINT fk_rjs_release FOREIGN KEY (release_id) REFERENCES graph_release(id),
  CONSTRAINT fk_rjs_role FOREIGN KEY (job_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_rjs_level FOREIGN KEY (job_level_id) REFERENCES job_level(id),
  CONSTRAINT fk_rjs_skill FOREIGN KEY (skill_id) REFERENCES skill(id),
  UNIQUE KEY uk_release_role_level_skill (release_id, job_role_id, job_level_id, skill_id),
  INDEX idx_rjs_role_release_type (job_role_id, release_id, requirement_type)
) ENGINE=InnoDB COMMENT='已发布岗位-技能版本事实';

CREATE TABLE IF NOT EXISTS release_job_responsibility (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_id BIGINT NOT NULL,
  job_role_id BIGINT NOT NULL,
  job_level_id BIGINT NOT NULL,
  responsibility_id BIGINT NOT NULL,
  evidence_count INT NOT NULL,
  weight DECIMAL(8,6) NOT NULL,
  CONSTRAINT fk_rjr_release FOREIGN KEY (release_id) REFERENCES graph_release(id),
  CONSTRAINT fk_rjr_role FOREIGN KEY (job_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_rjr_level FOREIGN KEY (job_level_id) REFERENCES job_level(id),
  CONSTRAINT fk_rjr_resp FOREIGN KEY (responsibility_id) REFERENCES responsibility(id),
  UNIQUE KEY uk_release_role_level_resp (release_id, job_role_id, job_level_id, responsibility_id)
) ENGINE=InnoDB COMMENT='已发布岗位-职责版本事实';

CREATE TABLE IF NOT EXISTS release_job_scenario (
  release_id BIGINT NOT NULL,
  job_role_id BIGINT NOT NULL,
  scenario_id BIGINT NOT NULL,
  evidence_count INT NOT NULL,
  PRIMARY KEY (release_id, job_role_id, scenario_id),
  CONSTRAINT fk_rsc_release FOREIGN KEY (release_id) REFERENCES graph_release(id),
  CONSTRAINT fk_rsc_role FOREIGN KEY (job_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_rsc_scenario FOREIGN KEY (scenario_id) REFERENCES industry_scenario(id)
) ENGINE=InnoDB COMMENT='已发布岗位场景';

CREATE TABLE IF NOT EXISTS outbox_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at DATETIME,
  INDEX idx_outbox_status_retry (status, next_retry_at, created_at)
) ENGINE=InnoDB COMMENT='图谱投影事务 Outbox';

CREATE TABLE IF NOT EXISTS projection_status (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_id BIGINT NOT NULL,
  projection_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  projected_nodes INT NOT NULL DEFAULT 0,
  projected_edges INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1024),
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_projection_release FOREIGN KEY (release_id) REFERENCES graph_release(id),
  UNIQUE KEY uk_release_projection (release_id, projection_type)
) ENGINE=InnoDB COMMENT='Neo4j/Lucene 可重建投影状态';

CREATE TABLE IF NOT EXISTS resume (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  file_name VARCHAR(255),
  stored_file_name VARCHAR(255),
  file_type VARCHAR(32),
  raw_text MEDIUMTEXT,
  education VARCHAR(64),
  major VARCHAR(128),
  graduation_year INT,
  weekly_hours INT,
  status VARCHAR(32) NOT NULL,
  consent_version VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at DATETIME,
  INDEX idx_resume_owner_status (owner_user_id, status, created_at)
) ENGINE=InnoDB COMMENT='本人可见的简历及解析状态';

CREATE TABLE IF NOT EXISTS resume_fact (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id BIGINT NOT NULL,
  fact_type VARCHAR(32) NOT NULL,
  raw_value VARCHAR(512) NOT NULL,
  normalized_id BIGINT,
  start_offset INT,
  end_offset INT,
  confidence DECIMAL(6,3),
  confirmed TINYINT NOT NULL DEFAULT 0,
  user_corrected TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_resume_fact_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  INDEX idx_resume_fact_type_confirmed (resume_id, fact_type, confirmed)
) ENGINE=InnoDB COMMENT='简历结构化事实与用户修正';

CREATE TABLE IF NOT EXISTS ability_evidence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  evidence_level TINYINT NOT NULL,
  evidence_type VARCHAR(32) NOT NULL,
  evidence_quote VARCHAR(1024),
  verification_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
  verified_at DATETIME,
  CONSTRAINT fk_ability_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  CONSTRAINT fk_ability_skill FOREIGN KEY (skill_id) REFERENCES skill(id),
  INDEX idx_ability_resume_skill_level (resume_id, skill_id, evidence_level)
) ENGINE=InnoDB COMMENT='L0-L4 个人能力证据';

CREATE TABLE IF NOT EXISTS scoring_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_code VARCHAR(64) NOT NULL,
  version VARCHAR(32) NOT NULL,
  job_family_id BIGINT NOT NULL,
  required_skill_weight DECIMAL(6,3) NOT NULL,
  responsibility_weight DECIMAL(6,3) NOT NULL,
  bonus_skill_weight DECIMAL(6,3) NOT NULL,
  proficiency_weight DECIMAL(6,3) NOT NULL,
  scenario_weight DECIMAL(6,3) NOT NULL,
  certificate_weight DECIMAL(6,3) NOT NULL,
  status VARCHAR(32) NOT NULL,
  CONSTRAINT fk_score_family FOREIGN KEY (job_family_id) REFERENCES job_family(id),
  UNIQUE KEY uk_score_template_version (template_code, version)
) ENGINE=InnoDB COMMENT='版本化确定性匹配权重';

CREATE TABLE IF NOT EXISTS match_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id BIGINT NOT NULL,
  job_role_id BIGINT NOT NULL,
  graph_release_id BIGINT NOT NULL,
  scoring_template_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  readiness_score DECIMAL(6,2),
  growth_potential DECIMAL(6,2),
  application_state VARCHAR(64),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME,
  CONSTRAINT fk_match_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  CONSTRAINT fk_match_role FOREIGN KEY (job_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_match_release FOREIGN KEY (graph_release_id) REFERENCES graph_release(id),
  CONSTRAINT fk_match_template FOREIGN KEY (scoring_template_id) REFERENCES scoring_template(id),
  INDEX idx_match_resume_created (resume_id, created_at),
  INDEX idx_match_role_release (job_role_id, graph_release_id)
) ENGINE=InnoDB COMMENT='绑定图谱版本的人岗匹配';

CREATE TABLE IF NOT EXISTS match_dimension (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  match_run_id BIGINT NOT NULL,
  dimension_code VARCHAR(64) NOT NULL,
  weight DECIMAL(6,3) NOT NULL,
  raw_score DECIMAL(6,2) NOT NULL,
  weighted_score DECIMAL(6,2) NOT NULL,
  explanation VARCHAR(1024),
  CONSTRAINT fk_dimension_match FOREIGN KEY (match_run_id) REFERENCES match_run(id),
  UNIQUE KEY uk_match_dimension (match_run_id, dimension_code)
) ENGINE=InnoDB COMMENT='可解释匹配维度';

CREATE TABLE IF NOT EXISTS match_skill_detail (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  match_run_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  match_type VARCHAR(32) NOT NULL,
  evidence_level TINYINT NOT NULL,
  required_level TINYINT NOT NULL,
  score DECIMAL(6,2) NOT NULL,
  resume_evidence VARCHAR(1024),
  gap_text VARCHAR(512),
  remedy VARCHAR(1024),
  CONSTRAINT fk_match_skill_run FOREIGN KEY (match_run_id) REFERENCES match_run(id),
  CONSTRAINT fk_match_skill_skill FOREIGN KEY (skill_id) REFERENCES skill(id),
  UNIQUE KEY uk_match_skill (match_run_id, skill_id)
) ENGINE=InnoDB COMMENT='逐技能得分与补足方式';

CREATE TABLE IF NOT EXISTS learning_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  match_run_id BIGINT NOT NULL,
  weekly_hours INT NOT NULL,
  estimated_weeks INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_plan_match FOREIGN KEY (match_run_id) REFERENCES match_run(id),
  INDEX idx_plan_match_created (match_run_id, created_at)
) ENGINE=InnoDB COMMENT='成长路径';

CREATE TABLE IF NOT EXISTS learning_plan_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  sort_order INT NOT NULL,
  stage VARCHAR(64) NOT NULL,
  skill_id BIGINT NOT NULL,
  reason VARCHAR(1024) NOT NULL,
  practical_task VARCHAR(2048) NOT NULL,
  acceptance_criteria VARCHAR(2048) NOT NULL,
  estimated_hours INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'TODO',
  CONSTRAINT fk_plan_item_plan FOREIGN KEY (plan_id) REFERENCES learning_plan(id),
  CONSTRAINT fk_plan_item_skill FOREIGN KEY (skill_id) REFERENCES skill(id),
  UNIQUE KEY uk_plan_sort (plan_id, sort_order)
) ENGINE=InnoDB COMMENT='拓扑排序后的成长路径项';

CREATE TABLE IF NOT EXISTS verification_submission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_item_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  evidence_type VARCHAR(32) NOT NULL,
  evidence_url VARCHAR(1024),
  evidence_text TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  reviewer_id BIGINT,
  reviewed_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_submission_plan_item FOREIGN KEY (plan_item_id) REFERENCES learning_plan_item(id),
  INDEX idx_submission_owner_status (owner_user_id, status)
) ENGINE=InnoDB COMMENT='项目、代码、测试结果等能力验证提交';

CREATE TABLE IF NOT EXISTS evaluation_dataset (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dataset_code VARCHAR(64) NOT NULL,
  dataset_type VARCHAR(32) NOT NULL,
  split_type VARCHAR(32) NOT NULL,
  version VARCHAR(32) NOT NULL,
  frozen TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_evaluation_dataset (dataset_code, version, split_type)
) ENGINE=InnoDB COMMENT='开发、验证、测试、黄金回归集';

CREATE TABLE IF NOT EXISTS evaluation_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dataset_id BIGINT NOT NULL,
  run_type VARCHAR(32) NOT NULL,
  model_version VARCHAR(64),
  prompt_version VARCHAR(32),
  algorithm_version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  sample_count INT NOT NULL,
  started_at DATETIME,
  completed_at DATETIME,
  CONSTRAINT fk_eval_run_dataset FOREIGN KEY (dataset_id) REFERENCES evaluation_dataset(id),
  INDEX idx_eval_run_dataset_type (dataset_id, run_type, status)
) ENGINE=InnoDB COMMENT='评测运行';

CREATE TABLE IF NOT EXISTS metric_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  evaluation_run_id BIGINT NOT NULL,
  metric_name VARCHAR(64) NOT NULL,
  metric_value DECIMAL(10,6) NOT NULL,
  confidence_lower DECIMAL(10,6),
  confidence_upper DECIMAL(10,6),
  sample_count INT NOT NULL,
  CONSTRAINT fk_metric_run FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_run(id),
  UNIQUE KEY uk_run_metric (evaluation_run_id, metric_name)
) ENGINE=InnoDB COMMENT='Accuracy、F1、Recall@K 等指标';

CREATE OR REPLACE VIEW v_raw_document_detail AS
SELECT rd.id, ds.source_type, ds.source_name, ds.license_status,
       rd.company_name, rd.city, rd.industry_name, rd.level_name,
       rd.document_title, rd.salary_range, rd.raw_text, rd.published_at,
       rd.content_hash, rd.is_demo, rd.deleted_at
FROM raw_document rd
JOIN data_source ds ON ds.id = rd.source_id;

CREATE OR REPLACE VIEW v_published_job_role AS
SELECT jr.id, jr.role_code, jr.role_name, jf.family_name,
       jl.level_name, jr.is_emerging, jr.definition, jr.salary_range,
       gr.release_version, jr.updated_at
FROM job_role jr
JOIN job_family jf ON jf.id = jr.family_id
JOIN graph_release gr ON gr.status = 'PUBLISHED'
LEFT JOIN release_job_skill rjs ON rjs.release_id = gr.id AND rjs.job_role_id = jr.id
LEFT JOIN job_level jl ON jl.id = rjs.job_level_id
WHERE jr.status = 'PUBLISHED'
GROUP BY jr.id, jr.role_code, jr.role_name, jf.family_name, jl.level_name,
         jr.is_emerging, jr.definition, jr.salary_range, gr.release_version, jr.updated_at;

CREATE OR REPLACE VIEW v_published_role_item AS
SELECT rjs.job_role_id AS role_id, 'SKILL' AS item_type, s.skill_name AS item_name,
       c.capability_name AS category_name, rjs.requirement_type,
       rjs.demand_rate, rjs.evidence_count, rjs.trend_type, c.sort_order
FROM release_job_skill rjs
JOIN graph_release gr ON gr.id = rjs.release_id AND gr.status = 'PUBLISHED'
JOIN skill s ON s.id = rjs.skill_id
JOIN capability c ON c.id = s.capability_id
UNION ALL
SELECT rjr.job_role_id, 'RESPONSIBILITY', r.responsibility_name, '职责',
       'REQUIRED', rjr.weight, rjr.evidence_count, 'STABLE', 100
FROM release_job_responsibility rjr
JOIN graph_release gr ON gr.id = rjr.release_id AND gr.status = 'PUBLISHED'
JOIN responsibility r ON r.id = rjr.responsibility_id
UNION ALL
SELECT rsc.job_role_id, 'SCENARIO', sc.scenario_name, '场景',
       'MENTIONED', 1, rsc.evidence_count, 'STABLE', 200
FROM release_job_scenario rsc
JOIN graph_release gr ON gr.id = rsc.release_id AND gr.status = 'PUBLISHED'
JOIN industry_scenario sc ON sc.id = rsc.scenario_id;

SET FOREIGN_KEY_CHECKS = 1;
