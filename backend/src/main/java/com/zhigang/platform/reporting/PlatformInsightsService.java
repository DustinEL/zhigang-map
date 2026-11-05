package com.zhigang.platform.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.domain.PlatformModels.CrawlerTaskSummary;
import com.zhigang.platform.domain.PlatformModels.EvaluationDatasetSummary;
import com.zhigang.platform.domain.PlatformModels.EvaluationErrorSummary;
import com.zhigang.platform.domain.PlatformModels.EvaluationMetric;
import com.zhigang.platform.domain.PlatformModels.EvaluationSummary;
import com.zhigang.platform.domain.PlatformModels.PromotionSummary;
import com.zhigang.platform.domain.PlatformModels.RepositoryLibrary;
import com.zhigang.platform.domain.PlatformModels.RepositoryOverview;
import com.zhigang.platform.domain.PlatformModels.RepositoryPipelineStep;
import com.zhigang.platform.repository.PlatformDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PlatformInsightsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformInsightsService.class);
    private static final int TARGET_SAMPLE_COUNT = 100;

    private final PlatformDataProvider dataProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${zhigang.evaluation.report-path:}")
    private String configuredReportPath;

    public PlatformInsightsService(PlatformDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    public RepositoryOverview repositoryOverview() {
        int baseCount = dataProvider.findDocuments().size();
        String mode = dataProvider.dataMode();
        LOGGER.debug("repositoryOverview dataMode={}", mode);
        boolean demo = "DEMO".equals(mode);
        int dynamicCount = demo ? 128 : 0;
        return new RepositoryOverview(
                new RepositoryLibrary("基础资料库", baseCount, "FROZEN", "已审核、可追溯、可用于正式分析"),
                new RepositoryLibrary("动态情报库", dynamicCount, demo ? "DEMO_PIPELINE" : "AWAITING_SOURCE",
                        "定期采集、待质检、不能直接覆盖基础知识"),
                List.of(
                        new RepositoryPipelineStep("定期采集", dynamicCount, "blue"),
                        new RepositoryPipelineStep("格式与合规", demo ? 121 : 0, "cyan"),
                        new RepositoryPipelineStep("去重与时滞", demo ? 104 : 0, "violet"),
                        new RepositoryPipelineStep("证据化抽取", demo ? 96 : 0, "amber"),
                        new RepositoryPipelineStep("专家审核", demo ? 32 : 0, "green"),
                        new RepositoryPipelineStep("晋级基础库", demo ? 28 : 0, "teal")
                ),
                List.of(
                        task("授权招聘快照", "每日 02:00", "parser-v1.3", demo ? 72 : 0, demo),
                        task("行业趋势报告", "每周一 03:00", "pdf-v1.1", demo ? 31 : 0, demo),
                        task("学习资源目录", "每周五 04:00", "resource-v1.0", demo ? 25 : 0, demo)
                ),
                new PromotionSummary("PROMOTION-2026-07", demo ? 32 : 0, demo ? 28 : 0,
                        demo ? 3 : 0, demo ? 1 : 0, "v1.0.0"),
                demo
        );
    }

    private CrawlerTaskSummary task(String name, String schedule, String parserVersion, int count, boolean demo) {
        return new CrawlerTaskSummary(name, schedule, parserVersion, count, demo ? "DEMO" : "NOT_CONFIGURED");
    }

    public EvaluationSummary evaluationSummary() {
        Path reportPath = resolveReportPath();
        if (reportPath != null && Files.isRegularFile(reportPath)) {
            try {
                return readEvaluationSummary(reportPath);
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("无法读取评测报告 {}", reportPath, exception);
            }
        }
        return pendingEvaluationSummary();
    }

    private Path resolveReportPath() {
        if (configuredReportPath != null && !configuredReportPath.isBlank()) {
            return Path.of(configuredReportPath).normalize();
        }
        Path localPath = Path.of("..", "test-data", "evaluation", "evaluation-report.json").normalize();
        return Files.isRegularFile(localPath) ? localPath : null;
    }

    private EvaluationSummary readEvaluationSummary(Path reportPath) throws IOException {
        JsonNode report = objectMapper.readTree(reportPath.toFile());
        Map<String, String> colors = Map.of(
                "JD_EXTRACTION", "#16835f",
                "RESUME_EXTRACTION", "#2d718f",
                "JOB_MATCH", "#7f6aa1"
        );
        Map<String, String> notes = Map.of(
                "JD_EXTRACTION", "岗位、职责、技能与要求类型",
                "RESUME_EXTRACTION", "教育、项目、技能与证据片段",
                "JOB_MATCH", "岗位对、申请状态与差距项"
        );
        List<EvaluationMetric> metrics = new ArrayList<>();
        List<EvaluationDatasetSummary> datasets = new ArrayList<>();
        List<EvaluationErrorSummary> errors = new ArrayList<>();
        for (JsonNode metric : report.path("metrics")) {
            String code = metric.path("code").asText();
            int sampleCount = metric.path("sampleCount").asInt();
            int targetSamples = metric.path("targetSamples").asInt(TARGET_SAMPLE_COUNT);
            Double accuracy = numberOrNull(metric, "accuracy");
            String status = metricStatus(sampleCount, targetSamples, metric.path("passed").asBoolean(false));
            metrics.add(new EvaluationMetric(code, metric.path("name").asText(code), accuracy,
                    report.path("targetAccuracy").asDouble(90), sampleCount, targetSamples,
                    numberOrNull(metric, "precision"), numberOrNull(metric, "recall"), numberOrNull(metric, "f1"),
                    status, colors.getOrDefault(code, "#2d718f"), notes.getOrDefault(code, "正式冻结测试集")));
            datasets.add(dataset(code, sampleCount, targetSamples, metric.path("goldSha256").isTextual()));
            int errorCount = metric.path("errors").size();
            if (errorCount > 0) {
                String share = sampleCount == 0 ? "0%" : Math.round(errorCount * 100.0 / sampleCount) + "%";
                errors.add(new EvaluationErrorSummary(metric.path("name").asText(code), errorCount, share,
                        "查看评测报告中的逐条缺失与误报"));
            }
        }
        boolean official = report.path("official").asBoolean(false);
        return new EvaluationSummary(metrics, datasets, errors,
                report.path("algorithmVersion").asText("unknown"),
                report.path("promptVersion").asText("unknown"), !official);
    }

    private String metricStatus(int sampleCount, int targetSamples, boolean passed) {
        if (sampleCount == 0) {
            return "AWAITING_DATA";
        }
        if (sampleCount < targetSamples) {
            return "INSUFFICIENT_SAMPLES";
        }
        return passed ? "VERIFIED_PASS" : "VERIFIED_FAIL";
    }

    private EvaluationDatasetSummary dataset(String code, int sampleCount, int targetSamples, boolean hasHash) {
        String name = switch (code) {
            case "JD_EXTRACTION" -> "JD-GOLD-2026.08";
            case "RESUME_EXTRACTION" -> "RESUME-GOLD-2026.08";
            default -> "MATCH-GOLD-2026.08";
        };
        String type = switch (code) {
            case "JD_EXTRACTION" -> "岗位抽取黄金集";
            case "RESUME_EXTRACTION" -> "简历解析黄金集";
            default -> "人岗匹配专家集";
        };
        boolean frozen = sampleCount >= targetSamples && hasHash;
        return new EvaluationDatasetSummary(name, type, sampleCount, targetSamples, "v1.0",
                frozen ? "FROZEN" : "AWAITING_ANNOTATION");
    }

    private Double numberOrNull(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }

    private EvaluationSummary pendingEvaluationSummary() {
        return new EvaluationSummary(
                List.of(
                        pendingMetric("JD_EXTRACTION", "JD 解析准确率", "#16835f", "岗位、职责、技能与要求类型"),
                        pendingMetric("RESUME_EXTRACTION", "简历提取准确率", "#2d718f", "教育、项目、技能与证据片段"),
                        pendingMetric("JOB_MATCH", "人岗匹配准确率", "#7f6aa1", "专家标注岗位对与差距项")
                ),
                List.of(
                        new EvaluationDatasetSummary("JD-GOLD-2026.08", "岗位抽取黄金集", 0, TARGET_SAMPLE_COUNT, "v1.0", "AWAITING_ANNOTATION"),
                        new EvaluationDatasetSummary("RESUME-GOLD-2026.08", "简历解析黄金集", 0, TARGET_SAMPLE_COUNT, "v1.0", "AWAITING_ANNOTATION"),
                        new EvaluationDatasetSummary("MATCH-GOLD-2026.08", "人岗匹配验证集", 0, TARGET_SAMPLE_COUNT, "v1.0", "AWAITING_ANNOTATION")
                ),
                List.of(), "algorithm-v1.2.0", "prompt-v1.0.3", true
        );
    }

    private EvaluationMetric pendingMetric(String code, String name, String color, String note) {
        return new EvaluationMetric(code, name, null, 90, 0, TARGET_SAMPLE_COUNT,
                null, null, null, "AWAITING_DATA", color, note);
    }
}
