package com.zhigang.platform.source;

import com.zhigang.platform.api.Requests.DataImportRequest;
import com.zhigang.platform.api.Requests.ProcessingRunRequest;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.source.ProcessingStepProcessor.StepResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingServiceTest {
    @Test
    void executesRequestedProcessorsAndRecordsMetrics() {
        List<String> calls = new ArrayList<>();
        ProcessingService service = service(
                processor("FORMAT_STANDARDIZATION", calls, 2),
                processor("EXTRACTION", calls, 3));
        var imported = importDocuments(service);

        var run = service.start(new ProcessingRunRequest(
                imported.snapshotId(), List.of("FORMAT_STANDARDIZATION", "EXTRACTION")));

        assertThat(calls).containsExactly("FORMAT_STANDARDIZATION", "EXTRACTION");
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.progress()).isEqualTo(100);
        assertThat(run.steps()).allSatisfy(step -> {
            assertThat(step.status()).isEqualTo("COMPLETED");
            assertThat(step.inputCount()).isEqualTo(2);
            assertThat(step.durationMs()).isGreaterThanOrEqualTo(0);
        });
        assertThat(run.steps()).extracting(step -> step.outputCount()).containsExactly(2, 3);
    }

    @Test
    void failureStopsPipelineAndRetryOnlyContinuesUnfinishedSteps() {
        List<String> calls = new ArrayList<>();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ProcessingStepProcessor unstable = new ProcessingStepProcessor() {
            @Override public String step() { return "EXTRACTION"; }
            @Override public StepResult process(List<SourceImportRepository.SnapshotDocument> documents) {
                calls.add(step());
                if (failOnce.getAndSet(false)) throw new IllegalStateException("抽取器暂时不可用");
                return new StepResult(documents.size(), documents.size());
            }
        };
        ProcessingService service = service(
                processor("FORMAT_STANDARDIZATION", calls, 2), unstable,
                processor("NORMALIZATION", calls, 4));
        var imported = importDocuments(service);

        var failed = service.start(new ProcessingRunRequest(imported.snapshotId(),
                List.of("FORMAT_STANDARDIZATION", "EXTRACTION", "NORMALIZATION")));
        var retried = service.retry(failed.id());

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("STEP_EXECUTION_FAILED");
        assertThat(failed.steps()).extracting(step -> step.status())
                .containsExactly("COMPLETED", "FAILED", "PENDING");
        assertThat(retried.status()).isEqualTo("COMPLETED");
        assertThat(retried.steps()).extracting(step -> step.attempt()).containsExactly(1, 2, 2);
        assertThat(calls).containsExactly(
                "FORMAT_STANDARDIZATION", "EXTRACTION", "EXTRACTION", "NORMALIZATION");
    }

    @Test
    void rejectsUnsupportedOrDuplicateStepsAndNonFailedRetry() {
        ProcessingService service = service(processor("FORMAT_STANDARDIZATION", new ArrayList<>(), 1));
        var imported = importDocuments(service);
        var completed = service.start(new ProcessingRunRequest(
                imported.snapshotId(), List.of("FORMAT_STANDARDIZATION")));

        assertThatThrownBy(() -> service.start(new ProcessingRunRequest(imported.snapshotId(), List.of("UNKNOWN"))))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("PROCESSING_STEP_UNSUPPORTED");
        assertThatThrownBy(() -> service.start(new ProcessingRunRequest(imported.snapshotId(),
                List.of("FORMAT_STANDARDIZATION", "FORMAT_STANDARDIZATION"))))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("PROCESSING_STEP_DUPLICATED");
        assertThatThrownBy(() -> service.retry(completed.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("RUN_NOT_RETRYABLE");
    }

    @Test
    void duplicateImportDoesNotCreateAnotherSnapshot() {
        ProcessingService service = service(processor("FORMAT_STANDARDIZATION", new ArrayList<>(), 1));
        var request = new DataImportRequest("RECRUITMENT_JD", "同一授权来源", "AUTHORIZED",
                List.of("负责 Java Spring Boot 后端服务设计与数据库性能优化"));

        var first = service.importData(request);
        var duplicate = service.importData(request);

        assertThat(first.status()).isEqualTo("IMPORTED");
        assertThat(first.snapshotId()).isNotBlank();
        assertThat(duplicate.status()).isEqualTo("NO_CHANGES");
        assertThat(duplicate.acceptedCount()).isZero();
        assertThat(duplicate.snapshotId()).isNull();
    }

    private ProcessingService service(ProcessingStepProcessor... processors) {
        return new ProcessingService(new InMemorySourceImportRepository(), List.of(processors));
    }

    private ProcessingStepProcessor processor(String step, List<String> calls, int outputCount) {
        return new ProcessingStepProcessor() {
            @Override public String step() { return step; }
            @Override public StepResult process(List<SourceImportRepository.SnapshotDocument> documents) {
                calls.add(step);
                return new StepResult(documents.size(), outputCount);
            }
        };
    }

    private ProcessingService.ImportResult importDocuments(ProcessingService service) {
        return service.importData(new DataImportRequest("RECRUITMENT_JD", "授权测试来源", "AUTHORIZED",
                List.of("岗位名称：Java 工程师，负责 Spring Boot 服务开发。", "岗位名称：AI 工程师，负责 RAG 应用开发。")));
    }
}
