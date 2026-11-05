package com.zhigang.platform.api;

import com.zhigang.platform.api.Requests.LearningPlanRequest;
import com.zhigang.platform.api.Requests.MatchRunRequest;
import com.zhigang.platform.api.Requests.ResumeRequest;
import com.zhigang.platform.api.Requests.ResumeFactsUpdateRequest;
import com.zhigang.platform.api.Requests.AbilityProfileRequest;
import com.zhigang.platform.api.Requests.VerificationSubmissionRequest;
import com.zhigang.platform.api.Requests.VerificationReviewRequest;
import com.zhigang.platform.api.Requests.DiagnosisComparisonRequest;
import com.zhigang.platform.career.CareerService;
import com.zhigang.platform.career.ResumeFileService;
import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.domain.PlatformModels.LearningPlan;
import com.zhigang.platform.domain.PlatformModels.MatchReport;
import com.zhigang.platform.domain.PlatformModels.ResumeProfile;
import com.zhigang.platform.domain.PlatformModels.VerificationSubmission;
import com.zhigang.platform.domain.PlatformModels.VerificationSubmissionPage;
import com.zhigang.platform.domain.PlatformModels.DiagnosisComparison;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import com.zhigang.platform.security.AnonymousSessionFilter;

@Tag(name = "职业导航")
@RestController
@RequestMapping("/api")
public class CareerController {
    private final CareerService careerService;
    private final ResumeFileService resumeFileService;

    public CareerController(CareerService careerService, ResumeFileService resumeFileService) {
        this.careerService = careerService;
        this.resumeFileService = resumeFileService;
    }

    @Operation(summary = "解析文本简历并生成能力护照")
    @PostMapping("/resumes")
    public ApiResponse<ResumeProfile> parse(@Valid @RequestBody ResumeRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.parseResume(request, owner(servletRequest)));
    }

    @PostMapping("/resumes/text")
    public ApiResponse<ResumeProfile> parseText(@Valid @RequestBody ResumeRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.parseResume(request, owner(servletRequest)));
    }

    @PutMapping("/resumes/{id}/text")
    public ApiResponse<ResumeProfile> reparseText(@PathVariable long id, @Valid @RequestBody ResumeRequest request,
                                                   HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.reparseResume(id, request, owner(servletRequest)));
    }

    @Operation(summary = "上传并解析文本型 PDF/DOCX 简历")
    @PostMapping(value = "/resumes/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ResumeProfile> parseFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam int weeklyHours,
            @RequestParam int graduationYear,
            HttpServletRequest servletRequest
    ) {
        ResumeFileService.ExtractedResumeDocument document = resumeFileService.extract(file);
        return ApiResponse.success(careerService.parseResume(
                new ResumeRequest(document.text(), weeklyHours, graduationYear), owner(servletRequest), document.parsingMode()));
    }

    @PostMapping(value = "/resumes/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ResumeProfile> parseSingleFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam int weeklyHours,
            @RequestParam int graduationYear,
            HttpServletRequest servletRequest
    ) {
        return parseFile(file, weeklyHours, graduationYear, servletRequest);
    }

    @PutMapping(value = "/resumes/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ResumeProfile> reparseFile(
            @PathVariable long id,
            @RequestPart("file") MultipartFile file,
            @RequestParam int weeklyHours,
            @RequestParam int graduationYear,
            HttpServletRequest servletRequest
    ) {
        ResumeFileService.ExtractedResumeDocument document = resumeFileService.extract(file);
        return ApiResponse.success(careerService.reparseResume(
                id, new ResumeRequest(document.text(), weeklyHours, graduationYear), owner(servletRequest), document.parsingMode()));
    }

    @PostMapping("/ability-profiles")
    public ApiResponse<ResumeProfile> abilityProfile(@Valid @RequestBody AbilityProfileRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.createAbilityProfile(request, owner(servletRequest)));
    }

    @GetMapping("/resumes/{id}/facts")
    public ApiResponse<ResumeProfile> facts(@PathVariable long id, HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.resume(id, owner(servletRequest)));
    }

    @PutMapping("/resumes/{id}/facts")
    public ApiResponse<ResumeProfile> updateFacts(@PathVariable long id, @Valid @RequestBody ResumeFactsUpdateRequest request,
                                                   HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.updateResume(id, request, owner(servletRequest)));
    }

    @DeleteMapping("/resumes/{id}")
    public ApiResponse<Void> deleteResume(@PathVariable long id, HttpServletRequest servletRequest) {
        careerService.deleteResume(id, owner(servletRequest));
        return ApiResponse.success(null);
    }

    @PostMapping("/match-runs")
    public ApiResponse<MatchReport> match(@Valid @RequestBody MatchRunRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.match(request, owner(servletRequest)));
    }

    @GetMapping("/match-runs/{id}")
    public ApiResponse<MatchReport> matchResult(@PathVariable long id, HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.match(id, owner(servletRequest)));
    }

    @PostMapping("/learning-plans")
    public ApiResponse<LearningPlan> learningPlan(@Valid @RequestBody LearningPlanRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.learningPlan(request, owner(servletRequest)));
    }

    @PostMapping("/verification-submissions")
    public ApiResponse<VerificationSubmission> verification(@Valid @RequestBody VerificationSubmissionRequest request,
                                                              HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.submitVerification(request, owner(servletRequest)));
    }

    @PostMapping("/admin/verification-submissions/{id}/reviews")
    public ApiResponse<VerificationSubmission> reviewVerification(
            @PathVariable long id,
            @Valid @RequestBody VerificationReviewRequest request,
            HttpServletRequest servletRequest
    ) {
        String reviewer = servletRequest.getUserPrincipal() == null ? "professional-reviewer" : servletRequest.getUserPrincipal().getName();
        return ApiResponse.success(careerService.reviewVerification(id, request.decision(), request.reason(), reviewer));
    }

    @GetMapping("/admin/verification-submissions")
    public ApiResponse<VerificationSubmissionPage> verificationSubmissions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(careerService.verificationSubmissions(status, page, size));
    }

    @PostMapping("/diagnosis-comparisons")
    public ApiResponse<DiagnosisComparison> comparison(@Valid @RequestBody DiagnosisComparisonRequest request,
                                                        HttpServletRequest servletRequest) {
        return ApiResponse.success(careerService.compareDiagnosis(request.beforeMatchRunId(), request.verificationSubmissionId(), owner(servletRequest)));
    }

    private String owner(HttpServletRequest request) {
        Object owner = request.getAttribute(AnonymousSessionFilter.ATTRIBUTE);
        return owner == null ? "UNKNOWN_SESSION" : owner.toString();
    }
}
