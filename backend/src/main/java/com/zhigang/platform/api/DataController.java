package com.zhigang.platform.api;

import com.zhigang.platform.api.Requests.DataImportRequest;
import com.zhigang.platform.api.Requests.DirectoryScanRequest;
import com.zhigang.platform.api.Requests.ProcessingRunRequest;
import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.domain.PlatformModels.QualityReport;
import com.zhigang.platform.quality.DataQualityService;
import com.zhigang.platform.repository.PlatformDataProvider;
import com.zhigang.platform.source.ProcessingService;
import com.zhigang.platform.source.ProcessingService.ImportResult;
import com.zhigang.platform.source.ProcessingService.ProcessingRun;
import com.zhigang.platform.source.AuthorizedDirectoryConnector;
import com.zhigang.platform.source.AuthorizedDirectoryConnector.ConnectorStatus;
import com.zhigang.platform.source.AuthorizedDirectoryConnector.DirectoryScanResult;
import com.zhigang.platform.source.DataImportFileService;
import com.zhigang.platform.source.AuthorizedFeedConnector;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordQuery;
import com.zhigang.platform.source.StructuredJdRepository;
import com.zhigang.platform.source.AuthorizedFeedConnector.FeedScanResult;
import com.zhigang.platform.source.AuthorizedFeedConnector.FeedStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

@Tag(name = "数据与任务")
@RestController
@RequestMapping("/api")
public class DataController {
    private final ProcessingService processingService;
    private final DataQualityService qualityService;
    private final AuthorizedDirectoryConnector directoryConnector;
    private final DataImportFileService importFileService;
    private final AuthorizedFeedConnector feedConnector;
    private final PlatformDataProvider dataProvider;
    private final StructuredJdRepository structuredJdRepository;

    public DataController(ProcessingService processingService, DataQualityService qualityService,
                          AuthorizedDirectoryConnector directoryConnector, DataImportFileService importFileService,
                          AuthorizedFeedConnector feedConnector, PlatformDataProvider dataProvider,
                          StructuredJdRepository structuredJdRepository) {
        this.processingService = processingService;
        this.qualityService = qualityService;
        this.directoryConnector = directoryConnector;
        this.importFileService = importFileService;
        this.feedConnector = feedConnector;
        this.dataProvider = dataProvider;
        this.structuredJdRepository = structuredJdRepository;
    }

    @GetMapping("/runtime-status")
    public ApiResponse<RuntimeStatus> runtimeStatus() {
        var dynamicRecords = structuredJdRepository.findDynamicRecords(
                new DynamicRecordQuery(null, null, null, null, null, false, 1, 1));
        var snapshot = structuredJdRepository.findCurrentRealSnapshot();
        long pendingSkills = structuredJdRepository.findCandidateSkills().stream()
                .filter(skill -> "PENDING_REVIEW".equals(skill.reviewDecision())
                        || "MIXED".equals(skill.reviewDecision()))
                .count();
        String dataMode = "DEMO".equals(dataProvider.dataMode()) ? "DEMO"
                : snapshot.documentCount() > 0 ? "REAL"
                : dynamicRecords.total() > 0 ? "REAL_PENDING" : "NO_DATA";
        return ApiResponse.success(new RuntimeStatus(dataMode, dataProvider.operatingMode(),
                dynamicRecords.total(), snapshot.documentCount(), pendingSkills,
                snapshot.snapshotCode().isBlank() ? null : snapshot.snapshotCode()));
    }

    @Operation(summary = "导入多源文本数据")
    @PostMapping("/data-imports")
    public ApiResponse<ImportResult> importData(@Valid @RequestBody DataImportRequest request) {
        return ApiResponse.success(processingService.importData(request));
    }

    @Operation(summary = "导入 CSV/JSON 岗位数据")
    @PostMapping(value = "/data-imports/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportResult> importFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam String sourceType,
            @RequestParam String sourceName,
            @RequestParam String licenseStatus
    ) {
        return ApiResponse.success(processingService.importData(importFileService.parse(file, sourceType, sourceName, licenseStatus)));
    }

    @PostMapping("/data-imports/directory/scan")
    public ApiResponse<DirectoryScanResult> scanDirectory(@Valid @RequestBody DirectoryScanRequest request) {
        return ApiResponse.success(directoryConnector.scan(request.sourceName(), request.licenseStatus()));
    }

    @GetMapping("/data-imports/directory/status")
    public ApiResponse<ConnectorStatus> directoryStatus() {
        return ApiResponse.success(directoryConnector.status());
    }

    @PostMapping("/data-imports/rss/scan")
    public ApiResponse<FeedScanResult> scanFeed() {
        return ApiResponse.success(feedConnector.scan());
    }

    @GetMapping("/data-imports/rss/status")
    public ApiResponse<FeedStatus> feedStatus() {
        return ApiResponse.success(feedConnector.status());
    }

    @Operation(summary = "启动处理任务")
    @PostMapping("/processing-runs")
    public ApiResponse<ProcessingRun> start(@Valid @RequestBody ProcessingRunRequest request) {
        return ApiResponse.success(processingService.start(request));
    }

    @GetMapping("/processing-runs/{id}")
    public ApiResponse<ProcessingRun> get(@PathVariable String id) {
        return ApiResponse.success(processingService.get(id));
    }

    @PostMapping("/processing-runs/{id}/retries")
    public ApiResponse<ProcessingRun> retry(@PathVariable String id) {
        return ApiResponse.success(processingService.retry(id));
    }

    @GetMapping("/dataset-snapshots/{id}/quality-report")
    public ApiResponse<QualityReport> quality(@PathVariable String id) {
        return ApiResponse.success(qualityService.buildReport(id));
    }

    public record RuntimeStatus(
            String dataMode,
            String operatingMode,
            long dynamicRecordCount,
            int publishedRealDocumentCount,
            long pendingCandidateSkillCount,
            String snapshotCode
    ) {
    }
}
