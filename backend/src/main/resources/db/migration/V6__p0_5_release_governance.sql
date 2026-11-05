SET NAMES utf8mb4;

-- 所有前置结构先通过校验，避免 MySQL DDL 自动提交造成半迁移状态。
SELECT id FROM emerging_candidate LIMIT 0;
SELECT id FROM processing_run LIMIT 0;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='graph_release' AND COLUMN_NAME='content_hash')=0,
              'ALTER TABLE graph_release ADD COLUMN content_hash CHAR(64) NULL COMMENT ''冻结版本内容 SHA-256''',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='graph_release' AND COLUMN_NAME='frozen_at')=0,
              'ALTER TABLE graph_release ADD COLUMN frozen_at DATETIME NULL COMMENT ''版本冻结时间''',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='graph_release' AND COLUMN_NAME='publisher_name')=0,
              'ALTER TABLE graph_release ADD COLUMN publisher_name VARCHAR(128) NULL COMMENT ''发布或激活操作人''',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='change_proposal' AND COLUMN_NAME='creator_name')=0,
              'ALTER TABLE change_proposal ADD COLUMN creator_name VARCHAR(128) NULL COMMENT ''提案创建人显示名''',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='review_record' AND COLUMN_NAME='reviewer_name')=0,
              'ALTER TABLE review_record ADD COLUMN reviewer_name VARCHAR(128) NULL COMMENT ''审核人显示名''',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE change_proposal SET creator_name='历史岗位研究员' WHERE creator_name IS NULL;
UPDATE review_record SET reviewer_name='历史审核员' WHERE reviewer_name IS NULL;
UPDATE graph_release SET publisher_name='历史发布员' WHERE publisher_name IS NULL;

CREATE TABLE IF NOT EXISTS graph_release_activation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  previous_release_id BIGINT NULL,
  target_release_id BIGINT NOT NULL,
  action_type VARCHAR(32) NOT NULL,
  actor VARCHAR(128) NOT NULL,
  expected_hash CHAR(64) NOT NULL,
  actual_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  error_code VARCHAR(64),
  error_message VARCHAR(512),
  activated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_activation_previous_release FOREIGN KEY (previous_release_id) REFERENCES graph_release(id),
  CONSTRAINT fk_activation_target_release FOREIGN KEY (target_release_id) REFERENCES graph_release(id),
  INDEX idx_activation_target_time (target_release_id, activated_at),
  INDEX idx_activation_actor_time (actor, activated_at)
) ENGINE=InnoDB COMMENT='不可覆盖的版本激活与回滚审计';

CREATE TABLE IF NOT EXISTS processing_step_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  processing_run_id BIGINT NOT NULL,
  step_code VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  input_count INT NOT NULL DEFAULT 0,
  output_count INT NOT NULL DEFAULT 0,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  error_code VARCHAR(64),
  error_message VARCHAR(512),
  attempt INT NOT NULL DEFAULT 1,
  started_at DATETIME,
  completed_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_processing_step_run FOREIGN KEY (processing_run_id) REFERENCES processing_run(id),
  UNIQUE KEY uk_processing_run_step (processing_run_id, step_code),
  INDEX idx_processing_step_status (status, updated_at)
) ENGINE=InnoDB COMMENT='处理步骤真实输入输出、耗时和失败审计';

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='emerging_candidate' AND COLUMN_NAME='job_level')=0,
              'ALTER TABLE emerging_candidate ADD COLUMN job_level VARCHAR(64) NULL COMMENT ''候选岗位职级''',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='emerging_candidate' AND COLUMN_NAME='technology_stack_json')=0,
              'ALTER TABLE emerging_candidate ADD COLUMN technology_stack_json JSON NULL COMMENT ''候选岗位技术栈''',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
