package com.zhigang.platform.repository.mysql;

import com.zhigang.platform.source.ProcessingRunAuditRepository;
import com.zhigang.platform.source.ProcessingService.ProcessingRun;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

@Profile("mysql")
@Repository
public class MySqlProcessingRunAuditRepository implements ProcessingRunAuditRepository {
    private final JdbcTemplate jdbcTemplate;

    public MySqlProcessingRunAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void save(ProcessingRun run) {
        Long snapshotId = jdbcTemplate.query("SELECT id FROM dataset_snapshot WHERE snapshot_code=?",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null, run.snapshotId());
        if (snapshotId == null) return;
        jdbcTemplate.update("""
                INSERT INTO processing_run (run_code, snapshot_id, status, current_step, progress, error_code,
                    error_message, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status=VALUES(status), current_step=VALUES(current_step),
                    progress=VALUES(progress), error_code=VALUES(error_code), error_message=VALUES(error_message),
                    completed_at=VALUES(completed_at)
                """, run.id(), snapshotId, run.status(), currentStep(run), run.progress(), run.errorCode(), run.error(),
                Timestamp.from(run.createdAt().toInstant()), completedAt(run));
        Long runId = jdbcTemplate.queryForObject("SELECT id FROM processing_run WHERE run_code=?", Long.class, run.id());
        for (var step : run.steps()) {
            jdbcTemplate.update("""
                    INSERT INTO processing_step_run (processing_run_id, step_code, status, input_count, output_count,
                        duration_ms, error_code, error_message, attempt, started_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE status=VALUES(status), input_count=VALUES(input_count),
                        output_count=VALUES(output_count), duration_ms=VALUES(duration_ms), error_code=VALUES(error_code),
                        error_message=VALUES(error_message), attempt=VALUES(attempt), started_at=VALUES(started_at),
                        completed_at=VALUES(completed_at)
                    """, runId, step.step(), step.status(), step.inputCount(), step.outputCount(), step.durationMs(),
                    step.errorCode(), step.error(), step.attempt(), timestamp(step.startedAt()), timestamp(step.completedAt()));
        }
    }

    private String currentStep(ProcessingRun run) {
        return run.steps().stream().filter(step -> !"COMPLETED".equals(step.status()))
                .map(step -> step.step()).findFirst().orElse(null);
    }

    private Timestamp completedAt(ProcessingRun run) {
        return "COMPLETED".equals(run.status()) ? Timestamp.from(run.updatedAt().toInstant()) : null;
    }

    private Timestamp timestamp(java.time.OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }
}
