CREATE TABLE IF NOT EXISTS role_evolution_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, run_code VARCHAR(80) NOT NULL UNIQUE,
  job_role_id BIGINT NOT NULL, policy_version VARCHAR(32) NOT NULL, policy_hash CHAR(64) NOT NULL,
  input_hash CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, conclusion_level VARCHAR(32) NOT NULL,
  sample_control VARCHAR(512) NOT NULL, statistics_method VARCHAR(128) NOT NULL, scope_json JSON NOT NULL,
  draft_note VARCHAR(2048), version INT NOT NULL DEFAULT 1, published_release_id BIGINT, published_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_evolution_run_role FOREIGN KEY (job_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_evolution_run_release FOREIGN KEY (published_release_id) REFERENCES graph_release(id),
  INDEX idx_evolution_run_role_created (job_role_id, created_at), INDEX idx_evolution_run_status_updated (status, updated_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS role_evolution_window (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id BIGINT NOT NULL, window_type VARCHAR(16) NOT NULL,
  window_label VARCHAR(64) NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL,
  document_count INT NOT NULL, independent_company_count INT NOT NULL, source_count INT NOT NULL,
  source_names_json JSON NOT NULL, city_scope VARCHAR(128) NOT NULL, level_scope VARCHAR(128) NOT NULL,
  sample_hash CHAR(64) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_evolution_window_run FOREIGN KEY (run_id) REFERENCES role_evolution_run(id),
  UNIQUE KEY uk_evolution_run_window (run_id, window_type)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS role_evolution_skill_change (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id BIGINT NOT NULL, skill_id BIGINT, skill_name VARCHAR(128) NOT NULL,
  change_type VARCHAR(32) NOT NULL, confidence_level VARCHAR(32) NOT NULL,
  baseline_document_count INT NOT NULL, baseline_company_count INT NOT NULL, baseline_rate DECIMAL(8,6) NOT NULL,
  baseline_lower_bound DECIMAL(8,6) NOT NULL, baseline_upper_bound DECIMAL(8,6) NOT NULL,
  current_document_count INT NOT NULL, current_company_count INT NOT NULL, current_rate DECIMAL(8,6) NOT NULL,
  current_lower_bound DECIMAL(8,6) NOT NULL, current_upper_bound DECIMAL(8,6) NOT NULL,
  absolute_change DECIMAL(8,6) NOT NULL, difference_lower_bound DECIMAL(8,6) NOT NULL,
  difference_upper_bound DECIMAL(8,6) NOT NULL, sensitivity_direction VARCHAR(16) NOT NULL,
  consecutive_absent_windows INT NOT NULL DEFAULT 0, publishable TINYINT NOT NULL DEFAULT 0,
  included_in_draft TINYINT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_evolution_change_run FOREIGN KEY (run_id) REFERENCES role_evolution_run(id),
  CONSTRAINT fk_evolution_change_skill FOREIGN KEY (skill_id) REFERENCES skill(id),
  UNIQUE KEY uk_evolution_run_skill (run_id, skill_name), INDEX idx_evolution_change_type_confidence (change_type, confidence_level)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS role_evolution_evidence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, change_id BIGINT NOT NULL, window_type VARCHAR(16) NOT NULL,
  document_id BIGINT NOT NULL, document_title VARCHAR(255) NOT NULL, company_name VARCHAR(255) NOT NULL,
  source_name VARCHAR(255) NOT NULL, source_url VARCHAR(2048), published_at DATETIME NOT NULL,
  evidence_quote VARCHAR(1024) NOT NULL, start_offset INT NOT NULL, end_offset INT NOT NULL,
  anchor_valid TINYINT NOT NULL DEFAULT 1, evidence_direction VARCHAR(16) NOT NULL DEFAULT 'SUPPORT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_evolution_evidence_change FOREIGN KEY (change_id) REFERENCES role_evolution_skill_change(id),
  CONSTRAINT fk_evolution_evidence_document FOREIGN KEY (document_id) REFERENCES raw_document(id),
  UNIQUE KEY uk_evolution_change_window_anchor (change_id, window_type, document_id, start_offset, end_offset),
  INDEX idx_evolution_evidence_document (document_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS role_evolution_revision (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id BIGINT NOT NULL, from_version INT NOT NULL, to_version INT NOT NULL,
  editor VARCHAR(128) NOT NULL, reason VARCHAR(1024) NOT NULL, before_json JSON NOT NULL, after_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_evolution_revision_run FOREIGN KEY (run_id) REFERENCES role_evolution_run(id),
  INDEX idx_evolution_revision_run_version (run_id, to_version)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS published_role_evolution (
  release_id BIGINT NOT NULL, run_id BIGINT NOT NULL, job_role_id BIGINT NOT NULL, change_id BIGINT NOT NULL,
  PRIMARY KEY (release_id, change_id),
  CONSTRAINT fk_published_evolution_release FOREIGN KEY (release_id) REFERENCES graph_release(id),
  CONSTRAINT fk_published_evolution_run FOREIGN KEY (run_id) REFERENCES role_evolution_run(id),
  CONSTRAINT fk_published_evolution_role FOREIGN KEY (job_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_published_evolution_change FOREIGN KEY (change_id) REFERENCES role_evolution_skill_change(id)
) ENGINE=InnoDB;
