package com.zhigang.platform.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoleClassificationPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "RAG_APP_ENGINEER",
            "AI_AGENT_ENGINEER",
            "LLM_APP_ENGINEER",
            "AI_DATA_ENGINEER",
            "AI_SECURITY_TEST_ENGINEER"
    })
    void confirmedExistingRolesOverrideStaleEmergingFlag(String roleCode) {
        assertThat(RoleClassificationPolicy.isEmerging(roleCode, true)).isFalse();
    }

    @Test
    void genuineEmergingRoleKeepsStoredFlag() {
        assertThat(RoleClassificationPolicy.isEmerging("LLM_EVALUATION_ENGINEER", true)).isTrue();
        assertThat(RoleClassificationPolicy.isEmerging("JAVA_ENGINEER", false)).isFalse();
    }
}
