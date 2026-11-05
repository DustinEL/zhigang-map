SET NAMES utf8mb4;

-- 该脚本可重复执行；已有列和索引不会被覆盖。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_record' AND COLUMN_NAME='risk_level')=0,
              'ALTER TABLE dynamic_record ADD COLUMN risk_level VARCHAR(16) NOT NULL DEFAULT ''UNASSESSED'' AFTER status_reason',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_record' AND COLUMN_NAME='review_route')=0,
              'ALTER TABLE dynamic_record ADD COLUMN review_route VARCHAR(32) NOT NULL DEFAULT ''PENDING_TRIAGE'' AFTER risk_level',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_record' AND COLUMN_NAME='review_reasons')=0,
              'ALTER TABLE dynamic_record ADD COLUMN review_reasons JSON NULL AFTER review_route',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_record' AND COLUMN_NAME='review_source')=0,
              'ALTER TABLE dynamic_record ADD COLUMN review_source VARCHAR(16) NOT NULL DEFAULT ''NONE'' AFTER review_reasons',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_record' AND COLUMN_NAME='triage_policy_version')=0,
              'ALTER TABLE dynamic_record ADD COLUMN triage_policy_version VARCHAR(32) NULL AFTER review_source',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_record' AND COLUMN_NAME='triaged_at')=0,
              'ALTER TABLE dynamic_record ADD COLUMN triaged_at DATETIME NULL AFTER triage_policy_version',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_review_record' AND COLUMN_NAME='review_source')=0,
              'ALTER TABLE dynamic_review_record ADD COLUMN review_source VARCHAR(16) NOT NULL DEFAULT ''HUMAN'' AFTER policy_version',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS candidate_skill_registry (
  candidate_name VARCHAR(512) PRIMARY KEY,
  decision VARCHAR(32) NOT NULL,
  corrected_value VARCHAR(512),
  reviewer VARCHAR(128) NOT NULL,
  note VARCHAR(1024),
  reviewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_candidate_registry_decision (decision, reviewed_at)
) ENGINE=InnoDB COMMENT='跨批次复用的候选技能审核决定';

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_record'
                 AND INDEX_NAME='idx_dynamic_review_queue')=0,
              'CREATE INDEX idx_dynamic_review_queue ON dynamic_record (crawler_run_id, review_route, risk_level, id)',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dynamic_fact'
                 AND INDEX_NAME='idx_dynamic_candidate_review')=0,
              'CREATE INDEX idx_dynamic_candidate_review ON dynamic_fact (ontology_status, review_decision, canonical_value, dynamic_record_id)',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE dynamic_review_record
SET review_source=CASE WHEN reviewer='system:auto-triage' THEN 'SYSTEM' ELSE 'HUMAN' END;

UPDATE dynamic_record dr
LEFT JOIN (
  SELECT rr.dynamic_record_id, rr.review_source
  FROM dynamic_review_record rr
  JOIN (SELECT dynamic_record_id, MAX(id) id FROM dynamic_review_record GROUP BY dynamic_record_id) latest
    ON latest.id=rr.id
) review ON review.dynamic_record_id=dr.id
SET dr.review_source=COALESCE(review.review_source, 'NONE');
