package com.zhigang.platform.api;

import com.zhigang.platform.api.Requests.ClassificationEvaluationRequest;
import com.zhigang.platform.api.Requests.ExtractionEvaluationRequest;
import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.evaluation.EvaluationService;
import com.zhigang.platform.evaluation.EvaluationService.ClassificationMetrics;
import com.zhigang.platform.evaluation.EvaluationService.ExtractionMetrics;
import com.zhigang.platform.reporting.PlatformInsightsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "评测验收")
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
    private final EvaluationService evaluationService;
    private final PlatformInsightsService insightsService;

    public EvaluationController(EvaluationService evaluationService, PlatformInsightsService insightsService) {
        this.evaluationService = evaluationService;
        this.insightsService = insightsService;
    }

    @PostMapping("/classification")
    public ApiResponse<ClassificationMetrics> classification(@Valid @RequestBody ClassificationEvaluationRequest request) {
        return ApiResponse.success(evaluationService.classification(request));
    }

    @PostMapping("/extraction")
    public ApiResponse<ExtractionMetrics> extraction(@Valid @RequestBody ExtractionEvaluationRequest request) {
        return ApiResponse.success(evaluationService.extraction(request));
    }

    @GetMapping("/status")
    public ApiResponse<List<EvaluationStatus>> status() {
        return ApiResponse.success(insightsService.evaluationSummary().metrics().stream()
                .map(metric -> new EvaluationStatus(metric.code(), metric.name(), metric.targetSamples(),
                        metric.sampleCount(), metric.target() / 100.0, metric.status()))
                .toList());
    }

    public record EvaluationStatus(String code, String name, int targetSamples, int frozenSamples,
                                   double targetAccuracy, String status) {
    }
}
