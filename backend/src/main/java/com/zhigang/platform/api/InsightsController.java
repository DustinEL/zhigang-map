package com.zhigang.platform.api;

import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.domain.PlatformModels.EvaluationSummary;
import com.zhigang.platform.domain.PlatformModels.RepositoryOverview;
import com.zhigang.platform.reporting.PlatformInsightsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "资料库与评测")
@RestController
@RequestMapping("/api")
public class InsightsController {
    private final PlatformInsightsService insightsService;

    public InsightsController(PlatformInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping("/repositories/overview")
    public ApiResponse<RepositoryOverview> repositoryOverview() {
        return ApiResponse.success(insightsService.repositoryOverview());
    }

    @GetMapping("/evaluation-runs/latest")
    public ApiResponse<EvaluationSummary> latestEvaluation() {
        return ApiResponse.success(insightsService.evaluationSummary());
    }
}
