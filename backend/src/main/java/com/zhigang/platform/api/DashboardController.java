package com.zhigang.platform.api;

import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.domain.PlatformModels.DashboardSummary;
import com.zhigang.platform.reporting.JobAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "总览")
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final JobAnalysisService analysisService;

    public DashboardController(JobAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Operation(summary = "获取平台总览")
    @GetMapping
    public ApiResponse<DashboardSummary> dashboard() {
        return ApiResponse.success(analysisService.dashboard());
    }
}
