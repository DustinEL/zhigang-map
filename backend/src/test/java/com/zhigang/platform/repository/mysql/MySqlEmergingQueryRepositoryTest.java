package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDiscovery;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MySqlEmergingQueryRepositoryTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MySqlEmergingRolePublisher rolePublisher = mock(MySqlEmergingRolePublisher.class);
    private final MySqlEmergingQueryRepository repository = new MySqlEmergingQueryRepository(
            jdbcTemplate, new ObjectMapper(), rolePublisher);

    @Test
    @SuppressWarnings("unchecked")
    void publishLocksDiscoveryRowAndShortCircuitsAfterLockWhenAlreadyPublished() {
        QueryDiscovery published = publishedDiscovery(77L, 99L, 101L);
        when(jdbcTemplate.query(eq("SELECT * FROM emerging_query_discovery WHERE id=? FOR UPDATE"),
                any(RowMapper.class), eq(77L))).thenReturn(List.of(published));

        QueryDiscovery result = repository.publish(77L, "用户手动加入图谱");

        assertThat(result).isSameAs(published);
        verify(jdbcTemplate).query(eq("SELECT * FROM emerging_query_discovery WHERE id=? FOR UPDATE"),
                any(RowMapper.class), eq(77L));
        verifyNoInteractions(rolePublisher);
    }

    @ParameterizedTest
    @MethodSource("invalidLockedDiscoveries")
    @SuppressWarnings("unchecked")
    void publishRejectsLatestLockedDiscoveryWhenItIsNoLongerPublishable(QueryDiscovery lockedDiscovery) {
        when(jdbcTemplate.query(eq("SELECT * FROM emerging_query_discovery WHERE id=? FOR UPDATE"),
                any(RowMapper.class), eq(lockedDiscovery.id()))).thenReturn(List.of(lockedDiscovery));

        assertThatThrownBy(() -> repository.publish(lockedDiscovery.id(), "用户手动加入图谱"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("QUERY_DISCOVERY_PUBLISH_GATE_FAILED"));
        verifyNoInteractions(rolePublisher);
    }

    private static Stream<Arguments> invalidLockedDiscoveries() {
        return Stream.of(
                Arguments.of(unpublishedDiscovery("WAITING_GENERATION", true, true)),
                Arguments.of(unpublishedDiscovery("EMERGING_ROLE", false, true)),
                Arguments.of(unpublishedDiscovery("EMERGING_ROLE", true, false))
        );
    }

    @Test
    void derivedReadOnlyRoleIdIsNotPersistedIntoForeignKeyColumn() {
        long derivedRoleId = 1_000_000_123L;
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_role WHERE id=?", Integer.class, derivedRoleId)).thenReturn(0);

        assertThat(repository.persistentNearestRoleId(derivedRoleId)).isNull();
    }

    @Test
    void existingDatabaseRoleIdRemainsAvailableForPersistence() {
        long persistedRoleId = 12L;
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_role WHERE id=?", Integer.class, persistedRoleId)).thenReturn(1);

        assertThat(repository.persistentNearestRoleId(persistedRoleId)).isEqualTo(persistedRoleId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveBindsSqlNullForDerivedReadOnlyNearestRoleId() throws Exception {
        long derivedRoleId = 1_000_000_123L;
        QueryDraft draft = new QueryDraft("提示词工程师", "提示词工程师", "a".repeat(64),
                "WAITING_GENERATION", "人工智能", "提示词工程师", "等待公开证据补充",
                List.of(), List.of(), List.of(), List.of(), derivedRoleId, "AI Agent 开发工程师", .62, .38,
                false, false, false, "generalv3", "b".repeat(64), "MODEL_UNAVAILABLE",
                "生成服务暂时不可用，请稍后重试", List.of(), List.of());
        OffsetDateTime now = OffsetDateTime.parse("2026-08-08T15:00:00+08:00");
        QueryDiscovery saved = new QueryDiscovery(77, draft.query(), draft.normalizedQuery(), draft.status(),
                draft.domain(), draft.name(), draft.definition(), draft.responsibilities(), draft.requiredSkills(),
                draft.bonusSkills(), draft.scenarios(), null, draft.nearestRoleName(), draft.nearestRoleSimilarity(),
                draft.noveltyScore(), 0, 0, false, false, false, draft.modelName(), draft.errorCode(),
                draft.errorMessage(), null, null, List.of(), List.of(), false, 0, "", null, now, now);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(), List.of(saved));
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_role WHERE id=?", Integer.class, derivedRoleId)).thenReturn(0);
        when(connection.prepareStatement(anyString(), eq(java.sql.Statement.RETURN_GENERATED_KEYS)))
                .thenReturn(statement);
        doAnswer(invocation -> {
            PreparedStatementCreator creator = invocation.getArgument(0);
            KeyHolder keys = invocation.getArgument(1);
            creator.createPreparedStatement(connection);
            keys.getKeyList().add(Map.of("id", 77L));
            return 1;
        }).when(jdbcTemplate).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        QueryDiscovery result = repository.save(draft);

        assertThat(result.id()).isEqualTo(77);
        verify(statement).setNull(12, Types.BIGINT);
        verify(statement, never()).setLong(12, derivedRoleId);
    }

    private QueryDiscovery publishedDiscovery(long id, long roleId, long releaseId) {
        QueryDiscovery unpublished = unpublishedDiscovery("EMERGING_ROLE", true, true);
        return new QueryDiscovery(id, unpublished.query(), unpublished.normalizedQuery(), unpublished.status(),
                unpublished.domain(), unpublished.name(), unpublished.definition(), unpublished.responsibilities(),
                unpublished.requiredSkills(), unpublished.bonusSkills(), unpublished.scenarios(),
                unpublished.nearestRoleId(), unpublished.nearestRoleName(), unpublished.nearestRoleSimilarity(),
                unpublished.noveltyScore(), unpublished.evidenceCount(), unpublished.sourceCount(),
                unpublished.fieldEvidenceComplete(), unpublished.webSearchUsed(), unpublished.publishable(),
                unpublished.modelName(), unpublished.errorCode(), unpublished.errorMessage(), roleId, releaseId,
                unpublished.evidence(), unpublished.fieldEvidence(), false, 0, "", unpublished.updatedAt(),
                unpublished.createdAt(), unpublished.updatedAt());
    }

    private static QueryDiscovery unpublishedDiscovery(String status, boolean fieldEvidenceComplete,
                                                        boolean publishable) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-08T15:00:00+08:00");
        return new QueryDiscovery(77L, "提示词工程师", "提示词工程师", status, "人工智能",
                "提示词工程师", "负责设计和优化提示词工程", List.of("设计提示策略"), List.of("大语言模型"),
                List.of(), List.of("AI 应用优化"), null, "AI Agent 开发工程师", .61, .39, 1, 1,
                fieldEvidenceComplete, true, publishable, "generalv3", "", "", null, null, List.of(), List.of(),
                false, 0, "", null, now, now);
    }
}
