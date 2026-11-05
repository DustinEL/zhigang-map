package com.zhigang.platform.repository.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.evolution.RoleEvolutionPolicyLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlRoleEvolutionRepositoryTest {
    private JdbcTemplate jdbcTemplate;
    private MySqlRoleEvolutionRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        repository = new MySqlRoleEvolutionRepository(jdbcTemplate, objectMapper,
                new RoleEvolutionPolicyLoader(objectMapper), mock(MySqlReleaseProtection.class));
    }

    @Test
    void staleEmergingFlagDoesNotBlockConfirmedExistingRole() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 7L,
                "role_code", "LLM_APP_ENGINEER",
                "role_name", "å¤§æ¨¡åž‹åº”ç”¨å¼€å‘å·¥ç¨‹å¸ˆ",
                "is_emerging", true
        )));

        var role = repository.role(7L);

        assertThat(role.code()).isEqualTo("LLM_APP_ENGINEER");
        assertThat(role.name()).isEqualTo("大模型应用开发工程师");
    }

    @Test
    void genuineEmergingRoleIsStillBlocked() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 6L,
                "role_code", "LLM_EVALUATION_ENGINEER",
                "role_name", "大模型评测工程师",
                "is_emerging", true
        )));

        assertThatThrownBy(() -> repository.role(6L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("EVOLUTION_REQUIRES_EXISTING_ROLE"));
    }
}
