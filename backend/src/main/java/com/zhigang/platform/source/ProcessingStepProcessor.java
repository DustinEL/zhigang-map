package com.zhigang.platform.source;

import com.zhigang.platform.source.SourceImportRepository.SnapshotDocument;

import java.util.List;

public interface ProcessingStepProcessor {
    String step();

    StepResult process(List<SnapshotDocument> documents);

    record StepResult(int inputCount, int outputCount) {
    }
}
