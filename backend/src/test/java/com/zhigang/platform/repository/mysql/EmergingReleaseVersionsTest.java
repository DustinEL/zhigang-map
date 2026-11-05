package com.zhigang.platform.repository.mysql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmergingReleaseVersionsTest {
    @Test
    void repeatedActionsForTheSameRoleProduceDistinctDatabaseSafeVersions() {
        String first = EmergingReleaseVersions.next("emerging", 7L);
        String second = EmergingReleaseVersions.next("emerging", 7L);

        assertThat(first).startsWith("emerging-").hasSizeLessThanOrEqualTo(32);
        assertThat(second).startsWith("emerging-").hasSizeLessThanOrEqualTo(32).isNotEqualTo(first);
    }
}
