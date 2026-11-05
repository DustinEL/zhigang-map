package com.zhigang.platform.source;

import com.zhigang.platform.analysis.ExtractionService;
import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.source.ProcessingStepProcessor.StepResult;
import com.zhigang.platform.source.SourceImportRepository.SnapshotDocument;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DefaultProcessingStepProcessors {
    private DefaultProcessingStepProcessors() {
    }

    @Component
    static final class FormatStandardization implements ProcessingStepProcessor {
        @Override public String step() { return "FORMAT_STANDARDIZATION"; }

        @Override
        public StepResult process(List<SnapshotDocument> documents) {
            int output = (int) documents.stream().filter(document -> !normalize(document.text()).isBlank()).count();
            return new StepResult(documents.size(), output);
        }

        private String normalize(String text) {
        return text == null ? "" : text.replace((char) 160, ' ').replace("\r\n", "\n").trim();
        }
    }

    @Component
    static final class NearDuplicate implements ProcessingStepProcessor {
        @Override public String step() { return "NEAR_DUPLICATE"; }

        @Override
        public StepResult process(List<SnapshotDocument> documents) {
            Set<String> signatures = new HashSet<>();
            documents.forEach(document -> signatures.add(signature(document.text())));
            return new StepResult(documents.size(), signatures.size());
        }

        private String signature(String text) {
            String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
            return normalized.substring(0, Math.min(256, normalized.length()));
        }
    }

    @Component
    static final class TimeLag implements ProcessingStepProcessor {
        @Override public String step() { return "TIME_LAG"; }

        @Override
        public StepResult process(List<SnapshotDocument> documents) {
            OffsetDateTime threshold = OffsetDateTime.now().minus(Duration.ofDays(365));
            int output = (int) documents.stream().filter(document ->
                    document.publishedAt() == null || !document.publishedAt().isBefore(threshold)).count();
            return new StepResult(documents.size(), output);
        }
    }

    @Component
    static final class Inflation implements ProcessingStepProcessor {
        @Override public String step() { return "INFLATION"; }

        @Override
        public StepResult process(List<SnapshotDocument> documents) {
            int output = (int) documents.stream().filter(document -> document.text() != null
                    && document.text().length() >= 40 && document.text().length() <= 20_000).count();
            return new StepResult(documents.size(), output);
        }
    }

    @Component
    static final class Extraction implements ProcessingStepProcessor {
        private final ExtractionService extractionService;

        Extraction(ExtractionService extractionService) {
            this.extractionService = extractionService;
        }

        @Override public String step() { return "EXTRACTION"; }

        @Override
        public StepResult process(List<SnapshotDocument> documents) {
            int output = (int) documents.stream()
                    .map(document -> extractionService.extractJobDescription(document.id(), document.text()))
                    .filter(result -> !"证据不足".equals(result.conclusionStatus())).count();
            return new StepResult(documents.size(), output);
        }
    }

    @Component
    static final class Normalization implements ProcessingStepProcessor {
        private final SkillNormalizationService normalizationService;

        Normalization(SkillNormalizationService normalizationService) {
            this.normalizationService = normalizationService;
        }

        @Override public String step() { return "NORMALIZATION"; }

        @Override
        public StepResult process(List<SnapshotDocument> documents) {
            int output = documents.stream().mapToInt(document -> normalizationService.extract(document.text()).size()).sum();
            return new StepResult(documents.size(), output);
        }
    }
}
