package com.zhigang.platform.api;

import com.zhigang.platform.api.Requests.ExtractionRequest;
import com.zhigang.platform.api.Requests.EvidenceExplanationRequest;
import com.zhigang.platform.analysis.ExtractionService;
import com.zhigang.platform.analysis.EvidenceExplanationService;
import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.domain.PlatformModels.EmergingCandidate;
import com.zhigang.platform.domain.PlatformModels.EvolutionReport;
import com.zhigang.platform.domain.PlatformModels.EvidenceExplanation;
import com.zhigang.platform.domain.PlatformModels.ExtractionResult;
import com.zhigang.platform.domain.PlatformModels.ExtractionMetrics;
import com.zhigang.platform.domain.PlatformModels.GraphView;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.MarketCard;
import com.zhigang.platform.domain.PlatformModels.PublicOverview;
import com.zhigang.platform.domain.PlatformModels.RoleMarketTimeline;
import com.zhigang.platform.domain.PlatformModels.RoleSkillEvidence;
import com.zhigang.platform.reporting.JobAnalysisService;
import com.zhigang.platform.emerging.EmergingDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
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
import java.util.Map;

@Tag(name = "岗位分析")
@RestController
@RequestMapping("/api")
public class AnalysisController {
    private final JobAnalysisService analysisService;
    private final ExtractionService extractionService;
    private final EvidenceExplanationService evidenceExplanationService;
    private final EmergingDiscoveryService emergingDiscoveryService;

    public AnalysisController(JobAnalysisService analysisService, ExtractionService extractionService,
                              EvidenceExplanationService evidenceExplanationService,
                              EmergingDiscoveryService emergingDiscoveryService) {
        this.analysisService = analysisService;
        this.extractionService = extractionService;
        this.evidenceExplanationService = evidenceExplanationService;
        this.emergingDiscoveryService = emergingDiscoveryService;
    }

    @GetMapping("/job-roles")
    public ApiResponse<List<JobRole>> roles() {
        return ApiResponse.success(analysisService.roles());
    }

    @GetMapping("/public/overview")
    public ApiResponse<PublicOverview> publicOverview() {
        return ApiResponse.success(analysisService.publicOverview());
    }

    @GetMapping("/public/data-version")
    public ApiResponse<Map<String, Object>> publicDataVersion() {
        return ApiResponse.success(analysisService.publicDataVersion());
    }

    @GetMapping("/public/graph-overview")
    public ApiResponse<GraphView> publicGraphOverview(
            @RequestParam(required = false) String techStack,
            @RequestParam(required = false) String jobLevel,
            @RequestParam(required = false) String roleVersion,
            @RequestParam(required = false) String evidenceLayer) {
        return ApiResponse.success(analysisService.publicGraphOverview(techStack, jobLevel, roleVersion, evidenceLayer));
    }

    @GetMapping("/job-roles/{id}/market-card")
    public ApiResponse<MarketCard> marketCard(@PathVariable long id) {
        return ApiResponse.success(analysisService.marketCard(id));
    }

    @GetMapping("/job-roles/{id}/market-timeline")
    public ApiResponse<RoleMarketTimeline> marketTimeline(@PathVariable long id) {
        return ApiResponse.success(analysisService.marketTimeline(id));
    }

    @GetMapping("/emerging-job-candidates")
    public ApiResponse<List<EmergingCandidate>> emergingCandidates() {
        List<EmergingCandidate> discovered = emergingDiscoveryService.candidates().stream().map(candidate ->
                new EmergingCandidate(candidate.id(), candidate.name(), candidate.nearestRole(),
                        Math.round(candidate.emergenceScore() * 100.0) / 100.0, candidate.uncertainty(),
                        candidate.documentCount(), candidate.companyCount(), candidate.sourceCount(),
                        candidate.clusterStability(), candidate.status(), candidate.responsibilities(),
                        candidate.requiredSkills(), candidate.bonusSkills(), candidate.scenarios(),
                        candidate.supportingEvidence(), candidate.opposingEvidence())).toList();
        return ApiResponse.success(discovered.isEmpty() ? analysisService.emergingCandidates() : discovered);
    }

    @GetMapping("/job-roles/{id}/evolution")
    public ApiResponse<EvolutionReport> evolution(@PathVariable long id) {
        return ApiResponse.success(analysisService.evolution(id));
    }

    @GetMapping("/job-roles/{id}/graph")
    public ApiResponse<GraphView> graph(
            @PathVariable long id,
            @RequestParam(defaultValue = "PANORAMA") String viewType,
            @RequestParam(required = false) String techStack,
            @RequestParam(required = false) String jobLevel,
            @RequestParam(required = false) String roleVersion,
            @RequestParam(required = false) String evidenceLayer) {
        return ApiResponse.success(analysisService.graph(id, viewType, techStack, jobLevel, roleVersion, evidenceLayer));
    }

    @GetMapping("/job-roles/{id}/skills/{skill}/evidence")
    public ApiResponse<RoleSkillEvidence> skillEvidence(@PathVariable long id, @PathVariable String skill) {
        return ApiResponse.success(analysisService.skillEvidence(id, skill));
    }

    @Operation(summary = "证据化解析 JD")
    @PostMapping("/job-descriptions/extractions")
    public ApiResponse<ExtractionResult> extract(@Valid @RequestBody ExtractionRequest request) {
        return ApiResponse.success(request.documentId() == null
                ? extractionService.extractJobDescription(request.text())
                : extractionService.extractJobDescription(request.documentId(), request.text()));
    }

    @Operation(summary = "查看可信抽取防幻觉指标")
    @GetMapping("/job-descriptions/extractions/metrics")
    public ApiResponse<ExtractionMetrics> extractionMetrics() {
        return ApiResponse.success(extractionService.metrics());
    }

    @Operation(summary = "基于岗位证据生成受控解释")
    @PostMapping("/evidence-explanations")
    public ApiResponse<EvidenceExplanation> explain(@Valid @RequestBody EvidenceExplanationRequest request) {
        return ApiResponse.success(evidenceExplanationService.explain(request));
    }
}
