package com.zhigang.platform.source;

import com.zhigang.platform.source.StructuredJdModels.CandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.CandidateSkillSummary;
import com.zhigang.platform.source.StructuredJdModels.BulkCandidateSkillReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.BulkDynamicReviewCommand;
import com.zhigang.platform.source.StructuredJdModels.DynamicRecordQuery;
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
import com.zhigang.platform.source.StructuredJdModels.ValidatedDocument;
import com.zhigang.platform.source.StructuredJdModels.TriageAssignment;
import com.zhigang.platform.source.StructuredJdModels.TriageCandidate;

import java.util.List;
import java.util.Optional;

public interface StructuredJdRepository {
    StructuredImportResult saveImport(StructuredJdBatch batch, List<ValidatedDocument> documents);

    Optional<StructuredImportResult> findImportByBatchKey(String batchKey);

    StructuredImportResult findImport(long id);

    DynamicRecordPage findDynamicRecords(DynamicRecordQuery query);

    DynamicRecordDetail findDynamicRecord(long id);

    DynamicRecordDetail reviewDynamicRecord(long id, DynamicReviewCommand command, String policyVersion);

    List<DynamicRecordDetail> bulkReviewDynamicRecords(BulkDynamicReviewCommand command, String policyVersion);

    List<CandidateSkillSummary> findCandidateSkills();

    List<CandidateSkillSummary> reviewCandidateSkill(String skill, CandidateSkillReviewCommand command);

    List<CandidateSkillSummary> bulkReviewCandidateSkills(BulkCandidateSkillReviewCommand command);

    List<TriageCandidate> findTriageCandidates(Long importId);

    void saveTriageAssignments(List<TriageAssignment> assignments, String policyVersion);

    ReviewQueueSummary reviewQueueSummary(Long importId);

    PromotionBatchView createPromotionBatch(PromotionBatchCommand command, String policyVersion);

    PromotionBatchView findPromotionBatch(long id);

    PromotionBatchView reviewPromotionBatch(long id, PromotionReviewCommand command);

    PromotionBatchView publishPromotionBatch(long id, PromotionPublishCommand command);

    RealSnapshotView findCurrentRealSnapshot();

    List<SourceLedgerItem> findSourceLedger();
}
