package com.zhigang.platform.source;

import com.zhigang.platform.api.Requests.DataImportRequest;
import com.zhigang.platform.api.Requests.ProcessingRunRequest;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.source.ProcessingStepProcessor.StepResult;
import com.zhigang.platform.source.SourceImportRepository.ImportedDocument;
import com.zhigang.platform.source.SourceImportRepository.SnapshotDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProcessingService {
    private static final List<String> DEFAULT_STEPS = List.of(
            "FORMAT_STANDARDIZATION", "NEAR_DUPLICATE", "TIME_LAG", "INFLATION", "EXTRACTION", "NORMALIZATION");

    private final Map<String, ImportResult> imports = new ConcurrentHashMap<>();
    private final Map<String, ProcessingRun> runs = new ConcurrentHashMap<>();
    private final SourceImportRepository repository;
    private final Map<String, ProcessingStepProcessor> processors;
    private final ProcessingRunAuditRepository auditRepository;

    @Autowired
    public ProcessingService(SourceImportRepository repository, List<ProcessingStepProcessor> processors,
                             ProcessingRunAuditRepository auditRepository) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        Map<String, ProcessingStepProcessor> index = new LinkedHashMap<>();
        processors.forEach(processor -> index.put(processor.step(), processor));
        this.processors = Map.copyOf(index);
    }

    ProcessingService(SourceImportRepository repository, List<ProcessingStepProcessor> processors) {
        this(repository, processors, run -> { });
    }

    public ImportResult importData(DataImportRequest request) {
        String id = "import-" + UUID.randomUUID();
        Set<String> knownHashes = repository.findContentHashes(request.sourceName());
        List<ImportedDocument> accepted = new ArrayList<>();
        for (int index = 0; index < request.documents().size(); index++) {
            String text = request.documents().get(index).trim();
            String hash = hash(text);
            if (!knownHashes.contains(hash) && accepted.stream().noneMatch(item -> item.contentHash().equals(hash))) {
                accepted.add(new ImportedDocument("document-" + (index + 1), text, hash, OffsetDateTime.now()));
            }
        }
        String snapshotId = accepted.isEmpty() ? null : repository.saveSnapshot(
                request.sourceType(), request.sourceName(), request.licenseStatus(), accepted);
        ImportResult result = new ImportResult(id, request.sourceType(), request.sourceName(), accepted.size(),
                request.documents().size() - accepted.size(), accepted.isEmpty() ? "NO_CHANGES" : "IMPORTED",
                snapshotId, OffsetDateTime.now());
        imports.put(id, result);
        return result;
    }

    public ProcessingRun start(ProcessingRunRequest request) {
        List<String> requestedSteps = request.steps() == null || request.steps().isEmpty()
                ? DEFAULT_STEPS : List.copyOf(request.steps());
        validateSteps(requestedSteps);
        List<SnapshotDocument> documents = repository.findSnapshotDocuments(request.snapshotId());
        if (documents.isEmpty()) {
            throw new BusinessException("SNAPSHOT_DOCUMENTS_EMPTY", "快照不存在或没有可处理的原始文档");
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<StepStatus> steps = requestedSteps.stream().map(step -> StepStatus.pending(step, 1)).toList();
        ProcessingRun run = new ProcessingRun("run-" + UUID.randomUUID(), request.snapshotId(), "RUNNING",
                steps, 0, null, null, now, now);
        runs.put(run.id(), run);
        return execute(run, documents);
    }

    public ProcessingRun get(String id) {
        ProcessingRun run = runs.get(id);
        if (run == null) throw new BusinessException("RUN_NOT_FOUND", "未找到处理任务");
        return run;
    }

    public ProcessingRun retry(String id) {
        ProcessingRun current = get(id);
        if (!"FAILED".equals(current.status())) {
            throw new BusinessException("RUN_NOT_RETRYABLE", "仅失败的处理任务可以重试");
        }
        List<SnapshotDocument> documents = repository.findSnapshotDocuments(current.snapshotId());
        List<StepStatus> reset = current.steps().stream().map(step -> "COMPLETED".equals(step.status())
                ? step : StepStatus.pending(step.step(), step.attempt() + 1)).toList();
        ProcessingRun retried = new ProcessingRun(current.id(), current.snapshotId(), "RUNNING", reset,
                progress(reset), null, null, current.createdAt(), OffsetDateTime.now());
        runs.put(id, retried);
        return execute(retried, documents);
    }

    private ProcessingRun execute(ProcessingRun initial, List<SnapshotDocument> documents) {
        List<StepStatus> steps = new ArrayList<>(initial.steps());
        String errorCode = null;
        String error = null;
        for (int index = 0; index < steps.size(); index++) {
            StepStatus current = steps.get(index);
            if ("COMPLETED".equals(current.status())) continue;
            OffsetDateTime startedAt = OffsetDateTime.now();
            long startedNanos = System.nanoTime();
            try {
                StepResult result = processors.get(current.step()).process(documents);
                steps.set(index, new StepStatus(current.step(), "COMPLETED", result.inputCount(), result.outputCount(),
                        elapsedMillis(startedNanos), null, null, current.attempt(), startedAt, OffsetDateTime.now()));
            } catch (Exception exception) {
                errorCode = exception instanceof BusinessException businessException
                        ? businessException.getCode() : "STEP_EXECUTION_FAILED";
                error = exception.getMessage() == null ? "处理步骤执行失败" : exception.getMessage();
                steps.set(index, new StepStatus(current.step(), "FAILED", documents.size(), 0,
                        elapsedMillis(startedNanos), errorCode, error, current.attempt(), startedAt, OffsetDateTime.now()));
                break;
            }
        }
        String status = error == null ? "COMPLETED" : "FAILED";
        ProcessingRun completed = new ProcessingRun(initial.id(), initial.snapshotId(), status, List.copyOf(steps),
                progress(steps), errorCode, error, initial.createdAt(), OffsetDateTime.now());
        runs.put(completed.id(), completed);
        auditRepository.save(completed);
        return completed;
    }

    private void validateSteps(List<String> steps) {
        if (steps.size() != Set.copyOf(steps).size()) {
            throw new BusinessException("PROCESSING_STEP_DUPLICATED", "处理步骤不能重复");
        }
        for (String step : steps) {
            if (!DEFAULT_STEPS.contains(step) || !processors.containsKey(step)) {
                throw new BusinessException("PROCESSING_STEP_UNSUPPORTED", "不支持的处理步骤：" + step);
            }
        }
    }

    private int progress(List<StepStatus> steps) {
        if (steps.isEmpty()) return 100;
        long completed = steps.stream().filter(step -> "COMPLETED".equals(step.status())).count();
        return (int) (completed * 100 / steps.size());
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toLowerCase().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ImportResult(String id, String sourceType, String sourceName, int acceptedCount, int skippedCount,
                               String status, String snapshotId, OffsetDateTime createdAt) {
    }

    public record StepStatus(String step, String status, int inputCount, int outputCount, long durationMs,
                             String errorCode, String error, int attempt, OffsetDateTime startedAt,
                             OffsetDateTime completedAt) {
        static StepStatus pending(String step, int attempt) {
            return new StepStatus(step, "PENDING", 0, 0, 0, null, null, attempt, null, null);
        }
    }

    public record ProcessingRun(String id, String snapshotId, String status, List<StepStatus> steps, int progress,
                                String errorCode, String error, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
