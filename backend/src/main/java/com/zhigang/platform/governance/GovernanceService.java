package com.zhigang.platform.governance;

import com.zhigang.platform.api.Requests.ChangeProposalRequest;
import com.zhigang.platform.api.Requests.GraphReleaseRequest;
import com.zhigang.platform.api.Requests.ReviewRequest;
import com.zhigang.platform.domain.PlatformModels.ChangeProposal;
import com.zhigang.platform.domain.PlatformModels.GraphRelease;
import com.zhigang.platform.domain.PlatformModels.ReleaseDiff;
import com.zhigang.platform.governance.GovernanceRepository.CurrentReleaseSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GovernanceService {
    private final String policyVersion;
    private final GovernanceRepository repository;

    @Autowired
    public GovernanceService(@Value("${zhigang.analysis.policy-version:v1.0.0}") String policyVersion,
                             GovernanceRepository repository) {
        this.policyVersion = policyVersion;
        this.repository = repository;
    }

    GovernanceService(String policyVersion) {
        this(policyVersion, new InMemoryGovernanceRepository());
    }

    public List<ChangeProposal> proposals() { return repository.proposals(); }
    public ChangeProposal create(ChangeProposalRequest request) { return repository.create(request); }
    public ChangeProposal review(long proposalId, ReviewRequest request) { return repository.review(proposalId, request); }
    public List<GraphRelease> releases() { return repository.releases(); }
    public GraphRelease currentRelease() { return repository.currentRelease(); }
    public CurrentReleaseSummary currentSummary() { return repository.currentSummary(); }
    public GraphRelease publish(GraphReleaseRequest request) { return repository.publish(request, policyVersion); }
    public GraphRelease rollback(long releaseId, String publisher) { return repository.rollback(releaseId, publisher); }
    public ReleaseDiff diff(long releaseId) { return repository.diff(releaseId); }
}
