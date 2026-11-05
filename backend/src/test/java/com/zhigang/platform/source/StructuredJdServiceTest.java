package com.zhigang.platform.source;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.DynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.Facts;
import com.zhigang.platform.source.StructuredJdModels.ProcessingPolicy;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionPublishCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.Salary;
import com.zhigang.platform.source.StructuredJdModels.Source;
import com.zhigang.platform.source.StructuredJdModels.SourceWorkbook;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdBatch;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdDocument;
import com.zhigang.platform.source.StructuredJdModels.StructuredResponsibilityFact;
import com.zhigang.platform.source.StructuredJdModels.StructuredSkillFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredJdServiceTest {
    private StructuredJdService service;

    @BeforeEach
    void setUp() {
        service = new StructuredJdService(new InMemoryStructuredJdRepository(), "jd-pilot-v1.1");
    }

    @Test
    void validatesImportsAndKeepsRepeatedFilesIdempotent() {
        StructuredJdBatch batch = batch(false);

        var first = service.importBatch(batch);
        var repeated = service.importBatch(batch);

        assertThat(first.acceptedCount()).isEqualTo(1);
        assertThat(first.quarantinedCount()).isZero();
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.acceptedCount()).isEqualTo(1);
        assertThat(repeated.duplicateCount()).isZero();
        assertThat(service.sourceLedger()).singleElement().extracting(item -> item.batchCount()).isEqualTo(1);
        assertThat(service.records(first.id(), null, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void filtersExactContentDuplicatesAcrossDifferentSourceRecordKeys() {
        StructuredJdBatch valid = batch(false);
        StructuredJdDocument first = valid.documents().get(0);
        StructuredJdDocument duplicate = new StructuredJdDocument(
                "TEST-JD-002", first.jobTitle(), first.company(), first.city(), first.salary(),
                first.experienceRequirement(), first.educationRequirement(), first.publishedAt(),
                first.collectedAt(), first.timeScope(), first.observedAt(), first.source(), first.rawText(),
                first.rawTextLength(), first.contentHash(), first.dataMode(), first.processingStatus(),
                first.statusReasons(), first.facts());
        StructuredJdBatch duplicateBatch = new StructuredJdBatch(
                valid.schemaVersion(), valid.generatedAt(), "exact-duplicate-batch",
                new SourceWorkbook("exact-duplicates.json", "c".repeat(64), 2,
                        first.rawTextLength() * 2),
                valid.processingPolicy(), List.of(first, duplicate));

        var result = service.importBatch(duplicateBatch);

        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(service.records(result.id(), null, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void rejectsEvidenceThatCannotReturnToOriginalText() {
        assertThatThrownBy(() -> service.importBatch(batch(true)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("STRUCTURED_JD_EVIDENCE_OFFSET_INVALID");
    }

    @Test
    void rejectsMissingSalaryMetadataBeforePersistence() {
        StructuredJdBatch valid = batch(false);
        StructuredJdDocument document = valid.documents().get(0);
        StructuredJdDocument invalid = new StructuredJdDocument(
                document.sourceRecordKey(), document.jobTitle(), document.company(), document.city(), null,
                document.experienceRequirement(), document.educationRequirement(), document.publishedAt(),
                document.collectedAt(), document.source(), document.rawText(), document.rawTextLength(),
                document.contentHash(), document.dataMode(), document.processingStatus(),
                document.statusReasons(), document.facts());
        StructuredJdBatch batch = new StructuredJdBatch(valid.schemaVersion(), valid.generatedAt(),
                valid.sourceWorkbook(), valid.processingPolicy(), List.of(invalid));

        assertThatThrownBy(() -> service.importBatch(batch))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("STRUCTURED_JD_SALARY_MISSING");
    }

    @Test
    void rejectsSourcesWithoutAConcreteLicenseNote() {
        StructuredJdBatch valid = batch(false);
        StructuredJdDocument document = valid.documents().get(0);
        Source source = new Source("authorized-source", "公开招聘参考", "TIER_2", "STRUCTURED_DATASET",
                "RECRUITMENT_JD", "DOCUMENTED_PERMISSION", "TODO",
                document.source().originalUrl(), document.source().canonicalUrl());
        StructuredJdDocument invalid = new StructuredJdDocument(
                document.sourceRecordKey(), document.jobTitle(), document.company(), document.city(), document.salary(),
                document.experienceRequirement(), document.educationRequirement(), document.publishedAt(),
                document.collectedAt(), "CURRENT", OffsetDateTime.parse("2026-07-24T10:00:00+08:00"), source,
                document.rawText(), document.rawTextLength(), document.contentHash(), document.dataMode(),
                document.processingStatus(), document.statusReasons(), document.facts());
        StructuredJdBatch invalidBatch = new StructuredJdBatch(valid.schemaVersion(), valid.generatedAt(),
                "missing-license-note", valid.sourceWorkbook(), valid.processingPolicy(), List.of(invalid));

        assertThatThrownBy(() -> service.importBatch(invalidBatch))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("STRUCTURED_JD_LICENSE_NOTE_MISSING");
    }

    @Test
    void isolatesUnknownTimeRecordsAndReportsThemInTheSourceLedger() {
        StructuredJdBatch valid = batch(false);
        StructuredJdDocument document = valid.documents().get(0);
        StructuredJdDocument unknownTime = new StructuredJdDocument(
                document.sourceRecordKey(), document.jobTitle(), document.company(), document.city(), document.salary(),
                document.experienceRequirement(), document.educationRequirement(), document.publishedAt(),
                document.collectedAt(), "UNKNOWN_TIME", OffsetDateTime.parse("2026-07-24T10:00:00+08:00"),
                document.source(), document.rawText(), document.rawTextLength(), document.contentHash(),
                document.dataMode(), document.processingStatus(), document.statusReasons(), document.facts());
        StructuredJdBatch unknownBatch = new StructuredJdBatch(valid.schemaVersion(), valid.generatedAt(),
                "unknown-time-batch", valid.sourceWorkbook(), valid.processingPolicy(), List.of(unknownTime));

        var result = service.importBatch(unknownBatch);

        assertThat(result.quarantinedCount()).isEqualTo(1);
        assertThat(service.sourceLedger()).singleElement().satisfies(item -> {
            assertThat(item.timeScope()).isEqualTo("UNKNOWN_TIME");
            assertThat(item.exceptionCount()).isEqualTo(1);
        });
    }

    @Test
    void rejectsReusingABatchKeyForDifferentSourceContent() {
        StructuredJdBatch valid = batch(false);
        StructuredJdBatch first = new StructuredJdBatch(valid.schemaVersion(), valid.generatedAt(), "stable-batch-key",
                valid.sourceWorkbook(), valid.processingPolicy(), valid.documents());
        StructuredJdBatch conflicting = new StructuredJdBatch(valid.schemaVersion(), valid.generatedAt(), "stable-batch-key",
                new SourceWorkbook("另一批.json", "b".repeat(64), 1,
                        valid.sourceWorkbook().rawTextCharacterCount()), valid.processingPolicy(), valid.documents());
        service.importBatch(first);

        assertThatThrownBy(() -> service.importBatch(conflicting))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("STRUCTURED_JD_BATCH_KEY_CONFLICT");
    }

    @Test
    void requiresCandidateAndDocumentReviewBeforeAtomicPromotion() {
        var imported = service.importBatch(batch(false));
        long recordId = service.records(imported.id(), null, 1, 20).items().get(0).id();
        service.reviewRecord(recordId, new DynamicReviewCommand(
                "APPROVE", "研究员甲", "原文和标准事实已核对", List.of()));

        assertThatThrownBy(() -> service.createPromotionBatch(
                new PromotionBatchCommand(List.of(recordId), "研究员甲")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("候选技能");

        service.reviewCandidateSkill("MyBatis", new CandidateSkillReviewCommand(
                "APPROVED_STANDARD", null, "领域审核员", "Java 数据访问核心技能"));
        var promotion = service.createPromotionBatch(
                new PromotionBatchCommand(List.of(recordId), "研究员甲"));

        assertThatThrownBy(() -> service.reviewPromotionBatch(promotion.id(),
                new PromotionReviewCommand("APPROVE", "研究员甲", "自审")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能审核");

        service.reviewPromotionBatch(promotion.id(),
                new PromotionReviewCommand("APPROVE", "审核员乙", "同意晋级"));
        var published = service.publishPromotionBatch(promotion.id(),
                new PromotionPublishCommand("发布员丙"));
        var snapshot = service.currentRealSnapshot();

        assertThat(published.status()).isEqualTo("COMPLETED");
        assertThat(snapshot.dataMode()).isEqualTo("REAL");
        assertThat(snapshot.documentCount()).isEqualTo(1);
        assertThat(snapshot.roles()).singleElement().satisfies(role ->
                assertThat(role.requiredSkills()).extracting("skill").contains("Java", "MyBatis"));
    }

    private StructuredJdBatch batch(boolean invalidOffset) {
        String rawText = "岗位职责\n熟练掌握 Java 和 MyBatis，负责接口开发与数据库访问，"
                + "完成单元测试、代码评审、接口文档和上线后的故障排查工作。"
                + "能够参与需求分析、技术方案设计、性能优化、服务监控和团队协作，并对交付质量负责。";
        int javaStart = rawText.indexOf("Java");
        int mybatisStart = rawText.indexOf("MyBatis");
        int responsibilityStart = rawText.indexOf("负责接口开发");
        StructuredSkillFact java = new StructuredSkillFact(
                "STANDARD", "Java", null, "Java", "REQUIRED",
                invalidOffset ? javaStart + 1 : javaStart, javaStart + 4,
                "熟练掌握 Java 和 MyBatis", 0.99, true);
        StructuredSkillFact mybatis = new StructuredSkillFact(
                "CANDIDATE", "MyBatis", "Java 数据访问", "MyBatis", "REQUIRED",
                mybatisStart, mybatisStart + 7, "熟练掌握 Java 和 MyBatis", 0.88, true);
        StructuredResponsibilityFact responsibility = new StructuredResponsibilityFact(
                "负责接口开发与数据库访问", "负责接口开发与数据库访问",
                responsibilityStart, responsibilityStart + "负责接口开发与数据库访问".length(),
                "负责接口开发与数据库访问", 0.94, true);
        StructuredJdDocument document = new StructuredJdDocument(
                "TEST-JD-001", "JAVA开发", "测试企业", "南京",
                new Salary("15-20K/月", new BigDecimal("15000"), new BigDecimal("20000"),
                        new BigDecimal("12"), "CNY"),
                "1-3年", "本科", null, LocalDate.of(2026, 7, 23),
                new Source("公开招聘参考", "RECRUITMENT_JD", "MANUAL_PUBLIC_REFERENCE",
                        "https://example.com/jobs/1?from=list", "https://example.com/jobs/1"),
                rawText, rawText.length(), sha256(rawText), "REAL", "ELIGIBLE",
                List.of("未披露发布时间"), new Facts(List.of(java, mybatis), List.of(responsibility)));
        return new StructuredJdBatch("1.0.0", OffsetDateTime.parse("2026-07-24T10:12:33+08:00"),
                new SourceWorkbook("岗位JD.xlsx", "a".repeat(64), 1, rawText.length()),
                new ProcessingPolicy("jd-pilot-v1.1",
                        "DICTIONARY_CLAUSE_CONTEXT_RULE_WITH_EVIDENCE_VALIDATION"),
                List.of(document));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
