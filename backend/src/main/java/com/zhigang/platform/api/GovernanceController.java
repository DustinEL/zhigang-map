package com.zhigang.platform.api;

import com.zhigang.platform.api.Requests.ChangeProposalRequest;
import com.zhigang.platform.api.Requests.GraphReleaseRequest;
import com.zhigang.platform.api.Requests.ReviewRequest;
import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.domain.PlatformModels.ChangeProposal;
import com.zhigang.platform.domain.PlatformModels.GraphRelease;
import com.zhigang.platform.domain.PlatformModels.ReleaseDiff;
import com.zhigang.platform.governance.GovernanceService;
import com.zhigang.platform.governance.GovernanceRepository.CurrentReleaseSummary;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "图谱治理")
@RestController
@RequestMapping("/api")
public class GovernanceController {
    private final GovernanceService governanceService;

    public GovernanceController(GovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping("/change-proposals")
    public ApiResponse<List<ChangeProposal>> proposals() {
        return ApiResponse.success(governanceService.proposals());
    }

    @PostMapping("/change-proposals")
    public ApiResponse<ChangeProposal> create(@Valid @RequestBody ChangeProposalRequest request) {
        return ApiResponse.success(governanceService.create(request));
    }

    @PostMapping("/change-proposals/{id}/reviews")
    public ApiResponse<ChangeProposal> review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(governanceService.review(id, request));
    }

    @GetMapping("/public/current-release")
    public ApiResponse<CurrentReleaseSummary> currentRelease() {
        return ApiResponse.success(governanceService.currentSummary());
    }

    @GetMapping("/admin/graph-releases")
    public ApiResponse<List<GraphRelease>> releases() {
        return ApiResponse.success(governanceService.releases());
    }

    @PostMapping("/admin/graph-releases")
    public ApiResponse<GraphRelease> publish(@Valid @RequestBody GraphReleaseRequest request) {
        return ApiResponse.success(governanceService.publish(request));
    }

    @GetMapping("/admin/graph-releases/{id}/diff")
    public ApiResponse<ReleaseDiff> diff(@PathVariable long id) {
        return ApiResponse.success(governanceService.diff(id));
    }

    @PostMapping("/admin/graph-releases/{id}/rollback")
    public ApiResponse<GraphRelease> rollback(@PathVariable long id, @RequestParam String publisher) {
        return ApiResponse.success(governanceService.rollback(id, publisher));
    }
}
