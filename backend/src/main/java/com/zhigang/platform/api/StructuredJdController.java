package com.zhigang.platform.api;

import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillSummary;
import com.zhigang.platform.source.StructuredJdModels.BulkCandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.BulkDynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordDetail;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordPage;
import com.zhigang.platform.source.StructuredJdModels.DynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchView;
import com.zhigang.platform.source.StructuredJdModels.PromotionPublishCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.RealSnapshotView;
import com.zhigang.platform.source.StructuredJdModels.ReviewQueueSummary;
import com.zhigang.platform.source.StructuredJdModels.StructuredImportResult;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdBatch;
import com.zhigang.platform.source.StructuredJdModels.SourceLedgerItem;
import com.zhigang.platform.source.StructuredJdService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "真实 JD 数据闭环")
@RestController
@RequestMapping("/api")
public class StructuredJdController {
    private final StructuredJdService service;
    private final boolean devPermitAll;

    public StructuredJdController(StructuredJdService service,
                                  @Value("${zhigang.security.dev-permit-all:true}") boolean devPermitAll) {
        this.service = service;
        this.devPermitAll = devPermitAll;
    }

    @Operation(summary = "导入结构化真实 JD")
    @PostMapping("/data-imports/structured-jd")
    public ApiResponse<StructuredImportResult> importStructuredJd(@RequestBody StructuredJdBatch batch) {
        return ApiResponse.success(service.importBatch(batch));
    }

    @GetMapping("/data-imports/{id}")
    public ApiResponse<StructuredImportResult> importRun(@PathVariable long id) {
        return ApiResponse.success(service.getImport(id));
    }

    @GetMapping("/dynamic-records")
    public ApiResponse<DynamicRecordPage> records(
            @RequestParam(required = false) Long importId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String reviewRoute,
            @RequestParam(defaultValue = "false") boolean onlyExceptions,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(service.records(importId, status, keyword, riskLevel, reviewRoute,
                onlyExceptions, page, size));
    }

    @GetMapping("/dynamic-records/{id}")
    public ApiResponse<DynamicRecordDetail> record(@PathVariable long id) {
        return ApiResponse.success(service.record(id));
    }

    @PostMapping("/dynamic-records/{id}/reviews")
    public ApiResponse<DynamicRecordDetail> reviewRecord(
            @PathVariable long id,
            @RequestBody DynamicReviewCommand command,
            Authentication authentication
    ) {
        return ApiResponse.success(service.reviewRecord(id, new DynamicReviewCommand(command.decision(),
                actor(authentication, command.reviewer()), command.reason(), command.factReviews())));
    }

    @PostMapping("/dynamic-records/bulk-reviews")
    public ApiResponse<List<DynamicRecordDetail>> bulkReviewRecords(@RequestBody BulkDynamicReviewCommand command,
                                                                     Authentication authentication) {
        return ApiResponse.success(service.bulkReviewRecords(new BulkDynamicReviewCommand(command.recordIds(),
                command.decision(), actor(authentication, command.reviewer()), command.reason())));
    }

    @PostMapping("/dynamic-records/retriage")
    public ApiResponse<ReviewQueueSummary> retriage(@RequestParam(required = false) Long importId) {
        return ApiResponse.success(service.retriage(importId));
    }

    @GetMapping("/review-queues/summary")
    public ApiResponse<ReviewQueueSummary> reviewQueueSummary(@RequestParam(required = false) Long importId) {
        return ApiResponse.success(service.reviewQueueSummary(importId));
    }

    @GetMapping("/candidate-skills")
    public ApiResponse<List<CandidateSkillSummary>> candidateSkills() {
        return ApiResponse.success(service.candidateSkills());
    }

    @PostMapping("/candidate-skills/{skill}/reviews")
    public ApiResponse<List<CandidateSkillSummary>> reviewCandidateSkill(
            @PathVariable String skill,
            @RequestBody CandidateSkillReviewCommand command,
            Authentication authentication
    ) {
        return ApiResponse.success(service.reviewCandidateSkill(skill, new CandidateSkillReviewCommand(
                command.decision(), command.correctedValue(), actor(authentication, command.reviewer()), command.note())));
    }

    @PostMapping("/candidate-skills/bulk-reviews")
    public ApiResponse<List<CandidateSkillSummary>> bulkReviewCandidateSkills(
            @RequestBody BulkCandidateSkillReviewCommand command,
            Authentication authentication
    ) {
        return ApiResponse.success(service.bulkReviewCandidateSkills(new BulkCandidateSkillReviewCommand(
                command.skills(), command.decision(), command.correctedValue(),
                actor(authentication, command.reviewer()), command.note())));
    }

    @PostMapping("/promotion-batches")
    public ApiResponse<PromotionBatchView> createPromotionBatch(@RequestBody PromotionBatchCommand command,
                                                                 Authentication authentication) {
        return ApiResponse.success(service.createPromotionBatch(new PromotionBatchCommand(command.recordIds(),
                command.importId(), command.selectionMode(), actor(authentication, command.requestedBy()))));
    }

    @GetMapping("/promotion-batches/{id}")
    public ApiResponse<PromotionBatchView> promotionBatch(@PathVariable long id) {
        return ApiResponse.success(service.promotionBatch(id));
    }

    @PostMapping("/promotion-batches/{id}/reviews")
    public ApiResponse<PromotionBatchView> reviewPromotionBatch(
            @PathVariable long id,
            @RequestBody PromotionReviewCommand command,
            Authentication authentication
    ) {
        return ApiResponse.success(service.reviewPromotionBatch(id, new PromotionReviewCommand(command.decision(),
                actor(authentication, command.reviewer()), command.note())));
    }

    @PostMapping("/promotion-batches/{id}/publish")
    public ApiResponse<PromotionBatchView> publishPromotionBatch(
            @PathVariable long id,
            @RequestBody PromotionPublishCommand command,
            Authentication authentication
    ) {
        return ApiResponse.success(service.publishPromotionBatch(id,
                new PromotionPublishCommand(actor(authentication, command.publisher()))));
    }

    @GetMapping("/real-job-snapshots/current")
    public ApiResponse<RealSnapshotView> currentRealSnapshot() {
        return ApiResponse.success(service.currentRealSnapshot());
    }

    @GetMapping("/professional/source-ledger")
    public ApiResponse<List<SourceLedgerItem>> sourceLedger() {
        return ApiResponse.success(service.sourceLedger());
    }

    private String actor(Authentication authentication, String submitted) {
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        if (devPermitAll) return submitted;
        throw new BusinessException("PROFESSIONAL_AUTH_REQUIRED", "专业端操作必须使用登录身份");
    }
}
