SET NAMES utf8mb4;

SELECT id FROM resume LIMIT 0;
SELECT id FROM match_run LIMIT 0;
SELECT id FROM verification_submission LIMIT 0;

UPDATE ability_evidence SET verification_status='PENDING_VERIFICATION'
WHERE verification_status IN ('UNVERIFIED', 'PENDING');
ALTER TABLE ability_evidence MODIFY COLUMN verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION';

UPDATE verification_submission SET status='PENDING_VERIFICATION' WHERE status='PENDING';
ALTER TABLE verification_submission MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION';

CREATE TABLE IF NOT EXISTS resume_parse_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id BIGINT NOT NULL,
  file_name VARCHAR(255), file_type VARCHAR(32) NOT NULL, content_hash CHAR(64) NOT NULL,
  parser_type VARCHAR(32) NOT NULL, redaction_version VARCHAR(32) NOT NULL,
  redacted_fields_json JSON NOT NULL, status VARCHAR(32) NOT NULL,
  error_code VARCHAR(64), error_message VARCHAR(512), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_resume_parse_run_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  INDEX idx_resume_parse_status_time (status, created_at), INDEX idx_resume_parse_content_hash (content_hash)
) ENGINE=InnoDB COMMENT='简历 Tika/OCR 解析、脱敏与文件哈希审计';

CREATE TABLE IF NOT EXISTS resume_fact_anchor (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, resume_id BIGINT NOT NULL, fact_type VARCHAR(32) NOT NULL,
  raw_value VARCHAR(512) NOT NULL, normalized_value VARCHAR(512), start_offset INT NOT NULL,
  end_offset INT NOT NULL, evidence_quote VARCHAR(1024) NOT NULL, evidence_level TINYINT NOT NULL DEFAULT 0,
  source_type VARCHAR(32) NOT NULL, verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION',
  confidence DECIMAL(6,3) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_resume_fact_anchor_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  INDEX idx_resume_fact_anchor_type (resume_id, fact_type),
  INDEX idx_resume_fact_anchor_verification (verification_status, evidence_level)
) ENGINE=InnoDB COMMENT='教育、专业、项目、技能、证书的原文偏移证据锚点';

CREATE TABLE IF NOT EXISTS match_scoring_calibration (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, policy_version VARCHAR(64) NOT NULL UNIQUE,
  validation_dataset_hash CHAR(64) NOT NULL, weights_json JSON NOT NULL, metrics_json JSON NOT NULL,
  frozen_at DATETIME NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='由冻结验证集校准并冻结的人岗匹配评分策略';

CREATE TABLE IF NOT EXISTS match_score_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, match_run_id BIGINT NOT NULL, policy_version VARCHAR(64) NOT NULL,
  validation_dataset_hash CHAR(64) NOT NULL, dimension_json JSON NOT NULL, hard_gate_json JSON NOT NULL,
  raw_score DECIMAL(6,2) NOT NULL, final_score DECIMAL(6,2) NOT NULL,
  score_confidence DECIMAL(6,2) NOT NULL, score_limit_reason VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_match_score_audit_run FOREIGN KEY (match_run_id) REFERENCES match_run(id),
  UNIQUE KEY uk_match_score_audit_run (match_run_id), INDEX idx_match_score_policy (policy_version, created_at)
) ENGINE=InnoDB COMMENT='硬门槛、分项权重、校准版本和最终分数审计';

CREATE TABLE IF NOT EXISTS verification_evidence_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, verification_submission_id BIGINT NOT NULL,
  target_skill VARCHAR(128) NOT NULL, content_hash CHAR(64) NOT NULL,
  validation_result_json JSON NOT NULL, status VARCHAR(32) NOT NULL, review_reason VARCHAR(1024),
  reviewer_name VARCHAR(128), verified_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_verification_audit_submission FOREIGN KEY (verification_submission_id) REFERENCES verification_submission(id),
  UNIQUE KEY uk_verification_audit_submission (verification_submission_id),
  INDEX idx_verification_audit_status (status, updated_at)
) ENGINE=InnoDB COMMENT='链接可访问性、内容匹配、测试指标与页面确认结果';

CREATE TABLE IF NOT EXISTS verification_review_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, verification_submission_id BIGINT NOT NULL,
  from_status VARCHAR(32) NOT NULL, to_status VARCHAR(32) NOT NULL, reviewer_name VARCHAR(128) NOT NULL,
  reason VARCHAR(1024) NOT NULL, reviewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_verification_review_submission FOREIGN KEY (verification_submission_id) REFERENCES verification_submission(id),
  INDEX idx_verification_review_time (verification_submission_id, reviewed_at)
) ENGINE=InnoDB COMMENT='能力验证状态的不可覆盖人工复核记录';

INSERT IGNORE INTO match_scoring_calibration
  (policy_version, validation_dataset_hash, weights_json, metrics_json, frozen_at)
VALUES ('resume-match-logistic-v1.0','ccc95b1be504f57b598256d6db3f14ea27e4582ec4c56b5f04cb8e225399db76',
        JSON_OBJECT('requiredSkills',0.43,'responsibilities',0.24,'bonusSkills',0.08,'proficiency',0.13,'scenarios',0.07,'certificates',0.05),
        JSON_OBJECT('fixtureF1AtThreshold',1.000,'decisionThreshold',0.60,'brierScore',0.099,
                    'sampleCount',6,'scope','smoke-fixture'),CURRENT_TIMESTAMP);
