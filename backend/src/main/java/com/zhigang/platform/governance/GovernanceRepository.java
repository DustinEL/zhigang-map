package com.zhigang.platform.governance;

import com.zhigang.platform.api.Requests.ChangeProposalRequest;
import com.zhigang.platform.api.Requests.GraphReleaseRequest;
import com.zhigang.platform.api.Requests.ReviewRequest;
import com.zhigang.platform.domain.PlatformModels.ChangeProposal;
import com.zhigang.platform.domain.PlatformModels.GraphRelease;
import com.zhigang.platform.domain.PlatformModels.ReleaseDiff;

import java.time.OffsetDateTime;
import java.util.List;

public interface GovernanceRepository {
    List<ChangeProposal> proposals();

    ChangeProposal create(ChangeProposalRequest request);

    ChangeProposal review(long proposalId, ReviewRequest request);

    List<GraphRelease> releases();

    GraphRelease currentRelease();

    GraphRelease publish(GraphReleaseRequest request, String policyVersion);

    GraphRelease rollback(long releaseId, String publisher);

    ReleaseDiff diff(long releaseId);

    CurrentReleaseSummary currentSummary();

    record CurrentReleaseSummary(String version, String dataScope, int documentCount,
                                 String policyVersion, OffsetDateTime updatedAt) {
    }
}
