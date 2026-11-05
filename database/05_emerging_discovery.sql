USE zhigang_map;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS emerging_discovery_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_code VARCHAR(64) NOT NULL UNIQUE,
  snapshot_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  policy_version VARCHAR(64) NOT NULL,
  policy_hash CHAR(64) NOT NULL,
  input_hash CHAR(64) NOT NULL,
  embedding_model VARCHAR(128) NOT NULL,
  embedding_dimension INT,
  cluster_model VARCHAR(128),
  document_count INT NOT NULL DEFAULT 0,
  cluster_count INT NOT NULL DEFAULT 0,
  candidate_count INT NOT NULL DEFAULT 0,
  noise_count INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64),
  error_message VARCHAR(1024),
  requested_by VARCHAR(128) NOT NULL,
  started_at DATETIME,
  completed_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_emerging_run_snapshot FOREIGN KEY (snapshot_id) REFERENCES dataset_snapshot(id),
  INDEX idx_emerging_run_status_created (status, created_at)
) ENGINE=InnoDB COMMENT='新岗位发现运行与可复现参数';

CREATE TABLE IF NOT EXISTS emerging_cluster (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  cluster_label INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  document_count INT NOT NULL,
  company_count INT NOT NULL,
  source_count INT NOT NULL,
  median_probability DECIMAL(8,6) NOT NULL,
  nearest_role_id BIGINT,
  nearest_role_name VARCHAR(128),
  nearest_role_similarity DECIMAL(8,6),
  emergence_score DECIMAL(8,4) NOT NULL DEFAULT 0,
  metric_json JSON NOT NULL,
  opposing_evidence_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_emerging_cluster_run FOREIGN KEY (run_id) REFERENCES emerging_discovery_run(id),
  UNIQUE KEY uk_emerging_run_label (run_id, cluster_label),
  INDEX idx_emerging_cluster_status_score (status, emergence_score)
) ENGINE=InnoDB COMMENT='HDBSCAN 岗位语义簇及可计算指标';

CREATE TABLE IF NOT EXISTS emerging_cluster_member (
  cluster_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  membership_probability DECIMAL(8,6) NOT NULL,
  noise TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (cluster_id, document_id),
  CONSTRAINT fk_emerging_member_cluster FOREIGN KEY (cluster_id) REFERENCES emerging_cluster(id),
  CONSTRAINT fk_emerging_member_document FOREIGN KEY (document_id) REFERENCES raw_document(id),
  INDEX idx_emerging_member_document (document_id)
) ENGINE=InnoDB COMMENT='岗位语义簇成员及概率';

CREATE TABLE IF NOT EXISTS emerging_candidate (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cluster_id BIGINT NOT NULL UNIQUE,
  version INT NOT NULL DEFAULT 1,
  candidate_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  uncertainty VARCHAR(16) NOT NULL,
  responsibilities_json JSON NOT NULL,
  required_skills_json JSON NOT NULL,
  bonus_skills_json JSON NOT NULL,
  scenarios_json JSON NOT NULL,
  generation_mode VARCHAR(64) NOT NULL,
  generation_input_hash CHAR(64),
  creator VARCHAR(128),
  submitted_at DATETIME,
  reviewer VARCHAR(128),
  review_comment VARCHAR(1024),
  reviewed_at DATETIME,
  proposal_id BIGINT,
  published_role_id BIGINT,
  published_release_id BIGINT,
  published_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_emerging_candidate_cluster FOREIGN KEY (cluster_id) REFERENCES emerging_cluster(id),
  CONSTRAINT fk_emerging_candidate_proposal FOREIGN KEY (proposal_id) REFERENCES change_proposal(id),
  CONSTRAINT fk_emerging_candidate_role FOREIGN KEY (published_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_emerging_candidate_release FOREIGN KEY (published_release_id) REFERENCES graph_release(id),
  INDEX idx_emerging_candidate_status_updated (status, updated_at)
) ENGINE=InnoDB COMMENT='可编辑、可审核的新岗位候选';

CREATE TABLE IF NOT EXISTS emerging_candidate_evidence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  candidate_id BIGINT NOT NULL,
  field_name VARCHAR(64) NOT NULL,
  field_value VARCHAR(512) NOT NULL,
  document_id BIGINT NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  evidence_quote VARCHAR(1024) NOT NULL,
  direction VARCHAR(16) NOT NULL DEFAULT 'SUPPORT',
  anchor_valid TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_emerging_evidence_candidate FOREIGN KEY (candidate_id) REFERENCES emerging_candidate(id),
  CONSTRAINT fk_emerging_evidence_document FOREIGN KEY (document_id) REFERENCES raw_document(id),
  INDEX idx_emerging_evidence_candidate_field (candidate_id, field_name, direction),
  INDEX idx_emerging_evidence_document_offset (document_id, start_offset)
) ENGINE=InnoDB COMMENT='候选字段的支持与反对证据';

CREATE TABLE IF NOT EXISTS emerging_candidate_revision (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  candidate_id BIGINT NOT NULL,
  from_version INT NOT NULL,
  to_version INT NOT NULL,
  before_json JSON NOT NULL,
  after_json JSON NOT NULL,
  reason VARCHAR(1024) NOT NULL,
  evidence_ids_json JSON NOT NULL,
  editor VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_emerging_revision_candidate FOREIGN KEY (candidate_id) REFERENCES emerging_candidate(id),
  UNIQUE KEY uk_emerging_revision_version (candidate_id, to_version)
) ENGINE=InnoDB COMMENT='岗位候选字段级修订记录';

CREATE TABLE IF NOT EXISTS emerging_candidate_review (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  candidate_id BIGINT NOT NULL,
  candidate_version INT NOT NULL,
  decision VARCHAR(16) NOT NULL,
  reviewer VARCHAR(128) NOT NULL,
  comment VARCHAR(1024),
  reviewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_emerging_review_candidate FOREIGN KEY (candidate_id) REFERENCES emerging_candidate(id),
  INDEX idx_emerging_review_candidate_time (candidate_id, reviewed_at)
) ENGINE=InnoDB COMMENT='新岗位候选不可覆盖审核记录';

CREATE TABLE IF NOT EXISTS published_role_document (
  release_id BIGINT NOT NULL,
  job_role_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  candidate_id BIGINT NOT NULL,
  PRIMARY KEY (release_id, job_role_id, document_id),
  CONSTRAINT fk_published_role_doc_release FOREIGN KEY (release_id) REFERENCES graph_release(id),
  CONSTRAINT fk_published_role_doc_role FOREIGN KEY (job_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_published_role_doc_document FOREIGN KEY (document_id) REFERENCES raw_document(id),
  CONSTRAINT fk_published_role_doc_candidate FOREIGN KEY (candidate_id) REFERENCES emerging_candidate(id)
) ENGINE=InnoDB COMMENT='发布新岗位与发现证据 JD 的关系';
