SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS crawler_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_code VARCHAR(64) NOT NULL UNIQUE,
  job_name VARCHAR(128) NOT NULL,
  source_id BIGINT NOT NULL,
  schedule_cron VARCHAR(64) NOT NULL DEFAULT 'MANUAL',
  target_type VARCHAR(32) NOT NULL DEFAULT 'JOB_POSTING',
  parser_version VARCHAR(32) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_crawler_job_source FOREIGN KEY (source_id) REFERENCES data_source(id)
) ENGINE=InnoDB COMMENT='手工或定时资料导入任务';

CREATE TABLE IF NOT EXISTS crawler_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_code VARCHAR(64) NOT NULL UNIQUE,
  crawler_job_id BIGINT NOT NULL,
  source_file_name VARCHAR(255),
  source_file_hash CHAR(64),
  schema_version VARCHAR(32),
  policy_version VARCHAR(32),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  fetched_count INT NOT NULL DEFAULT 0,
  accepted_count INT NOT NULL DEFAULT 0,
  duplicate_count INT NOT NULL DEFAULT 0,
  rejected_count INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64),
  error_message VARCHAR(1024),
  started_at DATETIME,
  completed_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_crawler_run_job FOREIGN KEY (crawler_job_id) REFERENCES crawler_job(id),
  INDEX idx_crawler_run_status_time (status, created_at)
) ENGINE=InnoDB COMMENT='结构化资料导入批次';

CREATE TABLE IF NOT EXISTS dynamic_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  crawler_run_id BIGINT NOT NULL,
  source_id BIGINT NOT NULL,
  source_record_key VARCHAR(255) NOT NULL,
  source_url VARCHAR(1024),
  canonical_url VARCHAR(1024),
  record_type VARCHAR(32) NOT NULL DEFAULT 'JOB_POSTING',
  title VARCHAR(255) NOT NULL,
  company_name VARCHAR(128) NOT NULL,
  city VARCHAR(64),
  experience_requirement VARCHAR(128),
  education_requirement VARCHAR(128),
  salary_raw VARCHAR(128),
  salary_min_monthly DECIMAL(12,2),
  salary_max_monthly DECIMAL(12,2),
  salary_months DECIMAL(4,1) NOT NULL DEFAULT 12,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  published_at DATETIME,
  collected_at DATETIME NOT NULL,
  raw_payload JSON NOT NULL,
  raw_text MEDIUMTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  parser_version VARCHAR(32) NOT NULL,
  data_mode VARCHAR(16) NOT NULL DEFAULT 'REAL',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_QUALITY',
  quality_score DECIMAL(6,3),
  status_reason VARCHAR(1024),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_dynamic_run FOREIGN KEY (crawler_run_id) REFERENCES crawler_run(id),
  CONSTRAINT fk_dynamic_source FOREIGN KEY (source_id) REFERENCES data_source(id),
  UNIQUE KEY uk_dynamic_source_record_hash (source_id, source_record_key, content_hash),
  INDEX idx_dynamic_status_collected (status, collected_at),
  INDEX idx_dynamic_title_status (title, status),
  INDEX idx_dynamic_hash (content_hash)
) ENGINE=InnoDB COMMENT='未晋级的真实动态资料';

CREATE TABLE IF NOT EXISTS dynamic_fact (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dynamic_record_id BIGINT NOT NULL,
  fact_type VARCHAR(32) NOT NULL,
  ontology_status VARCHAR(32),
  canonical_value VARCHAR(512) NOT NULL,
  suggested_category VARCHAR(128),
  raw_value VARCHAR(512) NOT NULL,
  requirement_type VARCHAR(32),
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  evidence_quote VARCHAR(1024) NOT NULL,
  confidence DECIMAL(6,3) NOT NULL,
  offset_verified TINYINT NOT NULL DEFAULT 0,
  review_decision VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  corrected_value VARCHAR(512),
  review_note VARCHAR(1024),
  reviewed_by VARCHAR(128),
  reviewed_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dynamic_fact_record FOREIGN KEY (dynamic_record_id) REFERENCES dynamic_record(id),
  INDEX idx_dynamic_fact_record_type (dynamic_record_id, fact_type),
  INDEX idx_dynamic_fact_candidate (ontology_status, canonical_value, review_decision)
) ENGINE=InnoDB COMMENT='动态资料中的技能和职责证据';

CREATE TABLE IF NOT EXISTS dynamic_quality_issue (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dynamic_record_id BIGINT NOT NULL,
  issue_type VARCHAR(64) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  detail VARCHAR(1024) NOT NULL,
  blocking TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dynamic_issue_record FOREIGN KEY (dynamic_record_id) REFERENCES dynamic_record(id),
  INDEX idx_dynamic_issue_record_severity (dynamic_record_id, severity)
) ENGINE=InnoDB COMMENT='动态资料自动质量问题';

CREATE TABLE IF NOT EXISTS dynamic_review_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dynamic_record_id BIGINT NOT NULL,
  decision VARCHAR(32) NOT NULL,
  reviewer VARCHAR(128) NOT NULL,
  reason VARCHAR(1024),
  policy_version VARCHAR(32) NOT NULL,
  reviewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dynamic_review_record FOREIGN KEY (dynamic_record_id) REFERENCES dynamic_record(id),
  INDEX idx_dynamic_review_time (dynamic_record_id, reviewed_at)
) ENGINE=InnoDB COMMENT='不可覆盖的动态资料审核记录';

CREATE TABLE IF NOT EXISTS promotion_batch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_code VARCHAR(64) NOT NULL UNIQUE,
  target_snapshot_id BIGINT NOT NULL,
  policy_version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  candidate_count INT NOT NULL DEFAULT 0,
  promoted_count INT NOT NULL DEFAULT 0,
  rejected_count INT NOT NULL DEFAULT 0,
  requested_by VARCHAR(128) NOT NULL,
  approved_by VARCHAR(128),
  review_note VARCHAR(1024),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_at DATETIME,
  completed_at DATETIME,
  CONSTRAINT fk_promotion_snapshot FOREIGN KEY (target_snapshot_id) REFERENCES dataset_snapshot(id),
  INDEX idx_promotion_status_time (status, created_at)
) ENGINE=InnoDB COMMENT='真实资料晋级批次';

CREATE TABLE IF NOT EXISTS promotion_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  promotion_batch_id BIGINT NOT NULL,
  dynamic_record_id BIGINT NOT NULL,
  target_document_id BIGINT,
  decision VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  decision_reason VARCHAR(1024),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_promotion_item_batch FOREIGN KEY (promotion_batch_id) REFERENCES promotion_batch(id),
  CONSTRAINT fk_promotion_item_dynamic FOREIGN KEY (dynamic_record_id) REFERENCES dynamic_record(id),
  CONSTRAINT fk_promotion_item_document FOREIGN KEY (target_document_id) REFERENCES raw_document(id),
  UNIQUE KEY uk_promotion_dynamic (promotion_batch_id, dynamic_record_id)
) ENGINE=InnoDB COMMENT='晋级批次逐条结果';

CREATE TABLE IF NOT EXISTS promoted_document_metadata (
  document_id BIGINT PRIMARY KEY,
  dynamic_record_id BIGINT NOT NULL UNIQUE,
  source_record_key VARCHAR(255) NOT NULL,
  source_url VARCHAR(1024),
  canonical_url VARCHAR(1024),
  data_mode VARCHAR(16) NOT NULL DEFAULT 'REAL',
  experience_requirement VARCHAR(128),
  education_requirement VARCHAR(128),
  salary_min_monthly DECIMAL(12,2),
  salary_max_monthly DECIMAL(12,2),
  salary_months DECIMAL(4,1),
  current_snapshot_eligible TINYINT NOT NULL DEFAULT 1,
  trend_eligible TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_promoted_meta_document FOREIGN KEY (document_id) REFERENCES raw_document(id),
  CONSTRAINT fk_promoted_meta_dynamic FOREIGN KEY (dynamic_record_id) REFERENCES dynamic_record(id),
  INDEX idx_promoted_meta_mode (data_mode, current_snapshot_eligible)
) ENGINE=InnoDB COMMENT='晋级文档的真实数据口径';

CREATE OR REPLACE VIEW v_real_promoted_document AS
SELECT rd.*, pdm.source_record_key, pdm.source_url, pdm.canonical_url, pdm.data_mode,
       pdm.experience_requirement, pdm.education_requirement,
       pdm.salary_min_monthly, pdm.salary_max_monthly, pdm.salary_months,
       pdm.current_snapshot_eligible, pdm.trend_eligible
FROM raw_document rd
JOIN promoted_document_metadata pdm ON pdm.document_id = rd.id
WHERE rd.deleted_at IS NULL
  AND rd.is_demo = 0
  AND pdm.data_mode = 'REAL'
  AND pdm.current_snapshot_eligible = 1;

SET FOREIGN_KEY_CHECKS = 1;
