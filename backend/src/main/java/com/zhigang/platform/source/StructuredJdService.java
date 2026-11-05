package com.zhigang.platform.source;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillSummary;
import com.zhigang.platform.source.StructuredJdModels.BulkCandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.BulkDynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordQuery;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordDetail;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordPage;
import com.zhigang.platform.source.StructuredJdModels.DynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.Facts;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchView;
import com.zhigang.platform.source.StructuredJdModels.PromotionPublishCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.QualityIssueDraft;
import com.zhigang.platform.source.StructuredJdModels.RealSnapshotView;
import com.zhigang.platform.source.StructuredJdModels.ReviewQueueSummary;
import com.zhigang.platform.source.StructuredJdModels.StructuredImportResult;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdBatch;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdDocument;
import com.zhigang.platform.source.StructuredJdModels.SourceLedgerItem;
import com.zhigang.platform.source.StructuredJdModels.StructuredResponsibilityFact;
import com.zhigang.platform.source.StructuredJdModels.StructuredSkillFact;
import com.zhigang.platform.source.StructuredJdModels.ValidatedDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class StructuredJdService {
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final Set<String> REQUIREMENT_TYPES = Set.of("REQUIRED", "BONUS", "MENTIONED", "NEGATED");
    private static final Set<String> DOCUMENT_STATUSES = Set.of("ELIGIBLE", "QUARANTINED");
    private static final Set<String> TIME_SCOPES = Set.of("CURRENT", "HISTORICAL", "UNKNOWN_TIME");
    private static final Set<String> SOURCE_TIERS = Set.of("TIER_1", "TIER_2", "TIER_3");
    private static final Set<String> SOURCE_ADAPTERS = Set.of(
            "MANUAL_BATCH", "STRUCTURED_DATASET", "OFFICIAL_ATS", "PUBLIC_EMPLOYMENT", "ENTERPRISE_CAREERS");
    private static final Set<String> RECORD_REVIEW_DECISIONS = Set.of("APPROVE", "QUARANTINE", "REJECT");
    private static final Set<String> SKILL_REVIEW_DECISIONS =
            Set.of("APPROVED_STANDARD", "APPROVED_ALIAS", "OBSERVING", "REJECTED_NOISE");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern BATCH_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{2,54}");
    private static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{2,63}");
    private static final Pattern LICENSE_PLACEHOLDER = Pattern.compile(
            "REPLACE|UNKNOWN|UNCONFIRMED|TODO|待补|未确认", Pattern.CASE_INSENSITIVE);
    private static final Pattern INJECTION = Pattern.compile(
            "忽略(?:前述|以上|之前)|系统提示|泄露.*提示|ignore\\s+previous", Pattern.CASE_INSENSITIVE);

    private final StructuredJdRepository repository;
    private final String policyVersion;
    private final ReviewTriageService triageService;

    @Autowired
    public StructuredJdService(StructuredJdRepository repository,
                               @Value("${zhigang.structured-jd.policy-version:jd-pilot-v1.1}") String policyVersion,
                               ReviewTriageService triageService) {
        this.repository = repository;
        this.policyVersion = policyVersion;
        this.triageService = triageService;
    }

    StructuredJdService(StructuredJdRepository repository, String policyVersion) {
        this(repository, policyVersion,
                new ReviewTriageService(repository, "jd-triage-v2.0", 0.95, 0.85, 0.10));
    }

    public StructuredImportResult importBatch(StructuredJdBatch batch) {
        require(batch != null, "STRUCTURED_JD_EMPTY", "结构化 JD 内容不能为空");
        require(SCHEMA_VERSION.equals(batch.schemaVersion()), "STRUCTURED_JD_SCHEMA_UNSUPPORTED",
                "仅支持 schemaVersion " + SCHEMA_VERSION);
        require(batch.sourceWorkbook() != null, "STRUCTURED_JD_WORKBOOK_MISSING", "缺少源工作簿信息");
        require(batch.processingPolicy() != null, "STRUCTURED_JD_POLICY_MISSING", "缺少处理策略信息");
        require(BATCH_KEY.matcher(text(batch.batchKey())).matches(),
                "STRUCTURED_JD_BATCH_KEY_INVALID", "batchKey 必须为 3–55 位字母、数字或 ._:-，且首位为字母或数字");
        require(!text(batch.sourceWorkbook().fileName()).isBlank(),
                "STRUCTURED_JD_WORKBOOK_NAME_MISSING", "源工作簿名称不能为空");
        require(!text(batch.processingPolicy().version()).isBlank(),
                "STRUCTURED_JD_POLICY_VERSION_MISSING", "处理策略版本不能为空");
        require(batch.documents() != null && !batch.documents().isEmpty(), "STRUCTURED_JD_DOCUMENTS_EMPTY", "JD 记录不能为空");
        require(batch.documents().size() <= 500, "STRUCTURED_JD_BATCH_TOO_LARGE", "单批最多导入 500 条 JD");
        require(SHA256.matcher(text(batch.sourceWorkbook().sha256())).matches(),
                "STRUCTURED_JD_WORKBOOK_HASH_INVALID", "源工作簿 SHA-256 格式错误");
        require(batch.sourceWorkbook().recordCount() == batch.documents().size(),
                "STRUCTURED_JD_RECORD_COUNT_MISMATCH", "记录数与工作簿声明不一致");
        int characterCount = batch.documents().stream().mapToInt(item -> text(item.rawText()).length()).sum();
        require(batch.sourceWorkbook().rawTextCharacterCount() == characterCount,
                "STRUCTURED_JD_CHARACTER_COUNT_MISMATCH", "JD 原文字符数与工作簿声明不一致");

        Set<String> recordKeys = new HashSet<>();
        Set<String> sourceIds = new HashSet<>();
        Set<String> timeScopes = new HashSet<>();
        List<ValidatedDocument> validated = new ArrayList<>(batch.documents().size());
        for (StructuredJdDocument document : batch.documents()) {
            require(recordKeys.add(text(document.sourceRecordKey())), "STRUCTURED_JD_DUPLICATE_RECORD_KEY",
                    "批次内存在重复 sourceRecordKey：" + document.sourceRecordKey());
            validated.add(validateDocument(document));
            sourceIds.add(document.source().sourceId());
            timeScopes.add(document.timeScope());
        }
        require(sourceIds.size() == 1, "STRUCTURED_JD_BATCH_SOURCE_MIXED", "同一批次只能包含一个 sourceId");
        require(timeScopes.size() == 1, "STRUCTURED_JD_BATCH_TIME_SCOPE_MIXED", "同一批次只能包含一个 timeScope");
        addNearDuplicateWarnings(validated);
        var existing = repository.findImportByBatchKey(batch.batchKey());
        if (existing.isPresent()) {
            require(existing.get().sourceFileHash().equalsIgnoreCase(batch.sourceWorkbook().sha256()),
                    "STRUCTURED_JD_BATCH_KEY_CONFLICT", "batchKey 已用于另一份源文件，不能覆盖原批次");
            return existing.get();
        }
        StructuredImportResult result = repository.saveImport(batch, validated);
        triageService.retriage(result.id());
        return repository.findImport(result.id());
    }

    public StructuredImportResult getImport(long id) {
        return repository.findImport(id);
    }

    public DynamicRecordPage records(Long importId, String status, String keyword, String riskLevel,
                                     String reviewRoute, boolean onlyExceptions, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return repository.findDynamicRecords(new DynamicRecordQuery(importId, blankToNull(status),
                blankToNull(keyword), blankToNull(riskLevel), blankToNull(reviewRoute), onlyExceptions,
                safePage, safeSize));
    }

    public DynamicRecordPage records(Long importId, String status, int page, int size) {
        return records(importId, status, null, null, null, false, page, size);
    }

    public DynamicRecordDetail record(long id) {
        return repository.findDynamicRecord(id);
    }

    public DynamicRecordDetail reviewRecord(long id, DynamicReviewCommand command) {
        require(command != null && RECORD_REVIEW_DECISIONS.contains(text(command.decision()).toUpperCase(Locale.ROOT)),
                "REVIEW_DECISION_INVALID", "审核决定必须为 APPROVE、QUARANTINE 或 REJECT");
        require(!text(command.reviewer()).isBlank(), "REVIEWER_REQUIRED", "审核人不能为空");
        if (command.factReviews() != null) {
            command.factReviews().forEach(item -> require(
                    SKILL_REVIEW_DECISIONS.contains(text(item.decision()).toUpperCase(Locale.ROOT)),
                    "FACT_REVIEW_DECISION_INVALID", "候选技能审核决定不合法"));
        }
        DynamicRecordDetail reviewed = repository.reviewDynamicRecord(id, command, policyVersion);
        triageService.retriage(reviewed.summary().importId());
        return repository.findDynamicRecord(id);
    }

    public List<DynamicRecordDetail> bulkReviewRecords(BulkDynamicReviewCommand command) {
        require(command != null && command.recordIds() != null && !command.recordIds().isEmpty(),
                "BULK_REVIEW_RECORDS_EMPTY", "至少选择一条待审核记录");
        require(command.recordIds().size() <= 500, "BULK_REVIEW_TOO_LARGE", "单次最多审核 500 条记录");
        require(RECORD_REVIEW_DECISIONS.contains(text(command.decision()).toUpperCase(Locale.ROOT)),
                "REVIEW_DECISION_INVALID", "审核决定必须为 APPROVE、QUARANTINE 或 REJECT");
        require(!text(command.reviewer()).isBlank(), "REVIEWER_REQUIRED", "审核人不能为空");
        require(!text(command.reason()).isBlank(), "REVIEW_REASON_REQUIRED", "批量审核说明不能为空");
        List<DynamicRecordDetail> reviewed = repository.bulkReviewDynamicRecords(command, policyVersion);
        reviewed.stream().map(item -> item.summary().importId()).distinct().forEach(triageService::retriage);
        return command.recordIds().stream().distinct().map(repository::findDynamicRecord).toList();
    }

    public List<CandidateSkillSummary> candidateSkills() {
        return repository.findCandidateSkills();
    }

    public List<CandidateSkillSummary> reviewCandidateSkill(String skill, CandidateSkillReviewCommand command) {
        require(!text(skill).isBlank(), "CANDIDATE_SKILL_REQUIRED", "候选技能不能为空");
        require(command != null && SKILL_REVIEW_DECISIONS.contains(text(command.decision()).toUpperCase(Locale.ROOT)),
                "CANDIDATE_SKILL_DECISION_INVALID", "候选技能审核决定不合法");
        require(!text(command.reviewer()).isBlank(), "REVIEWER_REQUIRED", "审核人不能为空");
        if ("APPROVED_ALIAS".equals(command.decision())) {
            require(!text(command.correctedValue()).isBlank(), "ALIAS_TARGET_REQUIRED", "作为别名时必须填写目标标准技能");
        }
        List<CandidateSkillSummary> result = repository.reviewCandidateSkill(skill, command);
        triageService.retriage(null);
        return result;
    }

    public List<CandidateSkillSummary> bulkReviewCandidateSkills(BulkCandidateSkillReviewCommand command) {
        require(command != null && command.skills() != null && !command.skills().isEmpty(),
                "CANDIDATE_SKILLS_EMPTY", "至少选择一种候选技能");
        require(command.skills().size() <= 200, "CANDIDATE_SKILLS_TOO_MANY", "单次最多审核 200 种候选技能");
        require(SKILL_REVIEW_DECISIONS.contains(text(command.decision()).toUpperCase(Locale.ROOT)),
                "CANDIDATE_SKILL_DECISION_INVALID", "候选技能审核决定不合法");
        require(!text(command.reviewer()).isBlank(), "REVIEWER_REQUIRED", "审核人不能为空");
        if ("APPROVED_ALIAS".equals(command.decision())) {
            require(!text(command.correctedValue()).isBlank(), "ALIAS_TARGET_REQUIRED", "作为别名时必须填写目标标准技能");
        }
        List<CandidateSkillSummary> result = repository.bulkReviewCandidateSkills(command);
        triageService.retriage(null);
        return result;
    }

    public ReviewQueueSummary retriage(Long importId) {
        return triageService.retriage(importId);
    }

    public ReviewQueueSummary reviewQueueSummary(Long importId) {
        return triageService.summary(importId);
    }

    public PromotionBatchView createPromotionBatch(PromotionBatchCommand command) {
        boolean allEligible = command != null && "ALL_ELIGIBLE".equals(command.selectionMode());
        require(command != null && (allEligible || command.recordIds() != null && !command.recordIds().isEmpty()),
                "PROMOTION_RECORDS_EMPTY", "至少选择一条动态记录，或选择全部可晋级记录");
        require(!text(command.requestedBy()).isBlank(), "PROMOTION_REQUESTER_REQUIRED", "创建人不能为空");
        return repository.createPromotionBatch(command, policyVersion);
    }

    public PromotionBatchView promotionBatch(long id) {
        return repository.findPromotionBatch(id);
    }

    public PromotionBatchView reviewPromotionBatch(long id, PromotionReviewCommand command) {
        require(command != null && Set.of("APPROVE", "REJECT").contains(text(command.decision()).toUpperCase(Locale.ROOT)),
                "PROMOTION_REVIEW_INVALID", "批次审核决定必须为 APPROVE 或 REJECT");
        require(!text(command.reviewer()).isBlank(), "REVIEWER_REQUIRED", "审核人不能为空");
        return repository.reviewPromotionBatch(id, command);
    }

    public PromotionBatchView publishPromotionBatch(long id, PromotionPublishCommand command) {
        require(command != null && !text(command.publisher()).isBlank(),
                "PROMOTION_PUBLISHER_REQUIRED", "发布人不能为空");
        return repository.publishPromotionBatch(id, command);
    }

    public RealSnapshotView currentRealSnapshot() {
        return repository.findCurrentRealSnapshot();
    }

    public List<SourceLedgerItem> sourceLedger() {
        return repository.findSourceLedger();
    }

    private ValidatedDocument validateDocument(StructuredJdDocument document) {
        require(document != null, "STRUCTURED_JD_DOCUMENT_NULL", "JD 记录不能为空");
        require(!text(document.sourceRecordKey()).isBlank(), "STRUCTURED_JD_RECORD_KEY_MISSING", "sourceRecordKey 不能为空");
        require(!text(document.jobTitle()).isBlank(), "STRUCTURED_JD_TITLE_MISSING", "岗位名称不能为空");
        require(!text(document.company()).isBlank(), "STRUCTURED_JD_COMPANY_MISSING", "企业名称不能为空");
        require(!text(document.rawText()).isBlank(), "STRUCTURED_JD_RAW_TEXT_MISSING", "JD 原文不能为空");
        require("REAL".equals(document.dataMode()), "STRUCTURED_JD_DATA_MODE_INVALID", "结构化导入只接受 REAL 数据");
        require(document.source() != null, "STRUCTURED_JD_SOURCE_MISSING", "REAL 数据必须提供来源信息");
        require(SOURCE_ID.matcher(text(document.source().sourceId())).matches(),
                "STRUCTURED_JD_SOURCE_ID_INVALID", "sourceId 必须为 3–64 位稳定标识");
        require(!text(document.source().name()).isBlank() && !text(document.source().sourceType()).isBlank(),
                "STRUCTURED_JD_SOURCE_METADATA_MISSING", "REAL 数据来源名称和类型不能为空");
        require(SOURCE_TIERS.contains(text(document.source().sourceTier()).toUpperCase(Locale.ROOT)),
                "STRUCTURED_JD_SOURCE_TIER_INVALID", "sourceTier 必须为 TIER_1、TIER_2 或 TIER_3");
        require(SOURCE_ADAPTERS.contains(text(document.source().adapter()).toUpperCase(Locale.ROOT)),
                "STRUCTURED_JD_SOURCE_ADAPTER_INVALID", "来源适配器不在允许清单中");
        require(!text(document.source().licenseStatus()).isBlank()
                        && !LICENSE_PLACEHOLDER.matcher(text(document.source().licenseStatus())).find(),
                "STRUCTURED_JD_LICENSE_STATUS_INVALID", "来源许可状态不能为空或使用待确认占位值");
        require(!text(document.source().licenseNote()).isBlank()
                        && !LICENSE_PLACEHOLDER.matcher(text(document.source().licenseNote())).find(),
                "STRUCTURED_JD_LICENSE_NOTE_MISSING", "licenseNote 必须写明授权、开放条款或人工留存依据");
        require(!text(document.source().canonicalUrl()).isBlank(),
                "STRUCTURED_JD_SOURCE_URL_MISSING", "REAL 数据必须提供规范来源链接");
        require(text(document.source().canonicalUrl()).startsWith("https://"),
                "STRUCTURED_JD_SOURCE_URL_INVALID", "规范来源链接必须使用 HTTPS");
        require(TIME_SCOPES.contains(text(document.timeScope()).toUpperCase(Locale.ROOT)),
                "STRUCTURED_JD_TIME_SCOPE_INVALID", "timeScope 必须为 CURRENT、HISTORICAL 或 UNKNOWN_TIME");
        require(document.observedAt() != null,
                "STRUCTURED_JD_OBSERVED_AT_MISSING", "observedAt 不能为空");
        if ("HISTORICAL".equals(document.timeScope())) {
            require(document.publishedAt() != null,
                    "STRUCTURED_JD_HISTORICAL_PUBLISHED_AT_MISSING", "历史数据必须提供明确发布时间");
        }
        require(document.salary() != null, "STRUCTURED_JD_SALARY_MISSING", "缺少薪资结构对象");
        require(document.collectedAt() != null, "STRUCTURED_JD_COLLECTED_AT_MISSING", "采集日期不能为空");
        require(SHA256.matcher(text(document.contentHash())).matches(),
                "STRUCTURED_JD_CONTENT_HASH_INVALID", "JD 内容哈希格式错误");
        require(document.rawTextLength() == document.rawText().length(),
                "STRUCTURED_JD_TEXT_LENGTH_MISMATCH", "JD 原文长度不一致");
        require(document.contentHash().equalsIgnoreCase(sha256(document.rawText())),
                "STRUCTURED_JD_CONTENT_HASH_MISMATCH", "JD 内容哈希与原文不一致");
        require(DOCUMENT_STATUSES.contains(document.processingStatus()),
                "STRUCTURED_JD_STATUS_INVALID", "处理状态必须为 ELIGIBLE 或 QUARANTINED");

        List<QualityIssueDraft> issues = new ArrayList<>();
        if (document.publishedAt() == null) {
            issues.add(new QualityIssueDraft("PUBLISHED_AT_MISSING", "LOW",
                    "未披露发布时间，只能用于当前岗位技能快照。", false));
        }
        if (!"CURRENT".equals(document.timeScope())) {
            issues.add(new QualityIssueDraft("NON_CURRENT_TIME_SCOPE", "HIGH",
                    "历史或时间未知记录仅进入隔离区，不参与当前需求率和自动晋级。", true));
        }
        if (document.rawText().length() < 100) {
            issues.add(new QualityIssueDraft("SHORT_TEXT", "HIGH", "JD 原文少于 100 个字符。", true));
        }
        if (INJECTION.matcher(document.rawText()).find()) {
            issues.add(new QualityIssueDraft("PROMPT_INJECTION", "HIGH",
                    "原文包含疑似操纵模型的指令，已按不可信文本隔离。", true));
        }
        Facts facts = document.facts();
        require(facts != null, "STRUCTURED_JD_FACTS_MISSING", "缺少技能和职责事实");
        List<StructuredSkillFact> skills = facts.skills() == null ? List.of() : facts.skills();
        List<StructuredResponsibilityFact> responsibilities =
                facts.responsibilities() == null ? List.of() : facts.responsibilities();
        skills.forEach(skill -> validateSkillFact(document.rawText(), skill));
        responsibilities.forEach(item -> validateResponsibilityFact(document.rawText(), item));
        if (responsibilities.size() >= 10) {
            issues.add(new QualityIssueDraft("RESPONSIBILITY_INFLATION", "MEDIUM",
                    "职责条目较多，晋级前建议人工确认岗位边界。", false));
        }
        double score = Math.max(0, 1 - issues.stream().mapToDouble(this::issuePenalty).sum());
        boolean blocking = issues.stream().anyMatch(QualityIssueDraft::blocking);
        return new ValidatedDocument(document, blocking ? "QUARANTINED" : document.processingStatus(), score, issues);
    }

    private void validateSkillFact(String rawText, StructuredSkillFact fact) {
        require(fact != null && !text(fact.canonicalSkill()).isBlank() && !text(fact.rawValue()).isBlank(),
                "STRUCTURED_JD_SKILL_EMPTY", "技能事实名称不能为空");
        require(REQUIREMENT_TYPES.contains(fact.requirementType()), "STRUCTURED_JD_REQUIREMENT_TYPE_INVALID",
                "技能要求类型不合法：" + fact.requirementType());
        validateOffset(rawText, fact.rawValue(), fact.startOffset(), fact.endOffset(), fact.offsetVerified());
    }

    private void validateResponsibilityFact(String rawText, StructuredResponsibilityFact fact) {
        require(fact != null && !text(fact.rawValue()).isBlank(),
                "STRUCTURED_JD_RESPONSIBILITY_EMPTY", "职责事实不能为空");
        validateOffset(rawText, fact.rawValue(), fact.startOffset(), fact.endOffset(), fact.offsetVerified());
    }

    private void validateOffset(String rawText, String rawValue, int start, int end, boolean verified) {
        boolean inRange = start >= 0 && end > start && end <= rawText.length();
        require(inRange && verified && rawText.substring(start, end).equals(rawValue),
                "STRUCTURED_JD_EVIDENCE_OFFSET_INVALID", "事实证据偏移与原文不一致");
    }

    private void addNearDuplicateWarnings(List<ValidatedDocument> documents) {
        List<TextFingerprint> fingerprints = documents.stream()
                .map(item -> fingerprint(item.document().rawText()))
                .toList();
        Map<String, List<Integer>> inverted = new HashMap<>();
        boolean[] warned = new boolean[documents.size()];
        for (int right = 0; right < fingerprints.size(); right++) {
            TextFingerprint current = fingerprints.get(right);
            Map<Integer, Integer> intersections = new HashMap<>();
            for (String trigram : current.trigrams()) {
                for (int bucket = current.lengthBucket() - 2; bucket <= current.lengthBucket() + 2; bucket++) {
                    for (Integer left : inverted.getOrDefault(bucket + ":" + trigram, List.of())) {
                        TextFingerprint previous = fingerprints.get(left);
                        double lengthRatio = Math.min(previous.normalizedLength(), current.normalizedLength())
                                / (double) Math.max(1, Math.max(previous.normalizedLength(), current.normalizedLength()));
                        if (lengthRatio >= 0.90) {
                            intersections.merge(left, 1, Integer::sum);
                        }
                    }
                }
            }
            for (Map.Entry<Integer, Integer> candidate : intersections.entrySet()) {
                int left = candidate.getKey();
                if (warned[left] && warned[right]) {
                    continue;
                }
                int union = fingerprints.get(left).trigrams().size() + current.trigrams().size()
                        - candidate.getValue();
                double similarity = candidate.getValue() / (double) Math.max(1, union);
                if (similarity >= 0.92) {
                    addNearDuplicateIssue(documents, warned, left, right, similarity);
                }
            }
            for (String trigram : current.trigrams()) {
                inverted.computeIfAbsent(current.lengthBucket() + ":" + trigram,
                        ignored -> new ArrayList<>()).add(right);
            }
        }
    }

    private void addNearDuplicateIssue(List<ValidatedDocument> documents, boolean[] warned,
                                       int left, int right, double similarity) {
        if (!warned[left]) {
            documents.get(left).issues().add(new QualityIssueDraft("NEAR_DUPLICATE", "MEDIUM",
                    "与 " + documents.get(right).document().sourceRecordKey() + " 的文本相似度为 "
                            + Math.round(similarity * 100) + "%。", false));
            warned[left] = true;
        }
        if (!warned[right]) {
            documents.get(right).issues().add(new QualityIssueDraft("NEAR_DUPLICATE", "MEDIUM",
                    "与 " + documents.get(left).document().sourceRecordKey() + " 的文本相似度为 "
                            + Math.round(similarity * 100) + "%。", false));
            warned[right] = true;
        }
    }

    private TextFingerprint fingerprint(String value) {
        String normalized = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();
        for (int index = 0; index + 3 <= normalized.length(); index++) {
            result.add(normalized.substring(index, index + 3));
        }
        int bucket = (int) Math.floor(Math.log(Math.max(1, normalized.length())) / Math.log(1.05));
        return new TextFingerprint(normalized.length(), bucket, result);
    }

    private double issuePenalty(QualityIssueDraft issue) {
        return switch (issue.severity()) {
            case "HIGH" -> 0.35;
            case "MEDIUM" -> 0.12;
            default -> 0.03;
        };
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new BusinessException(code, message);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return text(value).isBlank() ? null : value.trim();
    }

    private record TextFingerprint(int normalizedLength, int lengthBucket, Set<String> trigrams) {
    }
}
