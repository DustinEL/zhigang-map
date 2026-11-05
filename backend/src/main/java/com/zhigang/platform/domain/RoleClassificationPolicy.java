package com.zhigang.platform.domain;

import java.util.Set;

public final class RoleClassificationPolicy {
    private static final Set<String> CONFIRMED_EXISTING_ROLE_CODES = Set.of(
            "RAG_APP_ENGINEER",
            "AI_AGENT_ENGINEER",
            "LLM_APP_ENGINEER",
            "AI_DATA_ENGINEER",
            "AI_SECURITY_TEST_ENGINEER"
    );

    private RoleClassificationPolicy() {
    }

    public static boolean isEmerging(String roleCode, boolean storedEmerging) {
        return storedEmerging && !CONFIRMED_EXISTING_ROLE_CODES.contains(roleCode);
    }
}
