package com.zhigang.platform.api;

import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDraft;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDetail;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidatePublish;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateReview;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryCandidate;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryRun;
import com.zhigang.platform.emerging.EmergingDiscoveryService;
import com.zhigang.platform.emerging.EmergingQueryDiscoveryService;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDiscovery;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "新岗位发现")
@RestController
@RequestMapping("/api/emerging-discovery")
public class EmergingDiscoveryController {
    private final EmergingDiscoveryService service;
    private final EmergingQueryDiscoveryService queryService;
    private final boolean devPermitAll;

    public EmergingDiscoveryController(EmergingDiscoveryService service,
                                       EmergingQueryDiscoveryService queryService,
                                       @Value("${zhigang.security.dev-permit-all:false}") boolean devPermitAll) {
        this.service = service;
        this.queryService = queryService;
        this.devPermitAll = devPermitAll;
    }

    @Operation(summary = "按用户输入发现或匹配新岗位")
    @PostMapping("/queries")
    public ApiResponse<QueryDiscovery> discover(@RequestBody QueryRequest request) {
        return ApiResponse.success(queryService.discover(request == null ? null : request.query()));
    }

    @Operation(summary = "查看最近的新岗位查询结果")
    @GetMapping("/queries")
    public ApiResponse<List<QueryDiscovery>> recentQueries(@RequestParam(defaultValue = "12") int limit) {
        return ApiResponse.success(queryService.recent(limit));
    }

    @GetMapping("/queries/{id}")
    public ApiResponse<QueryDiscovery> query(@PathVariable long id) {
        return ApiResponse.success(queryService.get(id));
    }

    @Operation(summary = "证据门禁通过后直接保存到图谱")
    @PostMapping("/queries/{id}/publications")
    public ApiResponse<QueryDiscovery> publishQuery(@PathVariable long id) {
        return ApiResponse.success(queryService.publish(id));
    }

    @Operation(summary = "将新兴岗位从当前图谱移除并保留历史版本")
    @DeleteMapping("/queries/{id}/publications")
    public ApiResponse<QueryDiscovery> removePublishedQuery(@PathVariable long id) {
        return ApiResponse.success(queryService.removeFromGraph(id));
    }

    @Operation(summary = "启动异步新岗位发现")
    @PostMapping("/runs")
    public ApiResponse<DiscoveryRun> start(@RequestParam(required = false) String requestedBy,
                                           Authentication authentication) {
        return ApiResponse.success(service.start(actor(authentication, requestedBy)));
    }

    @GetMapping("/runs")
    public ApiResponse<List<DiscoveryRun>> runs() {
        return ApiResponse.success(service.runs());
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<DiscoveryRun> run(@PathVariable long id) {
        return ApiResponse.success(service.run(id));
    }

    @GetMapping("/candidates")
    public ApiResponse<List<DiscoveryCandidate>> candidates() {
        return ApiResponse.success(service.candidates());
    }

    @GetMapping("/candidates/{id}")
    public ApiResponse<DiscoveryCandidate> candidate(@PathVariable long id) {
        return ApiResponse.success(service.candidate(id));
    }

    @GetMapping("/candidates/{id}/detail")
    public ApiResponse<CandidateDetail> candidateDetail(@PathVariable long id) {
        return ApiResponse.success(service.candidateDetail(id));
    }

    @PutMapping("/candidates/{id}")
    public ApiResponse<DiscoveryCandidate> update(@PathVariable long id, @RequestBody CandidateDraft draft,
                                                  Authentication authentication) {
        return ApiResponse.success(service.update(id, new CandidateDraft(draft.expectedVersion(), draft.name(),
                draft.jobLevel(), draft.technologyStack(),
                draft.responsibilities(), draft.requiredSkills(), draft.bonusSkills(), draft.scenarios(), draft.reason(),
                draft.evidenceIds(), actor(authentication, draft.editor()))));
    }

    @PostMapping("/candidates/{id}/submissions")
    public ApiResponse<DiscoveryCandidate> submit(@PathVariable long id, @RequestParam int expectedVersion,
                                                  @RequestParam(required = false) String creator,
                                                  Authentication authentication) {
        return ApiResponse.success(service.submit(id, expectedVersion, actor(authentication, creator)));
    }

    @PostMapping("/candidates/{id}/reviews")
    public ApiResponse<DiscoveryCandidate> review(@PathVariable long id, @RequestParam int expectedVersion,
                                                  @RequestBody CandidateReview review,
                                                  Authentication authentication) {
        return ApiResponse.success(service.review(id, expectedVersion, new CandidateReview(review.decision(),
                actor(authentication, review.reviewer()), review.comment())));
    }

    @PostMapping("/candidates/{id}/publications")
    public ApiResponse<DiscoveryCandidate> publish(@PathVariable long id, @RequestParam int expectedVersion,
                                                   @RequestBody CandidatePublish publish,
                                                   Authentication authentication) {
        return ApiResponse.success(service.publish(id, expectedVersion, new CandidatePublish(publish.version(),
                actor(authentication, publish.publisher()))));
    }

    private String actor(Authentication authentication, String submitted) {
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) return authentication.getName();
        if (devPermitAll && submitted != null && !submitted.isBlank()) return submitted.trim();
        throw new BusinessException("PROFESSIONAL_AUTH_REQUIRED", "专业端操作必须使用登录身份");
    }
}
