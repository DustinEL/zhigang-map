USE zhigang_map;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS emerging_query_discovery (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  query_text VARCHAR(160) NOT NULL,
  normalized_query VARCHAR(160) NOT NULL,
  query_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  domain_name VARCHAR(128) NOT NULL DEFAULT '新一代信息技术',
  canonical_name VARCHAR(128) NOT NULL DEFAULT '',
  definition_text TEXT NOT NULL,
  responsibilities_json JSON NOT NULL,
  required_skills_json JSON NOT NULL,
  bonus_skills_json JSON NOT NULL,
  scenarios_json JSON NOT NULL,
  nearest_role_id BIGINT,
  nearest_role_name VARCHAR(128),
  nearest_role_similarity DECIMAL(8,6) NOT NULL DEFAULT 0,
  novelty_score DECIMAL(8,6) NOT NULL DEFAULT 0,
  evidence_count INT NOT NULL DEFAULT 0,
  source_count INT NOT NULL DEFAULT 0,
  field_evidence_complete TINYINT NOT NULL DEFAULT 0,
  web_search_used TINYINT NOT NULL DEFAULT 0,
  publishable TINYINT NOT NULL DEFAULT 0,
  model_name VARCHAR(128) NOT NULL,
  model_input_hash CHAR(64) NOT NULL,
  error_code VARCHAR(64),
  error_message VARCHAR(1024),
  published_role_id BIGINT,
  published_release_id BIGINT,
  published_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_query_discovery_nearest_role FOREIGN KEY (nearest_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_query_discovery_published_role FOREIGN KEY (published_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_query_discovery_release FOREIGN KEY (published_release_id) REFERENCES graph_release(id),
  UNIQUE KEY uk_query_discovery_normalized (normalized_query),
  INDEX idx_query_discovery_status_updated (status, updated_at),
  INDEX idx_query_discovery_published (published_role_id, published_release_id)
) ENGINE=InnoDB COMMENT='用户查询驱动的新岗位发现缓存';

CREATE TABLE IF NOT EXISTS emerging_query_evidence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  discovery_id BIGINT NOT NULL,
  evidence_key VARCHAR(32) NOT NULL,
  title VARCHAR(512) NOT NULL,
  source_name VARCHAR(256) NOT NULL,
  source_domain VARCHAR(255) NOT NULL,
  source_url VARCHAR(2048) NOT NULL,
  published_at_text VARCHAR(64),
  excerpt TEXT NOT NULL,
  url_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_query_evidence_discovery FOREIGN KEY (discovery_id) REFERENCES emerging_query_discovery(id),
  UNIQUE KEY uk_query_evidence_key (discovery_id, evidence_key),
  UNIQUE KEY uk_query_evidence_url (discovery_id, url_hash),
  INDEX idx_query_evidence_domain (source_domain)
) ENGINE=InnoDB COMMENT='星火联网检索返回的可追溯来源';

CREATE TABLE IF NOT EXISTS emerging_query_field_evidence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  discovery_id BIGINT NOT NULL,
  field_name VARCHAR(64) NOT NULL,
  field_value VARCHAR(512) NOT NULL,
  evidence_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_query_field_discovery FOREIGN KEY (discovery_id) REFERENCES emerging_query_discovery(id),
  CONSTRAINT fk_query_field_evidence FOREIGN KEY (evidence_id) REFERENCES emerging_query_evidence(id),
  UNIQUE KEY uk_query_field_anchor (discovery_id, field_name, field_value, evidence_id),
  INDEX idx_query_field_value (discovery_id, field_name, field_value)
) ENGINE=InnoDB COMMENT='新岗位定义字段到联网证据的引用关系';

CREATE TABLE IF NOT EXISTS published_role_query_evidence (
  release_id BIGINT NOT NULL,
  job_role_id BIGINT NOT NULL,
  discovery_id BIGINT NOT NULL,
  evidence_id BIGINT NOT NULL,
  PRIMARY KEY (release_id, job_role_id, evidence_id),
  CONSTRAINT fk_published_query_release FOREIGN KEY (release_id) REFERENCES graph_release(id),
  CONSTRAINT fk_published_query_role FOREIGN KEY (job_role_id) REFERENCES job_role(id),
  CONSTRAINT fk_published_query_discovery FOREIGN KEY (discovery_id) REFERENCES emerging_query_discovery(id),
  CONSTRAINT fk_published_query_evidence FOREIGN KEY (evidence_id) REFERENCES emerging_query_evidence(id)
) ENGINE=InnoDB COMMENT='已发布新兴岗位与联网证据关系';
