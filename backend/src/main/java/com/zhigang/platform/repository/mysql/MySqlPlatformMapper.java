package com.zhigang.platform.repository.mysql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Mapper
public interface MySqlPlatformMapper {
    @Select("""
            SELECT s.id, s.skill_name, c.capability_name AS category_name,
                   GROUP_CONCAT(DISTINCT a.alias_name ORDER BY a.alias_name SEPARATOR '||') AS aliases,
                   GROUP_CONCAT(DISTINCT p.skill_name ORDER BY p.skill_name SEPARATOR '||') AS prerequisites
            FROM skill s
            LEFT JOIN capability c ON c.id = s.capability_id
            LEFT JOIN skill_alias a ON a.skill_id = s.id AND a.status = 'APPROVED'
            LEFT JOIN skill_prerequisite sp ON sp.skill_id = s.id AND sp.status = 'PUBLISHED'
            LEFT JOIN skill p ON p.id = sp.prerequisite_skill_id
            WHERE s.status = 'PUBLISHED'
            GROUP BY s.id, s.skill_name, c.capability_name
            ORDER BY s.id
            """)
    List<Map<String, Object>> selectSkills();

    @Select("""
            SELECT rd.id, ds.source_type, ds.source_name, ds.license_status, rd.company_name, rd.city,
                   CASE WHEN dr.status_reason LIKE 'TARGET_ROLE:%'
                        THEN SUBSTRING_INDEX(SUBSTRING(dr.status_reason, 13), '，', 1)
                        ELSE rd.industry_name END AS industry_name,
                   rd.level_name, rd.document_title, rd.salary_range, rd.raw_text,
                   rd.published_at, rd.content_hash, rd.is_demo
            FROM raw_document rd
            JOIN data_source ds ON ds.id=rd.source_id
            JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
            JOIN dynamic_record dr ON dr.id=pdm.dynamic_record_id
            WHERE rd.deleted_at IS NULL AND rd.is_demo=0
              AND rd.snapshot_id=(
                  SELECT pb.target_snapshot_id
                  FROM promotion_batch pb
                  WHERE pb.status='COMPLETED'
                  ORDER BY pb.completed_at DESC, pb.id DESC
                  LIMIT 1
              )
            ORDER BY published_at DESC, id DESC
            """)
    List<Map<String, Object>> selectDocuments();

    @Select("""
            SELECT rd.id, ds.source_type, ds.source_name, ds.license_status, rd.company_name, rd.city,
                   CASE WHEN dr.status_reason LIKE 'TARGET_ROLE:%'
                        THEN SUBSTRING_INDEX(SUBSTRING(dr.status_reason, 13), '，', 1)
                        ELSE rd.industry_name END AS industry_name,
                   rd.level_name, rd.document_title, rd.salary_range, rd.raw_text,
                   rd.published_at, rd.content_hash, rd.is_demo
            FROM raw_document rd
            JOIN data_source ds ON ds.id=rd.source_id
            JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
            JOIN dynamic_record dr ON dr.id=pdm.dynamic_record_id
            WHERE rd.deleted_at IS NULL AND rd.is_demo=0
              AND dr.record_type='JOB_POSTING'
            ORDER BY rd.published_at DESC, rd.id DESC
            """)
    List<Map<String, Object>> selectDiscoveryDocuments();

    @Select("""
            SELECT
              (SELECT COUNT(*)
                 FROM raw_document rd
                 JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
                 JOIN dynamic_record dr ON dr.id=pdm.dynamic_record_id
                WHERE rd.deleted_at IS NULL AND rd.is_demo=0
                  AND dr.record_type='JOB_POSTING') AS sample_count,
              (SELECT COUNT(*)
                 FROM (
                   SELECT CASE
                            WHEN dr.status_reason LIKE 'TARGET_ROLE:%'
                             AND SUBSTRING_INDEX(SUBSTRING(dr.status_reason, 13), '，', 1) IN (
                               'AI_AGENT_ENGINEER', 'JAVA_ENGINEER', 'RAG_APP_ENGINEER',
                               'AI_APP_BACKEND_ENGINEER', 'LLMOPS_ENGINEER', 'LLM_EVALUATION_ENGINEER',
                               'LLM_APP_ENGINEER', 'AI_PLATFORM_ENGINEER', 'AI_DATA_ENGINEER',
                               'KNOWLEDGE_ENGINEER', 'VECTOR_RETRIEVAL_ENGINEER', 'AI_SECURITY_TEST_ENGINEER'
                             )
                            THEN SUBSTRING_INDEX(SUBSTRING(dr.status_reason, 13), '，', 1)
                            WHEN LOWER(rd.document_title) LIKE '%java%' THEN 'JAVA_ENGINEER'
                            ELSE 'AI_AGENT_ENGINEER'
                          END AS role_code
                     FROM raw_document rd
                     JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
                     JOIN dynamic_record dr ON dr.id=pdm.dynamic_record_id
                    WHERE rd.snapshot_id=(
                            SELECT pb.target_snapshot_id
                              FROM promotion_batch pb
                             WHERE pb.status='COMPLETED'
                             ORDER BY pb.completed_at DESC, pb.id DESC LIMIT 1
                          )
                      AND pdm.data_mode='REAL' AND pdm.current_snapshot_eligible=1
                    GROUP BY role_code
                   UNION
                   SELECT CASE jr.role_code
                            WHEN 'JAVA' THEN 'JAVA_ENGINEER'
                            WHEN 'AI_AGENT' THEN 'AI_AGENT_ENGINEER'
                            ELSE jr.role_code
                          END AS role_code
                     FROM graph_release gr
                     JOIN release_job_skill rjs ON rjs.release_id=gr.id
                     JOIN job_role jr ON jr.id=rjs.job_role_id
                      AND jr.is_emerging=1 AND jr.status='PUBLISHED'
                    WHERE gr.status='PUBLISHED'
                    GROUP BY role_code
                 ) public_roles) AS role_count,
              GREATEST(
                COALESCE((SELECT MAX(rd.created_at)
                            FROM raw_document rd
                            JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
                            JOIN dynamic_record dr ON dr.id=pdm.dynamic_record_id
                           WHERE rd.deleted_at IS NULL AND rd.is_demo=0
                             AND dr.record_type='JOB_POSTING'), '1970-01-01 00:00:00'),
                COALESCE((SELECT MAX(jr.updated_at) FROM job_role jr WHERE jr.status='PUBLISHED'),
                         '1970-01-01 00:00:00'),
                COALESCE((SELECT MAX(dr.updated_at)
                            FROM raw_document rd
                            JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
                            JOIN dynamic_record dr ON dr.id=pdm.dynamic_record_id
                           WHERE rd.snapshot_id=(
                                   SELECT pb.target_snapshot_id
                                     FROM promotion_batch pb
                                    WHERE pb.status='COMPLETED'
                                    ORDER BY pb.completed_at DESC, pb.id DESC LIMIT 1
                                 )
                             AND pdm.data_mode='REAL' AND pdm.current_snapshot_eligible=1),
                         '1970-01-01 00:00:00'),
                COALESCE((SELECT MAX(pb.completed_at) FROM promotion_batch pb WHERE pb.status='COMPLETED'),
                         '1970-01-01 00:00:00')
              ) AS updated_at,
              SHA2(CONCAT_WS('|',
                (SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(CRC32(CONCAT_WS(':',
                           gr.id, gr.release_version, gr.snapshot_id, gr.policy_version, gr.status,
                           COALESCE(gr.published_at, ''), gr.created_at))), 0))
                   FROM graph_release gr WHERE gr.status='PUBLISHED'),
                (SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(CRC32(CONCAT_WS(':',
                           rjs.id, rjs.release_id, rjs.job_role_id, rjs.job_level_id, rjs.skill_id,
                           rjs.requirement_type, rjs.demand_rate, COALESCE(rjs.strong_demand_rate, ''),
                           rjs.evidence_count, rjs.independent_company_count, rjs.trend_type, rjs.effective_from))), 0))
                   FROM release_job_skill rjs
                   JOIN graph_release gr ON gr.id=rjs.release_id AND gr.status='PUBLISHED'),
                (SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(CRC32(CONCAT_WS(':',
                           rjr.id, rjr.release_id, rjr.job_role_id, rjr.job_level_id,
                           rjr.responsibility_id, rjr.evidence_count, rjr.weight))), 0))
                   FROM release_job_responsibility rjr
                   JOIN graph_release gr ON gr.id=rjr.release_id AND gr.status='PUBLISHED'),
                (SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(CRC32(CONCAT_WS(':',
                           rsc.release_id, rsc.job_role_id, rsc.scenario_id, rsc.evidence_count))), 0))
                   FROM release_job_scenario rsc
                   JOIN graph_release gr ON gr.id=rsc.release_id AND gr.status='PUBLISHED'),
                (SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(CRC32(CONCAT_WS(':',
                           prd.release_id, prd.job_role_id, prd.document_id, COALESCE(prd.candidate_id, '')))), 0))
                   FROM published_role_document prd
                   JOIN graph_release gr ON gr.id=prd.release_id AND gr.status='PUBLISHED')
              ), 256) AS graph_fingerprint
            """)
    Map<String, Object> selectPublicDataStats();

    @Select("""
            SELECT rd.id, rd.document_title, ds.source_name, rd.company_name,
                   COALESCE(pdm.canonical_url, pdm.source_url) AS source_url,
                   rd.published_at, rd.raw_text
            FROM raw_document rd
            JOIN data_source ds ON ds.id=rd.source_id
            JOIN promoted_document_metadata pdm ON pdm.document_id=rd.id
            WHERE rd.deleted_at IS NULL AND rd.is_demo=0
            ORDER BY rd.published_at DESC, rd.id DESC
            """)
    List<Map<String, Object>> selectDiscoveryEvidence();

    @Select("""
            SELECT ef.document_id, ef.fact_type, ef.raw_value
            FROM extracted_fact ef
            JOIN raw_document rd ON rd.id=ef.document_id
            WHERE ef.gate_status='REVIEWED'
              AND rd.deleted_at IS NULL AND rd.is_demo=0
              AND rd.snapshot_id=(
                  SELECT pb.target_snapshot_id FROM promotion_batch pb
                  WHERE pb.status='COMPLETED'
                  ORDER BY pb.completed_at DESC, pb.id DESC LIMIT 1
              )
            ORDER BY ef.document_id, ef.id
            """)
    List<Map<String, Object>> selectReviewedSemanticFacts();

    @Select("""
            SELECT jr.id, jr.role_code, jr.role_name, jr.definition, jr.salary_range, jr.is_emerging,
                   jf.family_name, jr.updated_at, gr.release_version,
                   GROUP_CONCAT(DISTINCT CONCAT(s.skill_name, '~~', rjs.requirement_type, '~~',
                       rjs.demand_rate, '~~', rjs.evidence_count) ORDER BY s.skill_name SEPARATOR '||') AS skills,
                   GROUP_CONCAT(DISTINCT r.responsibility_name ORDER BY r.responsibility_name SEPARATOR '||') AS responsibilities,
                   GROUP_CONCAT(DISTINCT sc.scenario_name ORDER BY sc.scenario_name SEPARATOR '||') AS scenarios
            FROM graph_release gr
            JOIN job_role jr ON jr.status='PUBLISHED'
            JOIN job_family jf ON jf.id=jr.family_id
            LEFT JOIN release_job_skill rjs ON rjs.release_id=gr.id AND rjs.job_role_id=jr.id
            LEFT JOIN skill s ON s.id=rjs.skill_id
            LEFT JOIN release_job_responsibility rjr ON rjr.release_id=gr.id AND rjr.job_role_id=jr.id
            LEFT JOIN responsibility r ON r.id=rjr.responsibility_id
            LEFT JOIN release_job_scenario rsc ON rsc.release_id=gr.id AND rsc.job_role_id=jr.id
            LEFT JOIN industry_scenario sc ON sc.id=rsc.scenario_id
            WHERE gr.status='PUBLISHED'
              AND (rjs.id IS NOT NULL OR rjr.id IS NOT NULL OR rsc.release_id IS NOT NULL)
            GROUP BY jr.id, jr.role_code, jr.role_name, jr.definition, jr.salary_range, jr.is_emerging,
                     jf.family_name, jr.updated_at, gr.release_version
            ORDER BY jr.id
            """)
    List<Map<String, Object>> selectPublishedBaselineRoles();

    @Select("""
            SELECT lr.id, lr.resource_name, lr.resource_type, lr.provider_name, lr.review_status,
                   GROUP_CONCAT(DISTINCT s.skill_name ORDER BY s.skill_name SEPARATOR '||') AS skills
            FROM learning_resource lr
            LEFT JOIN learning_resource_skill lrs ON lrs.resource_id = lr.id
            LEFT JOIN skill s ON s.id = lrs.skill_id
            WHERE lr.status = 'ACTIVE'
            GROUP BY lr.id, lr.resource_name, lr.resource_type, lr.provider_name, lr.review_status
            ORDER BY lr.id
            """)
    List<Map<String, Object>> selectLearningResources();

    @Select("""
            SELECT jr.id, jr.role_code, jr.role_name, jr.definition, jf.family_name, jr.updated_at,
                   gr.release_version,
                   GROUP_CONCAT(DISTINCT CONCAT(s.skill_name, '~~', rjs.requirement_type, '~~', rjs.demand_rate,
                       '~~', rjs.evidence_count) ORDER BY s.skill_name SEPARATOR '||') AS skills,
                   GROUP_CONCAT(DISTINCT r.responsibility_name ORDER BY r.responsibility_name SEPARATOR '||') AS responsibilities,
                   GROUP_CONCAT(DISTINCT sc.scenario_name ORDER BY sc.scenario_name SEPARATOR '||') AS scenarios
            FROM graph_release gr
            JOIN release_job_skill rjs ON rjs.release_id=gr.id
            JOIN job_role jr ON jr.id=rjs.job_role_id AND jr.is_emerging=1 AND jr.status='PUBLISHED'
            JOIN job_family jf ON jf.id=jr.family_id
            JOIN skill s ON s.id=rjs.skill_id
            LEFT JOIN release_job_responsibility rjr ON rjr.release_id=gr.id AND rjr.job_role_id=jr.id
            LEFT JOIN responsibility r ON r.id=rjr.responsibility_id
            LEFT JOIN release_job_scenario rsc ON rsc.release_id=gr.id AND rsc.job_role_id=jr.id
            LEFT JOIN industry_scenario sc ON sc.id=rsc.scenario_id
            WHERE gr.status='PUBLISHED'
            GROUP BY jr.id, jr.role_code, jr.role_name, jr.definition, jf.family_name, jr.updated_at, gr.release_version
            ORDER BY jr.id
            """)
    List<Map<String, Object>> selectPublishedEmergingRoles();

    @Select("SELECT id, role_code FROM job_role WHERE status='PUBLISHED' ORDER BY id")
    List<Map<String, Object>> selectPublishedRoleIdentities();

    @Select("""
            SELECT prd.document_id FROM published_role_document prd
            JOIN graph_release gr ON gr.id=prd.release_id AND gr.status='PUBLISHED'
            WHERE prd.job_role_id=#{roleId}
            """)
    Set<Long> selectPublishedRoleDocumentIds(long roleId);
}
