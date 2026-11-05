package com.zhigang.platform.repository.mysql;

import com.zhigang.platform.common.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;

@Profile("mysql")
@Repository
public class MySqlEmergingRolePublisher {
    private final JdbcTemplate jdbcTemplate;
    private final MySqlReleaseProtection releaseProtection;

    public MySqlEmergingRolePublisher(JdbcTemplate jdbcTemplate, MySqlReleaseProtection releaseProtection) {
        this.jdbcTemplate = jdbcTemplate;
        this.releaseProtection = releaseProtection;
    }

    @Transactional
    public PublicationResult publish(PublicationInput input, long snapshotId, String releaseVersion,
                                     String policyVersion, String publisher) {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM graph_release WHERE release_version=?",
                Long.class, releaseVersion) > 0) {
            throw new BusinessException("RELEASE_VERSION_EXISTS", "图谱版本号已存在");
        }
        long roleId = createRole(input);
        long releaseId = createRelease(snapshotId, releaseVersion, policyVersion, publisher, input.name());
        copyPreviousReleaseRelations(releaseId);
        writeRelations(releaseId, roleId, input);
        releaseProtection.freeze(releaseId);
        releaseProtection.activate(releaseId, publisher, "PUBLISH_EMERGING_ROLE");
        return new PublicationResult(roleId, releaseId, releaseVersion);
    }

    @Transactional
    public long remove(long roleId, String operator) {
        CurrentRelease current = jdbcTemplate.query("""
                SELECT id, snapshot_id, policy_version FROM graph_release
                WHERE status='PUBLISHED' ORDER BY published_at DESC, id DESC LIMIT 1 FOR UPDATE
                """, resultSet -> resultSet.next()
                ? new CurrentRelease(resultSet.getLong("id"), resultSet.getLong("snapshot_id"),
                resultSet.getString("policy_version")) : null);
        if (current == null) throw new BusinessException("PUBLISHED_RELEASE_REQUIRED", "当前没有生效中的图谱版本");
        Long relationCount = jdbcTemplate.queryForObject("""
                SELECT (SELECT COUNT(*) FROM release_job_skill WHERE release_id=? AND job_role_id=?
                     ) + (SELECT COUNT(*) FROM release_job_responsibility WHERE release_id=? AND job_role_id=?
                     ) + (SELECT COUNT(*) FROM release_job_scenario WHERE release_id=? AND job_role_id=?)
                """, Long.class, current.id(), roleId, current.id(), roleId, current.id(), roleId);
        if (relationCount == null || relationCount == 0) {
            throw new BusinessException("EMERGING_ROLE_NOT_IN_CURRENT_GRAPH", "该岗位不在当前图谱中");
        }
        String version = EmergingReleaseVersions.next("remove", roleId);
        long releaseId = createRelease(current.snapshotId(), version, current.policyVersion(), operator,
                "移除岗位 " + roleId);
        copyReleaseRelationsExcludingRole(current.id(), releaseId, roleId);
        releaseProtection.freeze(releaseId);
        releaseProtection.activate(releaseId, operator, "REMOVE_EMERGING_ROLE");
        jdbcTemplate.update("UPDATE job_role SET status='ARCHIVED' WHERE id=? AND is_emerging=1", roleId);
        return releaseId;
    }

    private long createRole(PublicationInput input) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO domain_pack (pack_code, pack_name, version, status)
                VALUES ('EMERGING_AI','新兴人工智能岗位','1.0','ACTIVE')
                """);
        Long packId = jdbcTemplate.queryForObject("SELECT id FROM domain_pack WHERE pack_code='EMERGING_AI'", Long.class);
        jdbcTemplate.update("""
                INSERT IGNORE INTO job_family (domain_pack_id, family_code, family_name, status)
                VALUES (?, 'EMERGING_AI', '新兴岗位', 'ACTIVE')
                """, packId);
        Long familyId = jdbcTemplate.queryForObject("SELECT id FROM job_family WHERE family_code='EMERGING_AI'", Long.class);
        String roleCode = "DISCOVERED_" + shortHash(input.name()).toUpperCase();
        Long archivedRoleId = jdbcTemplate.query("""
                        SELECT id FROM job_role
                        WHERE role_code=? AND is_emerging=1 AND status='ARCHIVED' LIMIT 1
                        """,
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null, roleCode);
        if (archivedRoleId != null) {
            jdbcTemplate.update("UPDATE job_role SET role_name=?, definition=?, status='PUBLISHED' WHERE id=?",
                    truncate(input.name(), 120), input.definition(), archivedRoleId);
            return archivedRoleId;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO job_role (family_id, role_code, role_name, is_emerging, definition, status)
                        VALUES (?, ?, ?, 1, ?, 'PUBLISHED')
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, familyId);
                statement.setString(2, roleCode);
                statement.setString(3, truncate(input.name(), 120));
                statement.setString(4, input.definition());
                return statement;
            }, keys);
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException("EMERGING_ROLE_EXISTS", "同名或同编码岗位已存在，不能重复发布");
        }
        return keys.getKey().longValue();
    }

    private long createRelease(long snapshotId, String version, String policyVersion, String publisher, String roleName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO graph_release
                        (release_version, snapshot_id, policy_version, status, publisher_id, release_note)
                    VALUES (?, ?, ?, 'ARCHIVED', 1, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, truncate(version, 32));
            statement.setLong(2, snapshotId);
            statement.setString(3, truncate(policyVersion, 32));
            statement.setString(4, truncate("发布新兴岗位：" + roleName + "；操作来源：" + publisher, 2000));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private void copyPreviousReleaseRelations(long releaseId) {
        Long previous = jdbcTemplate.query("""
                SELECT id FROM graph_release WHERE id<>? ORDER BY published_at DESC, id DESC LIMIT 1
                """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null, releaseId);
        if (previous == null) return;
        jdbcTemplate.update("""
                INSERT INTO release_job_skill (release_id, job_role_id, job_level_id, skill_id, requirement_type,
                    demand_rate, strong_demand_rate, evidence_count, independent_company_count, trend_type, effective_from)
                SELECT ?, job_role_id, job_level_id, skill_id, requirement_type, demand_rate, strong_demand_rate,
                    evidence_count, independent_company_count, trend_type, effective_from
                FROM release_job_skill WHERE release_id=?
                """, releaseId, previous);
        jdbcTemplate.update("""
                INSERT INTO release_job_responsibility (release_id, job_role_id, job_level_id, responsibility_id, evidence_count, weight)
                SELECT ?, job_role_id, job_level_id, responsibility_id, evidence_count, weight
                FROM release_job_responsibility WHERE release_id=?
                """, releaseId, previous);
        jdbcTemplate.update("""
                INSERT INTO release_job_scenario (release_id, job_role_id, scenario_id, evidence_count)
                SELECT ?, job_role_id, scenario_id, evidence_count FROM release_job_scenario WHERE release_id=?
                """, releaseId, previous);
    }

    private void copyReleaseRelationsExcludingRole(long sourceReleaseId, long targetReleaseId, long roleId) {
        jdbcTemplate.update("""
                INSERT INTO release_job_skill (release_id, job_role_id, job_level_id, skill_id, requirement_type,
                    demand_rate, strong_demand_rate, evidence_count, independent_company_count, trend_type, effective_from)
                SELECT ?, job_role_id, job_level_id, skill_id, requirement_type, demand_rate, strong_demand_rate,
                    evidence_count, independent_company_count, trend_type, effective_from
                FROM release_job_skill WHERE release_id=? AND job_role_id<>?
                """, targetReleaseId, sourceReleaseId, roleId);
        jdbcTemplate.update("""
                INSERT INTO release_job_responsibility
                    (release_id, job_role_id, job_level_id, responsibility_id, evidence_count, weight)
                SELECT ?, job_role_id, job_level_id, responsibility_id, evidence_count, weight
                FROM release_job_responsibility WHERE release_id=? AND job_role_id<>?
                """, targetReleaseId, sourceReleaseId, roleId);
        jdbcTemplate.update("""
                INSERT INTO release_job_scenario (release_id, job_role_id, scenario_id, evidence_count)
                SELECT ?, job_role_id, scenario_id, evidence_count
                FROM release_job_scenario WHERE release_id=? AND job_role_id<>?
                """, targetReleaseId, sourceReleaseId, roleId);
    }

    private void writeRelations(long releaseId, long roleId, PublicationInput input) {
        Long levelId = jdbcTemplate.query("SELECT id FROM job_level WHERE level_name=? LIMIT 1",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null, input.jobLevel());
        if (levelId == null) levelId = jdbcTemplate.query("SELECT id FROM job_level ORDER BY sort_order, id LIMIT 1",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null);
        if (levelId == null) {
            jdbcTemplate.update("INSERT INTO job_level (level_code, level_name, sort_order) VALUES ('GENERAL', '通用', 1)");
            levelId = jdbcTemplate.queryForObject("SELECT id FROM job_level WHERE level_code='GENERAL'", Long.class);
        }
        for (String skill : input.requiredSkills()) writeSkill(releaseId, roleId, levelId, skill, "REQUIRED", input);
        for (String skill : input.bonusSkills()) writeSkill(releaseId, roleId, levelId, skill, "BONUS", input);
        for (String skill : input.technologyStack().stream()
                .filter(value -> !input.requiredSkills().contains(value) && !input.bonusSkills().contains(value)).toList()) {
            writeSkill(releaseId, roleId, levelId, skill, "MENTIONED", input);
        }
        for (String responsibility : input.responsibilities()) {
            String code = "RESP_" + shortHash(responsibility).toUpperCase();
            jdbcTemplate.update("""
                    INSERT IGNORE INTO responsibility (responsibility_code, responsibility_name, status)
                    VALUES (?, ?, 'PUBLISHED')
                    """, code, truncate(responsibility, 250));
            Long id = jdbcTemplate.queryForObject("SELECT id FROM responsibility WHERE responsibility_code=?", Long.class, code);
            jdbcTemplate.update("""
                    INSERT INTO release_job_responsibility
                    (release_id, job_role_id, job_level_id, responsibility_id, evidence_count, weight)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """, releaseId, roleId, levelId, id, Math.max(1, input.evidenceCount()));
        }
        for (String scenario : input.scenarios()) {
            String code = "SCENARIO_" + shortHash(scenario).toUpperCase();
            jdbcTemplate.update("""
                    INSERT IGNORE INTO industry_scenario (scenario_code, scenario_name, status)
                    VALUES (?, ?, 'PUBLISHED')
                    """, code, truncate(scenario, 120));
            Long id = jdbcTemplate.queryForObject("SELECT id FROM industry_scenario WHERE scenario_code=?", Long.class, code);
            jdbcTemplate.update("""
                    INSERT INTO release_job_scenario (release_id, job_role_id, scenario_id, evidence_count)
                    VALUES (?, ?, ?, ?)
                    """, releaseId, roleId, id, Math.max(1, input.evidenceCount()));
        }
    }

    private void writeSkill(long releaseId, long roleId, long levelId, String name, String type, PublicationInput input) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO capability (capability_code, capability_name, sort_order)
                VALUES ('EMERGING_ENGINEERING', '新兴工程能力', 99)
                """);
        Long capabilityId = jdbcTemplate.queryForObject("SELECT id FROM capability WHERE capability_code='EMERGING_ENGINEERING'", Long.class);
        String code = "SKILL_" + shortHash(name).toUpperCase();
        jdbcTemplate.update("""
                INSERT IGNORE INTO skill (capability_id, skill_code, skill_name, status)
                VALUES (?, ?, ?, 'PUBLISHED')
                """, capabilityId, code, truncate(name, 120));
        Long skillId = jdbcTemplate.queryForObject("SELECT id FROM skill WHERE skill_code=?", Long.class, code);
        jdbcTemplate.update("""
                INSERT INTO release_job_skill (release_id, job_role_id, job_level_id, skill_id, requirement_type,
                    demand_rate, evidence_count, independent_company_count, trend_type, effective_from)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, 'NEW', CURRENT_TIMESTAMP)
                """, releaseId, roleId, levelId, skillId, type, Math.max(1, input.evidenceCount()),
                Math.max(1, input.independentSupportCount()));
    }

    private String truncate(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record PublicationInput(
            String name,
            String jobLevel,
            List<String> technologyStack,
            String definition,
            List<String> responsibilities,
            List<String> requiredSkills,
            List<String> bonusSkills,
            List<String> scenarios,
            int evidenceCount,
            int independentSupportCount
    ) {
    }

    public record PublicationResult(long roleId, long releaseId, String releaseVersion) {
    }

    private record CurrentRelease(long id, long snapshotId, String policyVersion) {
    }
}
