package com.zhigang.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class CompetitionDependenciesHealthIndicatorTest {
    @Test
    void reportsReadableErrorsWhenCompetitionDependenciesAreNotConfigured() {
        CompetitionDependenciesHealthIndicator indicator =
                new CompetitionDependenciesHealthIndicator("", "", "", 200);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("summary").toString())
                .contains("分析工作器不可用", "向量模型不可用", "Qdrant 不可用");
        assertThat(health.getDetails()).containsEntry("analysisWorker", "未配置")
                .containsEntry("embedding", "未配置")
                .containsEntry("qdrant", "未配置");
    }
}
