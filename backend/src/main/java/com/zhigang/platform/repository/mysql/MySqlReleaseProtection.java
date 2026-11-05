package com.zhigang.platform.repository.mysql;

import com.zhigang.platform.common.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Profile("mysql")
@Component
public class MySqlReleaseProtection {
    private final JdbcTemplate jdbcTemplate;

    public MySqlReleaseProtection(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void freeze(long releaseId) {
        String actualHash = calculateHash(releaseId);
        jdbcTemplate.update("""
                UPDATE graph_release SET content_hash=?, frozen_at=COALESCE(frozen_at, CURRENT_TIMESTAMP)
                WHERE id=? AND (content_hash IS NULL OR content_hash=?)
                """, actualHash, releaseId, actualHash);
        verify(releaseId);
    }

    public void activate(long releaseId, String actor, String action) {
        verify(releaseId);
        Long previousId = jdbcTemplate.query("SELECT id FROM graph_release WHERE status='PUBLISHED' FOR UPDATE",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null);
        if (previousId != null && previousId == releaseId) {
            throw new BusinessException("RELEASE_ALREADY_PUBLISHED", "该版本已经是当前生效版本");
        }
        String hash = calculateHash(releaseId);
        jdbcTemplate.update("UPDATE graph_release SET status='ARCHIVED' WHERE status='PUBLISHED'");
        int activated = jdbcTemplate.update("""
                UPDATE graph_release SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP,
                    publisher_name=? WHERE id=?
                """, actor, releaseId);
        if (activated != 1) throw new BusinessException("RELEASE_ACTIVATION_FAILED", "版本激活失败");
        jdbcTemplate.update("""
                INSERT INTO graph_release_activation
                    (previous_release_id, target_release_id, action_type, actor, expected_hash, actual_hash, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SUCCESS')
                """, previousId, releaseId, action, actor, hash, hash);
    }

    public void verify(long releaseId) {
        String expected = jdbcTemplate.query("SELECT content_hash FROM graph_release WHERE id=?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null, releaseId);
        if (expected == null || expected.isBlank()) {
            throw new BusinessException("RELEASE_NOT_FROZEN", "版本尚未冻结，不能激活");
        }
        String actual = calculateHash(releaseId);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new BusinessException("RELEASE_INTEGRITY_FAILED", "版本完整性校验失败，已拒绝激活");
        }
    }

    public String calculateHash(long releaseId) {
        List<String> parts = new java.util.ArrayList<>();
        parts.addAll(jdbcTemplate.queryForList("""
                SELECT CONCAT('R|', id, '|', release_version, '|', snapshot_id, '|', policy_version, '|',
                    COALESCE(release_note, '')) FROM graph_release WHERE id=?
                """, String.class, releaseId));
        parts.addAll(jdbcTemplate.queryForList("""
                SELECT CONCAT('S|', job_role_id, '|', job_level_id, '|', skill_id, '|', requirement_type, '|',
                    demand_rate, '|', COALESCE(strong_demand_rate, ''), '|', evidence_count, '|',
                    independent_company_count, '|', trend_type)
                FROM release_job_skill WHERE release_id=? ORDER BY job_role_id, job_level_id, skill_id
                """, String.class, releaseId));
        parts.addAll(jdbcTemplate.queryForList("""
                SELECT CONCAT('D|', job_role_id, '|', job_level_id, '|', responsibility_id, '|', evidence_count, '|', weight)
                FROM release_job_responsibility WHERE release_id=? ORDER BY job_role_id, job_level_id, responsibility_id
                """, String.class, releaseId));
        parts.addAll(jdbcTemplate.queryForList("""
                SELECT CONCAT('C|', job_role_id, '|', scenario_id, '|', evidence_count)
                FROM release_job_scenario WHERE release_id=? ORDER BY job_role_id, scenario_id
                """, String.class, releaseId));
        if (parts.isEmpty()) throw new BusinessException("RELEASE_NOT_FOUND", "未找到图谱版本");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
