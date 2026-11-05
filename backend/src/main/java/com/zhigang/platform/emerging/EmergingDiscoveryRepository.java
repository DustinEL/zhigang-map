package com.zhigang.platform.emerging;

import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDraft;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDetail;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateSeed;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryCandidate;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryRun;

import java.util.List;
import java.util.Optional;

public interface EmergingDiscoveryRepository {
    DiscoveryRun createRun(String runCode, String policyVersion, String policyHash, String inputHash,
                           String embeddingModel, int documentCount, String requestedBy);

    void markRunning(long runId);

    void complete(long runId, int embeddingDimension, String clusterModel, int clusterCount,
                  int candidateCount, int noiseCount, List<CandidateSeed> candidates);

    void fail(long runId, String errorCode, String errorMessage);

    List<DiscoveryRun> findRuns();

    Optional<DiscoveryRun> findRun(long runId);

    List<DiscoveryCandidate> findCandidates();

    Optional<DiscoveryCandidate> findCandidate(long candidateId);

    CandidateDetail findCandidateDetail(long candidateId);

    DiscoveryCandidate updateDraft(long candidateId, CandidateDraft draft);

    DiscoveryCandidate submit(long candidateId, int expectedVersion, String creator);

    DiscoveryCandidate review(long candidateId, int expectedVersion, String decision,
                              String reviewer, String comment);

    DiscoveryCandidate publish(long candidateId, int expectedVersion, String version, String publisher,
                               EmergingDiscoveryPolicy policy);
}
