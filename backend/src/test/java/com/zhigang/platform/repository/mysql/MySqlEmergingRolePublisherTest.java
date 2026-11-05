package com.zhigang.platform.repository.mysql;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MySqlEmergingRolePublisherTest {
    @Test
    void createReleaseUsesCompetitionSchemaWithoutPublisherName() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MySqlReleaseProtection releaseProtection = mock(MySqlReleaseProtection.class);
        MySqlEmergingRolePublisher publisher = new MySqlEmergingRolePublisher(jdbcTemplate, releaseProtection);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        AtomicReference<String> sql = new AtomicReference<>();
        org.mockito.Mockito.when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
                .thenAnswer(invocation -> {
                    sql.set(invocation.getArgument(0));
                    return statement;
                });
        doAnswer(invocation -> {
            PreparedStatementCreator creator = invocation.getArgument(0);
            KeyHolder keyHolder = invocation.getArgument(1);
            creator.createPreparedStatement(connection);
            keyHolder.getKeyList().add(Map.of("GENERATED_KEY", 42L));
            return 1;
        }).when(jdbcTemplate).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        Method method = MySqlEmergingRolePublisher.class.getDeclaredMethod("createRelease",
                long.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        long releaseId = (long) method.invoke(publisher, 7L, "release-v1", "policy-v1", "manual-user", "提示词工程师");

        assertThat(releaseId).isEqualTo(42L);
        assertThat(sql.get()).contains("publisher_id", "release_note").doesNotContain("publisher_name");
        verify(statement).setString(1, "release-v1");
        verify(statement).setLong(2, 7L);
        verify(statement).setString(3, "policy-v1");
        verify(statement).setString(4, "发布新兴岗位：提示词工程师；操作来源：manual-user");
        org.mockito.Mockito.verify(statement, org.mockito.Mockito.never()).setString(eq(5), anyString());
        org.mockito.Mockito.verify(statement, org.mockito.Mockito.never()).setObject(anyInt(), any());
    }
}
