package com.zhigang.platform.source;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillEvidence;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillSummary;
import com.zhigang.platform.source.StructuredJdModels.BulkCandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.BulkDynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.DynamicFact;
import com.zhigang.platform.source.StructuredJdModels.DynamicQualityIssue;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordDetail;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordPage;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordSummary;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordQuery;
import com.zhigang.platform.source.StructuredJdModels.DynamicReview;
import com.zhigang.platform.source.StructuredJdModels.DynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.FactReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionBatchView;
import com.zhigang.platform.source.StructuredJdModels.PromotionItemView;
import com.zhigang.platform.source.StructuredJdModels.PromotionPublishCommand;
import com.zhigang.platform.source.StructuredJdModels.PromotionReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.RealEvidence;
import com.zhigang.platform.source.StructuredJdModels.RealResponsibilityMetric;
import com.zhigang.platform.source.StructuredJdModels.RealRoleSnapshot;
import com.zhigang.platform.source.StructuredJdModels.RealSkillMetric;
import com.zhigang.platform.source.StructuredJdModels.RealSnapshotView;
import com.zhigang.platform.source.StructuredJdModels.ReviewQueueSummary;
import com.zhigang.platform.source.StructuredJdModels.StructuredImportResult;
import com.zhigang.platform.source.StructuredJdModels.StructuredJdBatch;
import com.zhigang.platform.source.StructuredJdModels.SourceLedgerItem;
import com.zhigang.platform.source.StructuredJdModels.ValidatedDocument;
import com.zhigang.platform.source.StructuredJdModels.TriageAssignment;
import com.zhigang.platform.source.StructuredJdModels.TriageCandidate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Profile("!mysql")
@Repository
public class InMemoryStructuredJdRepository implements StructuredJdRepository {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);

    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, StructuredImportResult> imports = new LinkedHashMap<>();
    private final Map<Long, StructuredJdBatch> importBatches = new LinkedHashMap<>();
    private final Map<String, Long> batchKeys = new LinkedHashMap<>();
    private final Map<Long, StoredRecord> records = new LinkedHashMap<>();
    private final Map<Long, StoredBatch> batches = new LinkedHashMap<>();
    private final Map<String, RegistryDecision> candidateRegistry = new LinkedHashMap<>();

    @Override
    public synchronized StructuredImportResult saveImport(StructuredJdBatch batch, List<ValidatedDocument> documents) {
        long importId = ids.getAndIncrement();
        int accepted = 0;
        int duplicates = 0;
        int quarantined = 0;
        Set<String> existing = records.values().stream()
                .map(record -> record.document.document().contentHash())
                .collect(Collectors.toSet());
        for (ValidatedDocument document : documents) {
            String key = document.document().contentHash();
            if (!existing.add(key)) {
                duplicates++;
                continue;
            }
            long recordId = ids.getAndIncrement();
            StoredRecord stored = new StoredRecord(recordId, importId, document);
            records.put(recordId, stored);
            accepted++;
            if ("QUARANTINED".equals(document.status())) {
                quarantined++;
            }
        }
        StructuredImportResult result = new StructuredImportResult(importId,
                "structured-" + batch.batchKey(), batch.sourceWorkbook().fileName(), batch.sourceWorkbook().sha256(),
                documents.size(), accepted, duplicates, quarantined, 0, 0, 0, 0, "COMPLETED",
                batch.processingPolicy().version(), OffsetDateTime.now(OFFSET));
        imports.put(importId, result);
        importBatches.put(importId, batch);
        batchKeys.put(batch.batchKey(), importId);
        ReviewQueueSummary summary = reviewQueueSummary(importId);
        return new StructuredImportResult(result.id(), result.runCode(), result.sourceFileName(), result.sourceFileHash(),
                result.fetchedCount(), result.acceptedCount(), result.duplicateCount(), result.quarantinedCount(),
                (int) summary.autoApprovedCount(), (int) summary.sampledCount(), (int) summary.exceptionCount(),
                (int) summary.waitingSkillCount(), result.status(), result.policyVersion(), result.createdAt());
    }

    @Override
    public synchronized Optional<StructuredImportResult> findImportByBatchKey(String batchKey) {
        Long id = batchKeys.get(batchKey);
        return id == null ? Optional.empty() : Optional.of(findImport(id));
    }

    @Override
    public synchronized StructuredImportResult findImport(long id) {
        StructuredImportResult result = imports.get(id);
        if (result == null) {
            throw missing("IMPORT_NOT_FOUND", "未找到导入批次");
        }
        ReviewQueueSummary summary = reviewQueueSummary(id);
        return new StructuredImportResult(result.id(), result.runCode(), result.sourceFileName(), result.sourceFileHash(),
                result.fetchedCount(), result.acceptedCount(), result.duplicateCount(), result.quarantinedCount(),
                (int) summary.autoApprovedCount(), (int) summary.sampledCount(), (int) summary.exceptionCount(),
                (int) summary.waitingSkillCount(), result.status(), result.policyVersion(), result.createdAt());
    }

    @Override
    public synchronized DynamicRecordPage findDynamicRecords(DynamicRecordQuery query) {
        List<DynamicRecordSummary> filtered = records.values().stream()
                .filter(item -> query.importId() == null || item.importId == query.importId())
                .filter(item -> query.status() == null || item.status.equals(query.status()))
                .filter(item -> query.keyword() == null || item.matches(query.keyword()))
                .filter(item -> query.riskLevel() == null || item.riskLevel.equals(query.riskLevel()))
                .filter(item -> query.reviewRoute() == null || item.reviewRoute.equals(query.reviewRoute()))
                .filter(item -> !query.onlyExceptions() || Set.of("SAMPLE_REVIEW", "EXCEPTION_REVIEW", "WAITING_SKILL")
                        .contains(item.reviewRoute))
                .sorted(Comparator.comparingLong((StoredRecord item) -> item.id).reversed())
                .map(StoredRecord::summary)
                .toList();
        int from = Math.min(filtered.size(), (query.page() - 1) * query.size());
        int to = Math.min(filtered.size(), from + query.size());
        return new DynamicRecordPage(filtered.subList(from, to), query.page(), query.size(), filtered.size());
    }

    @Override
    public synchronized DynamicRecordDetail findDynamicRecord(long id) {
        return record(id).detail();
    }

    @Override
    public synchronized DynamicRecordDetail reviewDynamicRecord(long id, DynamicReviewCommand command, String policyVersion) {
        StoredRecord record = record(id);
        if (command.factReviews() != null) {
            for (FactReviewCommand review : command.factReviews()) {
                MutableFact fact = record.facts.stream().filter(item -> item.id == review.factId()).findFirst()
                        .orElseThrow(() -> missing("DYNAMIC_FACT_NOT_FOUND", "未找到候选事实"));
                fact.reviewDecision = review.decision();
                fact.correctedValue = review.correctedValue();
                fact.reviewNote = review.note();
            }
        }
        record.reviewDecision = command.decision();
        record.reviewSource = "HUMAN";
        record.status = switch (command.decision()) {
            case "APPROVE" -> "ELIGIBLE";
            case "QUARANTINE" -> "QUARANTINED";
            default -> "REJECTED";
        };
        record.reviews.add(new DynamicReview(ids.getAndIncrement(), command.decision(), command.reviewer(),
                command.reason(), policyVersion, "HUMAN", OffsetDateTime.now(OFFSET)));
        return record.detail();
    }

    @Override
    public synchronized List<DynamicRecordDetail> bulkReviewDynamicRecords(BulkDynamicReviewCommand command,
                                                                            String policyVersion) {
        List<Long> recordIds = command.recordIds().stream().distinct().toList();
        recordIds.forEach(id -> {
            if (!records.containsKey(id)) {
                throw missing("DYNAMIC_RECORD_NOT_FOUND", "部分动态记录不存在");
            }
        });
        return recordIds.stream().map(id -> reviewDynamicRecord(id,
                new DynamicReviewCommand(command.decision(), command.reviewer(), command.reason(), List.of()),
                policyVersion)).toList();
    }

    @Override
    public synchronized List<CandidateSkillSummary> findCandidateSkills() {
        Map<String, List<MutableFact>> grouped = records.values().stream()
                .flatMap(record -> record.facts.stream())
                .filter(fact -> "SKILL".equals(fact.factType) && "CANDIDATE".equals(fact.ontologyStatus))
                .collect(Collectors.groupingBy(fact -> fact.canonicalValue, LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream().map(entry -> {
            List<MutableFact> facts = entry.getValue();
            Set<String> companies = facts.stream().map(fact -> fact.owner.document.document().company())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String decision = facts.stream().map(fact -> fact.reviewDecision).distinct().count() == 1
                    ? facts.get(0).reviewDecision : "MIXED";
            String corrected = facts.stream().map(fact -> fact.correctedValue).filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse(null);
            List<CandidateSkillEvidence> evidence = facts.stream()
                    .collect(Collectors.toMap(fact -> fact.owner.document.document().company(), fact -> fact,
                            (left, right) -> left.confidence >= right.confidence ? left : right,
                            LinkedHashMap::new))
                    .values().stream().limit(3).map(this::candidateEvidence).toList();
            var advice = CandidateSkillAdvisor.recommend(entry.getKey(), companies.size(), decision, corrected,
                    null, evidence);
            return new CandidateSkillSummary(entry.getKey(), facts.get(0).suggestedCategory, facts.size(),
                    companies.size(), decision, corrected,
                    candidateRegistry.containsKey(entry.getKey()) ? "REUSED" : "NEW",
                    facts.stream().map(fact -> fact.requirementType).filter(value -> value != null).distinct().toList(),
                    advice.decision(), advice.target(), advice.reasons(), evidence);
        }).sorted(Comparator.comparing(CandidateSkillSummary::skill)).toList();
    }

    private CandidateSkillEvidence candidateEvidence(MutableFact fact) {
        var document = fact.owner.document.document();
        return new CandidateSkillEvidence(fact.owner.id, document.jobTitle(), document.company(),
                fact.evidenceQuote, fact.requirementType, fact.confidence, fact.offsetVerified);
    }

    @Override
    public synchronized List<CandidateSkillSummary> reviewCandidateSkill(String skill, CandidateSkillReviewCommand command) {
        int updated = 0;
        for (StoredRecord record : records.values()) {
            for (MutableFact fact : record.facts) {
                if ("CANDIDATE".equals(fact.ontologyStatus) && fact.canonicalValue.equals(skill)) {
                    fact.reviewDecision = command.decision();
                    fact.correctedValue = command.correctedValue();
                    fact.reviewNote = command.note();
                    updated++;
                }
            }
        }
        if (updated == 0) {
            throw missing("CANDIDATE_SKILL_NOT_FOUND", "未找到候选技能");
        }
        candidateRegistry.put(skill, new RegistryDecision(command.decision(), command.correctedValue(),
                command.reviewer(), command.note()));
        return findCandidateSkills();
    }

    @Override
    public synchronized List<CandidateSkillSummary> bulkReviewCandidateSkills(BulkCandidateSkillReviewCommand command) {
        command.skills().stream().distinct().forEach(skill -> reviewCandidateSkill(skill,
                new CandidateSkillReviewCommand(command.decision(), command.correctedValue(),
                        command.reviewer(), command.note())));
        return findCandidateSkills();
    }

    @Override
    public synchronized List<TriageCandidate> findTriageCandidates(Long importId) {
        return records.values().stream()
                .filter(record -> importId == null || record.importId == importId)
                .filter(record -> !"PROMOTED".equals(record.status))
                .map(record -> new TriageCandidate(record.id, record.importId, record.status,
                        record.document.qualityScore(), record.document.document().contentHash(),
                        (int) record.facts.stream().filter(fact -> "CANDIDATE".equals(fact.ontologyStatus)
                                && "PENDING_REVIEW".equals(fact.reviewDecision)).count(),
                        (int) record.document.issues().stream().filter(StructuredJdModels.QualityIssueDraft::blocking).count(),
                        (int) record.document.issues().stream().filter(issue ->
                                Set.of("HIGH", "MEDIUM").contains(issue.severity())).count(),
                        record.facts.stream().mapToDouble(fact -> fact.confidence).min().orElse(1),
                        record.reviewDecision, record.reviewSource))
                .toList();
    }

    @Override
    public synchronized void saveTriageAssignments(List<TriageAssignment> assignments, String policyVersion) {
        for (TriageAssignment assignment : assignments) {
            StoredRecord record = record(assignment.recordId());
            record.riskLevel = assignment.riskLevel();
            record.reviewRoute = assignment.reviewRoute();
            record.reviewReasons = List.copyOf(assignment.reasons());
            record.triagePolicyVersion = policyVersion;
            record.triagedAt = OffsetDateTime.now(OFFSET);
            if (assignment.createAutoApproval()) {
                record.reviewDecision = "APPROVE";
                record.reviewSource = "SYSTEM";
                record.status = "ELIGIBLE";
                record.reviews.add(new DynamicReview(ids.getAndIncrement(), "APPROVE", "system:auto-triage",
                        "低风险记录通过自动门禁", policyVersion, "SYSTEM", OffsetDateTime.now(OFFSET)));
            } else if (!"NONE".equals(assignment.reviewSource())) {
                record.reviewSource = assignment.reviewSource();
            }
        }
    }

    @Override
    public synchronized ReviewQueueSummary reviewQueueSummary(Long importId) {
        List<StoredRecord> selected = records.values().stream()
                .filter(record -> importId == null || record.importId == importId).toList();
        long pendingSkills = findCandidateSkills().stream()
                .filter(skill -> Set.of("PENDING_REVIEW", "MIXED").contains(skill.reviewDecision())).count();
        long sampled = countRoute(selected, "SAMPLE_REVIEW");
        long exception = countRoute(selected, "EXCEPTION_REVIEW");
        long waiting = countRoute(selected, "WAITING_SKILL");
        return new ReviewQueueSummary(importId, selected.size(), countRoute(selected, "AUTO_APPROVED"), sampled,
                exception, waiting, countRoute(selected, "HUMAN_APPROVED"), countRoute(selected, "BLOCKED"),
                pendingSkills, sampled + exception + pendingSkills);
    }

    private long countRoute(List<StoredRecord> selected, String route) {
        return selected.stream().filter(record -> route.equals(record.reviewRoute)).count();
    }

    @Override
    public synchronized PromotionBatchView createPromotionBatch(PromotionBatchCommand command, String policyVersion) {
        List<StoredRecord> selected = "ALL_ELIGIBLE".equals(command.selectionMode())
                ? records.values().stream()
                .filter(record -> command.importId() == null || record.importId == command.importId())
                .filter(record -> "ELIGIBLE".equals(record.status) && "APPROVE".equals(record.reviewDecision))
                .filter(record -> Set.of("AUTO_APPROVED", "HUMAN_APPROVED").contains(record.reviewRoute))
                .toList()
                : command.recordIds().stream().distinct().map(this::record).toList();
        if (selected.isEmpty()) throw new BusinessException("PROMOTION_RECORDS_EMPTY", "没有可晋级记录");
        for (StoredRecord record : selected) {
            if (!"ELIGIBLE".equals(record.status) || !"APPROVE".equals(record.reviewDecision)) {
                throw new BusinessException("PROMOTION_RECORD_NOT_APPROVED", "只有已通过审核的 ELIGIBLE 记录可以晋级");
            }
            boolean pendingCandidate = record.facts.stream().anyMatch(fact ->
                    "CANDIDATE".equals(fact.ontologyStatus) && "PENDING_REVIEW".equals(fact.reviewDecision));
            if (pendingCandidate) {
                throw new BusinessException("CANDIDATE_SKILL_REVIEW_PENDING", "候选技能尚未全部完成人工审核");
            }
            if (!Set.of("AUTO_APPROVED", "HUMAN_APPROVED").contains(record.reviewRoute)) {
                throw new BusinessException("PROMOTION_RECORD_REVIEW_PENDING", "抽检和异常记录完成人工审核后才能晋级");
            }
        }
        long id = ids.getAndIncrement();
        StoredBatch batch = new StoredBatch(id, "promotion-" + id, "real-jd-pilot-" + id,
                policyVersion, command.requestedBy(), selected);
        batches.put(id, batch);
        return batch.view();
    }

    @Override
    public synchronized PromotionBatchView findPromotionBatch(long id) {
        return batch(id).view();
    }

    @Override
    public synchronized PromotionBatchView reviewPromotionBatch(long id, PromotionReviewCommand command) {
        StoredBatch batch = batch(id);
        if (batch.requestedBy.equals(command.reviewer())) {
            throw new BusinessException("SEPARATION_OF_DUTIES_REQUIRED", "创建人不能审核自己的晋级批次");
        }
        if (!"DRAFT".equals(batch.status)) {
            throw new BusinessException("PROMOTION_BATCH_STATE_INVALID", "当前批次不能重复审核");
        }
        batch.approvedBy = command.reviewer();
        batch.reviewNote = command.note();
        batch.status = "APPROVE".equals(command.decision()) ? "APPROVED" : "REJECTED";
        return batch.view();
    }

    @Override
    public synchronized PromotionBatchView publishPromotionBatch(long id, PromotionPublishCommand command) {
        StoredBatch batch = batch(id);
        if ("COMPLETED".equals(batch.status)) {
            return batch.view();
        }
        if (!"APPROVED".equals(batch.status)) {
            throw new BusinessException("PROMOTION_BATCH_NOT_APPROVED", "晋级批次必须先由另一位审核人通过");
        }
        long documentId = 1;
        for (StoredRecord record : batch.records) {
            record.status = "PROMOTED";
            record.targetDocumentId = documentId++;
        }
        batch.status = "COMPLETED";
        batch.completedAt = OffsetDateTime.now(OFFSET);
        return batch.view();
    }

    @Override
    public synchronized RealSnapshotView findCurrentRealSnapshot() {
        List<StoredRecord> promoted = records.values().stream().filter(item -> "PROMOTED".equals(item.status)).toList();
        if (promoted.isEmpty()) {
            return new RealSnapshotView("", "REAL", "CURRENT_SNAPSHOT_ONLY", 0, 0, false, List.of());
        }
        String snapshot = batches.values().stream().filter(item -> "COMPLETED".equals(item.status))
                .max(Comparator.comparingLong(item -> item.id)).map(item -> item.snapshotCode).orElse("");
        return snapshot(snapshot, promoted);
    }

    @Override
    public synchronized List<SourceLedgerItem> findSourceLedger() {
        Map<LedgerKey, LedgerAccumulator> ledger = new LinkedHashMap<>();
        importBatches.forEach((importId, batch) -> {
            Map<LedgerKey, List<StructuredJdModels.StructuredJdDocument>> groups = batch.documents().stream()
                    .collect(Collectors.groupingBy(LedgerKey::from, LinkedHashMap::new, Collectors.toList()));
            groups.forEach((key, documents) -> {
                LedgerAccumulator item = ledger.computeIfAbsent(key, ignored -> new LedgerAccumulator());
                item.batchCount++;
                item.collectedCount += documents.size();
                List<StoredRecord> accepted = records.values().stream()
                        .filter(record -> record.importId == importId && key.equals(LedgerKey.from(record.document.document())))
                        .toList();
                item.acceptedCount += accepted.size();
                item.duplicateCount += documents.size() - accepted.size();
                item.exceptionCount += (int) accepted.stream()
                        .filter(record -> !record.document.issues().isEmpty() || "QUARANTINED".equals(record.status))
                        .count();
                OffsetDateTime createdAt = imports.get(importId).createdAt();
                if (item.updatedAt == null || createdAt.isAfter(item.updatedAt)) item.updatedAt = createdAt;
            });
        });
        return ledger.entrySet().stream()
                .map(entry -> entry.getValue().toItem(entry.getKey()))
                .sorted(Comparator.comparing(SourceLedgerItem::sourceName).thenComparing(SourceLedgerItem::timeScope))
                .toList();
    }

    private RealSnapshotView snapshot(String code, List<StoredRecord> promoted) {
        Map<String, List<StoredRecord>> grouped = promoted.stream().collect(Collectors.groupingBy(
                item -> item.document.document().jobTitle().toLowerCase(Locale.ROOT).contains("java") ? "JAVA" : "AI_AGENT",
                LinkedHashMap::new, Collectors.toList()));
        List<RealRoleSnapshot> roles = grouped.entrySet().stream()
                .map(entry -> roleSnapshot(entry.getKey(), entry.getValue())).toList();
        int companies = (int) promoted.stream().map(item -> item.document.document().company()).distinct().count();
        boolean timeCoverage = promoted.stream().allMatch(item -> item.document.document().publishedAt() != null);
        return new RealSnapshotView(code, "REAL", "CURRENT_SNAPSHOT_ONLY", promoted.size(), companies, timeCoverage, roles);
    }

    private RealRoleSnapshot roleSnapshot(String code, List<StoredRecord> group) {
        int companyCount = (int) group.stream().map(item -> item.document.document().company()).distinct().count();
        Map<String, List<MutableFact>> facts = group.stream().flatMap(item -> item.facts.stream())
                .filter(this::publishableSkill)
                .collect(Collectors.groupingBy(fact -> fact.requirementType, LinkedHashMap::new, Collectors.toList()));
        List<MutableFact> responsibilities = group.stream().flatMap(item -> item.facts.stream())
                .filter(fact -> "RESPONSIBILITY".equals(fact.factType)).toList();
        return new RealRoleSnapshot(code, "JAVA".equals(code) ? "Java 开发工程师" : "AI Agent 应用开发工程师",
                group.size(), companyCount, "试验性快照",
                responsibilityMetrics(responsibilities),
                metrics(facts.getOrDefault("REQUIRED", List.of()), companyCount),
                metrics(facts.getOrDefault("BONUS", List.of()), companyCount),
                metrics(facts.getOrDefault("MENTIONED", List.of()), companyCount),
                facts.getOrDefault("NEGATED", List.of()).stream().map(this::evidence).toList());
    }

    private List<RealResponsibilityMetric> responsibilityMetrics(List<MutableFact> facts) {
        return facts.stream().collect(Collectors.groupingBy(fact -> fact.canonicalValue,
                        LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> {
                    List<MutableFact> grouped = entry.getValue();
                    int documents = (int) grouped.stream().map(fact -> fact.owner.id).distinct().count();
                    int companies = (int) grouped.stream()
                            .map(fact -> fact.owner.document.document().company()).distinct().count();
                    return new RealResponsibilityMetric(entry.getKey(), documents, companies,
                            grouped.stream().limit(5).map(this::evidence).toList());
                }).sorted(Comparator.comparingInt(RealResponsibilityMetric::documentCount).reversed()
                        .thenComparing(RealResponsibilityMetric::responsibility)).limit(12).toList();
    }

    private List<RealSkillMetric> metrics(List<MutableFact> facts, int denominator) {
        return facts.stream().collect(Collectors.groupingBy(this::publishedSkillName, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> {
                    List<MutableFact> grouped = entry.getValue();
                    int documents = (int) grouped.stream().map(fact -> fact.owner.id).distinct().count();
                    int companies = (int) grouped.stream().map(fact -> fact.owner.document.document().company()).distinct().count();
                    return new RealSkillMetric(entry.getKey(), companies / (double) Math.max(1, denominator),
                            documents, companies, grouped.stream().limit(8).map(this::evidence).toList());
                }).sorted(Comparator.comparingDouble(RealSkillMetric::rate).reversed()
                        .thenComparing(RealSkillMetric::skill)).toList();
    }

    private boolean publishableSkill(MutableFact fact) {
        return "SKILL".equals(fact.factType)
                && (!"CANDIDATE".equals(fact.ontologyStatus)
                || Set.of("APPROVED_STANDARD", "APPROVED_ALIAS").contains(fact.reviewDecision));
    }

    private String publishedSkillName(MutableFact fact) {
        return "APPROVED_ALIAS".equals(fact.reviewDecision) && fact.correctedValue != null
                ? fact.correctedValue : fact.canonicalValue;
    }

    private RealEvidence evidence(MutableFact fact) {
        var document = fact.owner.document.document();
        return new RealEvidence(fact.owner.targetDocumentId == null ? fact.owner.id : fact.owner.targetDocumentId,
                document.jobTitle(), document.company(), fact.evidenceQuote, fact.startOffset, fact.endOffset,
                document.source().canonicalUrl());
    }

    private StoredRecord record(long id) {
        StoredRecord record = records.get(id);
        if (record == null) {
            throw missing("DYNAMIC_RECORD_NOT_FOUND", "未找到动态资料");
        }
        return record;
    }

    private StoredBatch batch(long id) {
        StoredBatch batch = batches.get(id);
        if (batch == null) {
            throw missing("PROMOTION_BATCH_NOT_FOUND", "未找到晋级批次");
        }
        return batch;
    }

    private BusinessException missing(String code, String message) {
        return new BusinessException(code, message);
    }

    private record LedgerKey(String sourceId, String sourceName, String sourceTier,
                             String timeScope, String adapter) {
        private static LedgerKey from(StructuredJdModels.StructuredJdDocument document) {
            var source = document.source();
            return new LedgerKey(source.sourceId(), source.name(), source.sourceTier(),
                    document.timeScope(), source.adapter());
        }
    }

    private static final class LedgerAccumulator {
        private int batchCount;
        private int collectedCount;
        private int duplicateCount;
        private int acceptedCount;
        private int exceptionCount;
        private OffsetDateTime updatedAt;

        private SourceLedgerItem toItem(LedgerKey key) {
            return new SourceLedgerItem(key.sourceId(), key.sourceName(), key.sourceTier(), key.timeScope(),
                    key.adapter(), batchCount, collectedCount, duplicateCount, acceptedCount, exceptionCount,
                    updatedAt);
        }
    }

    private final class StoredRecord {
        private final long id;
        private final long importId;
        private final ValidatedDocument document;
        private final List<MutableFact> facts = new ArrayList<>();
        private final List<DynamicReview> reviews = new ArrayList<>();
        private String status;
        private String reviewDecision = "PENDING_REVIEW";
        private String reviewSource = "NONE";
        private String riskLevel = "UNASSESSED";
        private String reviewRoute = "WAITING_SKILL";
        private List<String> reviewReasons = List.of("等待风险分流");
        private String triagePolicyVersion;
        private OffsetDateTime triagedAt;
        private Long targetDocumentId;

        private StoredRecord(long id, long importId, ValidatedDocument document) {
            this.id = id;
            this.importId = importId;
            this.document = document;
            this.status = document.status();
            document.document().facts().skills().forEach(fact -> facts.add(new MutableFact(this, fact)));
            document.document().facts().responsibilities().forEach(fact -> facts.add(new MutableFact(this, fact)));
        }

        private DynamicRecordSummary summary() {
            int skills = (int) facts.stream().filter(fact -> "SKILL".equals(fact.factType)).count();
            int candidates = (int) facts.stream().filter(fact -> "CANDIDATE".equals(fact.ontologyStatus)).count();
            var source = document.document();
            return new DynamicRecordSummary(id, importId, source.sourceRecordKey(), source.jobTitle(), source.company(),
                    source.city(), source.contentHash(), source.dataMode(), status, document.qualityScore(),
                    skills, candidates, document.issues().size(), reviewDecision, riskLevel, reviewRoute,
                    reviewReasons, reviewSource, triagePolicyVersion, triagedAt,
                    source.collectedAt().atStartOfDay().atOffset(OFFSET));
        }

        private boolean matches(String keyword) {
            String normalized = keyword.toLowerCase(Locale.ROOT);
            var source = document.document();
            return source.jobTitle().toLowerCase(Locale.ROOT).contains(normalized)
                    || source.company().toLowerCase(Locale.ROOT).contains(normalized)
                    || (source.city() != null && source.city().toLowerCase(Locale.ROOT).contains(normalized));
        }

        private DynamicRecordDetail detail() {
            var source = document.document();
            List<DynamicQualityIssue> issues = new ArrayList<>();
            for (var issue : document.issues()) {
                issues.add(new DynamicQualityIssue(ids.getAndIncrement(), issue.type(), issue.severity(),
                        issue.detail(), issue.blocking()));
            }
            return new DynamicRecordDetail(summary(), source.source().name(), source.source().licenseStatus(),
                    source.source().originalUrl(), source.source().canonicalUrl(), source.salary().raw(),
                    source.experienceRequirement(), source.educationRequirement(), source.publishedAt(), source.rawText(),
                    facts.stream().map(MutableFact::view).toList(), issues, List.copyOf(reviews));
        }
    }

    private final class MutableFact {
        private final long id = ids.getAndIncrement();
        private final StoredRecord owner;
        private final String factType;
        private final String ontologyStatus;
        private final String canonicalValue;
        private final String suggestedCategory;
        private final String rawValue;
        private final String requirementType;
        private final int startOffset;
        private final int endOffset;
        private final String evidenceQuote;
        private final double confidence;
        private final boolean offsetVerified;
        private String reviewDecision;
        private String correctedValue;
        private String reviewNote;

        private MutableFact(StoredRecord owner, StructuredJdModels.StructuredSkillFact fact) {
            this.owner = owner;
            this.factType = "SKILL";
            this.ontologyStatus = fact.ontologyStatus();
            this.canonicalValue = fact.canonicalSkill();
            this.suggestedCategory = fact.suggestedCategory();
            this.rawValue = fact.rawValue();
            this.requirementType = fact.requirementType();
            this.startOffset = fact.startOffset();
            this.endOffset = fact.endOffset();
            this.evidenceQuote = fact.evidenceQuote();
            this.confidence = fact.confidence();
            this.offsetVerified = fact.offsetVerified();
            RegistryDecision reused = candidateRegistry.get(fact.canonicalSkill());
            this.reviewDecision = "STANDARD".equals(fact.ontologyStatus()) ? "APPROVED_EXISTING"
                    : reused == null ? "PENDING_REVIEW" : reused.decision();
            this.correctedValue = reused == null ? null : reused.correctedValue();
            this.reviewNote = reused == null ? null : "复用候选技能注册表决定";
        }

        private MutableFact(StoredRecord owner, StructuredJdModels.StructuredResponsibilityFact fact) {
            this.owner = owner;
            this.factType = "RESPONSIBILITY";
            this.ontologyStatus = "STANDARD";
            this.canonicalValue = fact.normalizedValue();
            this.suggestedCategory = null;
            this.rawValue = fact.rawValue();
            this.requirementType = null;
            this.startOffset = fact.startOffset();
            this.endOffset = fact.endOffset();
            this.evidenceQuote = fact.evidenceQuote();
            this.confidence = fact.confidence();
            this.offsetVerified = fact.offsetVerified();
            this.reviewDecision = "APPROVED_EXISTING";
        }

        private DynamicFact view() {
            return new DynamicFact(id, factType, ontologyStatus, canonicalValue, suggestedCategory, rawValue,
                    requirementType, startOffset, endOffset, evidenceQuote, confidence, offsetVerified,
                    reviewDecision, correctedValue, reviewNote);
        }
    }

    private final class StoredBatch {
        private final long id;
        private final String code;
        private final String snapshotCode;
        private final String policyVersion;
        private final String requestedBy;
        private final List<StoredRecord> records;
        private final OffsetDateTime createdAt = OffsetDateTime.now(OFFSET);
        private String status = "DRAFT";
        private String approvedBy;
        private String reviewNote;
        private OffsetDateTime completedAt;

        private StoredBatch(long id, String code, String snapshotCode, String policyVersion,
                            String requestedBy, List<StoredRecord> records) {
            this.id = id;
            this.code = code;
            this.snapshotCode = snapshotCode;
            this.policyVersion = policyVersion;
            this.requestedBy = requestedBy;
            this.records = records;
        }

        private PromotionBatchView view() {
            List<PromotionItemView> items = records.stream().map(record -> new PromotionItemView(
                    record.id, record.document.document().jobTitle(), record.document.document().company(),
                    "COMPLETED".equals(status) ? "PROMOTED" : "PENDING", record.targetDocumentId, null)).toList();
            int promoted = "COMPLETED".equals(status) ? records.size() : 0;
            return new PromotionBatchView(id, code, snapshotCode, policyVersion, status, records.size(),
                    promoted, 0, requestedBy, approvedBy, reviewNote, createdAt, completedAt, items);
        }
    }

    private record RegistryDecision(String decision, String correctedValue, String reviewer, String note) {
    }
}
