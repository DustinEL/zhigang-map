package com.zhigang.platform.governance;

import com.zhigang.platform.api.Requests.ChangeProposalRequest;
import com.zhigang.platform.api.Requests.GraphReleaseRequest;
import com.zhigang.platform.api.Requests.ReviewRequest;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.ChangeProposal;
import com.zhigang.platform.domain.PlatformModels.GraphRelease;
import com.zhigang.platform.domain.PlatformModels.ReleaseDiff;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Profile("!mysql")
@Repository
public class InMemoryGovernanceRepository implements GovernanceRepository {
    private final Map<Long, ChangeProposal> proposals = new ConcurrentHashMap<>();
    private final Map<Long, GraphRelease> releases = new ConcurrentHashMap<>();
    private final AtomicLong proposalSequence = new AtomicLong(100);
    private final AtomicLong releaseSequence = new AtomicLong(10);

    public InMemoryGovernanceRepository() {
        seed();
    }

    @Override
    public List<ChangeProposal> proposals() {
        return proposals.values().stream().sorted((left, right) -> right.createdAt().compareTo(left.createdAt())).toList();
    }

    @Override
    public ChangeProposal create(ChangeProposalRequest request) {
        long id = proposalSequence.incrementAndGet();
        ChangeProposal proposal = new ChangeProposal(id, request.title(), request.type(), "PENDING_REVIEW", "岗位研究员",
                null, request.reason(), List.copyOf(request.changes()), OffsetDateTime.now(), null);
        proposals.put(id, proposal);
        return proposal;
    }

    @Override
    public ChangeProposal review(long proposalId, ReviewRequest request) {
        ChangeProposal current = proposal(proposalId);
        if (current.creator().equals(request.reviewer())) {
            throw new BusinessException("SELF_REVIEW_FORBIDDEN", "创建人不能独立审核自己的提案");
        }
        String status = "APPROVE".equalsIgnoreCase(request.decision()) ? "APPROVED" : "REJECTED";
        ChangeProposal reviewed = new ChangeProposal(current.id(), current.title(), current.type(), status,
                current.creator(), request.reviewer(), current.reason(), current.changes(), current.createdAt(),
                OffsetDateTime.now());
        proposals.put(proposalId, reviewed);
        return reviewed;
    }

    @Override
    public List<GraphRelease> releases() {
        return releases.values().stream().sorted((left, right) -> right.publishedAt().compareTo(left.publishedAt())).toList();
    }

    @Override
    public GraphRelease currentRelease() {
        return releases.values().stream().filter(release -> "PUBLISHED".equals(release.status()))
                .findFirst().orElseThrow(() -> new BusinessException("CURRENT_RELEASE_NOT_FOUND", "当前没有生效版本"));
    }

    @Override
    public GraphRelease publish(GraphReleaseRequest request, String policyVersion) {
        if (releases.values().stream().anyMatch(release -> release.version().equals(request.version()))) {
            throw new BusinessException("RELEASE_VERSION_EXISTS", "图谱版本号已存在，请使用新的版本号");
        }
        List<ChangeProposal> selected = request.proposalIds() == null ? List.of()
                : request.proposalIds().stream().map(this::proposal).toList();
        if (selected.stream().anyMatch(proposal -> !"APPROVED".equals(proposal.status()))) {
            throw new BusinessException("PROPOSAL_NOT_APPROVED", "图谱发布只能包含已审核通过的变更提案");
        }
        archivePublishedReleases();
        long id = releaseSequence.incrementAndGet();
        List<String> changes = selected.stream().flatMap(proposal -> proposal.changes().stream()).toList();
        GraphRelease release = new GraphRelease(id, request.version(), "PUBLISHED", request.snapshotId(), policyVersion,
                request.publisher(), changes, OffsetDateTime.now());
        releases.put(id, release);
        return release;
    }

    @Override
    public GraphRelease rollback(long releaseId, String publisher) {
        GraphRelease target = release(releaseId);
        if ("PUBLISHED".equals(target.status())) {
            throw new BusinessException("RELEASE_ALREADY_PUBLISHED", "该版本已经是当前生效版本");
        }
        archivePublishedReleases();
        GraphRelease restored = new GraphRelease(target.id(), target.version(), "PUBLISHED", target.snapshotId(),
                target.policyVersion(), publisher, target.changes(), OffsetDateTime.now());
        releases.put(target.id(), restored);
        return restored;
    }

    @Override
    public ReleaseDiff diff(long releaseId) {
        GraphRelease current = release(releaseId);
        GraphRelease previous = releases.values().stream().filter(release -> release.id() < releaseId)
                .max((left, right) -> Long.compare(left.id(), right.id())).orElse(current);
        return diff(previous, current);
    }

    @Override
    public CurrentReleaseSummary currentSummary() {
        GraphRelease current = currentRelease();
        return new CurrentReleaseSummary(current.version(), "当前生效岗位能力图谱", 0,
                current.policyVersion(), current.publishedAt());
    }

    private ReleaseDiff diff(GraphRelease previous, GraphRelease current) {
        return new ReleaseDiff(previous.version(), current.version(),
                current.changes().stream().filter(change -> change.startsWith("新增")).toList(),
                current.changes().stream().filter(change -> change.startsWith("修改") || change.startsWith("增强")).toList(),
                current.changes().stream().filter(change -> change.startsWith("弱化")).toList(),
                current.changes().stream().filter(change -> change.startsWith("删除")).toList());
    }

    private ChangeProposal proposal(long id) {
        ChangeProposal proposal = proposals.get(id);
        if (proposal == null) throw new BusinessException("PROPOSAL_NOT_FOUND", "未找到变更提案");
        return proposal;
    }

    private GraphRelease release(long id) {
        GraphRelease release = releases.get(id);
        if (release == null) throw new BusinessException("RELEASE_NOT_FOUND", "未找到图谱版本");
        return release;
    }

    private void archivePublishedReleases() {
        releases.replaceAll((id, release) -> "PUBLISHED".equals(release.status())
                ? new GraphRelease(release.id(), release.version(), "ARCHIVED", release.snapshotId(),
                release.policyVersion(), release.publisher(), release.changes(), release.publishedAt()) : release);
    }

    private void seed() {
        OffsetDateTime now = OffsetDateTime.now();
        ChangeProposal proposal = new ChangeProposal(99, "Java 岗位能力更新", "ROLE_EVOLUTION", "APPROVED",
                "岗位研究员", "审核员", "双窗口证据支持", List.of("增强：Docker 与 Kubernetes"),
                now.minusDays(2), now.minusDays(1));
        proposals.put(proposal.id(), proposal);
        releases.put(9L, new GraphRelease(9, "2025.12", "ARCHIVED", "snapshot-2025-12", "v0.9.0",
                "系统发布", List.of("基线：Java 岗位能力图谱"), now.minusMonths(6)));
        releases.put(10L, new GraphRelease(10, "2026.07", "PUBLISHED", "demo-snapshot-2026-07", "v1.0.0",
                "系统发布", List.of("增强：Docker 与 Kubernetes"), now.minusHours(12)));
    }
}
