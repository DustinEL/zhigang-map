package com.zhigang.platform.emerging;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDraft;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDetail;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateSeed;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryCandidate;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryRun;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Profile("!mysql")
@Repository
public class InMemoryEmergingDiscoveryRepository implements EmergingDiscoveryRepository {
    private static final List<String> OPEN_STATUSES = List.of(
            "WAITING_GENERATION", "EVIDENCE_INSUFFICIENT", "EMERGING_OBSERVATION", "AUTO_PUBLISH_READY",
            "DRAFT", "PENDING_REVIEW", "APPROVED");
    private final AtomicLong runSequence = new AtomicLong();
    private final AtomicLong candidateSequence = new AtomicLong();
    private final Map<Long, DiscoveryRun> runs = new ConcurrentHashMap<>();
    private final Map<Long, DiscoveryCandidate> candidates = new ConcurrentHashMap<>();

    @Override
    public DiscoveryRun createRun(String runCode, String policyVersion, String policyHash, String inputHash,
                                  String embeddingModel, int documentCount, String requestedBy) {
        long id = runSequence.incrementAndGet();
        DiscoveryRun run = new DiscoveryRun(id, runCode, "PENDING", policyVersion, policyHash, inputHash,
                embeddingModel, null, "", documentCount, 0, 0, 0, "", "", requestedBy,
                null, null, OffsetDateTime.now());
        runs.put(id, run);
        return run;
    }

    @Override
    public void markRunning(long runId) {
        DiscoveryRun run = run(runId);
        runs.put(runId, copyRun(run, "RUNNING", null, "", "", 0, 0, 0, OffsetDateTime.now(), null));
    }

    @Override
    public void complete(long runId, int embeddingDimension, String clusterModel, int clusterCount,
                         int candidateCount, int noiseCount, List<CandidateSeed> seeds) {
        DiscoveryRun run = run(runId);
        runs.put(runId, copyRun(run, "COMPLETED", embeddingDimension, clusterModel, "", clusterCount,
                candidateCount, noiseCount, run.startedAt(), OffsetDateTime.now()));
        for (CandidateSeed seed : seeds) {
            long id = candidateSequence.incrementAndGet();
            List<String> technologyStack = java.util.stream.Stream.concat(
                    seed.requiredSkills().stream(), seed.bonusSkills().stream()).distinct().toList();
            candidates.put(id, new DiscoveryCandidate(id, runId, 1, seed.candidateName(), "未明确", technologyStack,
                    seed.nearestRoleName(),
                    seed.nearestRoleSimilarity(), seed.emergenceScore(), seed.uncertainty(), seed.documentCount(),
                    seed.companyCount(), seed.sourceCount(), seed.medianProbability(), seed.status(),
                    seed.generationMode(), seed.metrics(), seed.responsibilities(), seed.requiredSkills(),
                    seed.bonusSkills(), seed.scenarios(), List.of(), seed.opposingEvidence(), "", "", OffsetDateTime.now()));
        }
        long latestCompletedRunId = runs.values().stream().filter(item -> "COMPLETED".equals(item.status()))
                .mapToLong(DiscoveryRun::id).max().orElse(runId);
        candidates.replaceAll((id, candidate) -> candidate.runId() != latestCompletedRunId
                && "COMPLETED".equals(run(candidate.runId()).status())
                && OPEN_STATUSES.contains(candidate.status())
                ? copyCandidate(candidate, candidate.version(), candidate.name(), candidate.jobLevel(),
                candidate.technologyStack(), "SUPERSEDED",
                candidate.responsibilities(), candidate.requiredSkills(), candidate.bonusSkills(),
                candidate.scenarios(), candidate.creator(), candidate.reviewer()) : candidate);
    }

    @Override
    public void fail(long runId, String errorCode, String errorMessage) {
        DiscoveryRun run = run(runId);
        runs.put(runId, new DiscoveryRun(run.id(), run.runCode(), "FAILED", run.policyVersion(), run.policyHash(),
                run.inputHash(), run.embeddingModel(), run.embeddingDimension(), run.clusterModel(), run.documentCount(),
                run.clusterCount(), run.candidateCount(), run.noiseCount(), errorCode, errorMessage, run.requestedBy(),
                run.startedAt(), OffsetDateTime.now(), run.createdAt()));
    }

    @Override public List<DiscoveryRun> findRuns() { return runs.values().stream().sorted((a, b) -> Long.compare(b.id(), a.id())).toList(); }
    @Override public Optional<DiscoveryRun> findRun(long runId) { return Optional.ofNullable(runs.get(runId)); }
    @Override public List<DiscoveryCandidate> findCandidates() {
        return candidates.values().stream().filter(candidate -> !"SUPERSEDED".equals(candidate.status()))
                .sorted((left, right) -> Long.compare(right.id(), left.id())).toList();
    }
    @Override public Optional<DiscoveryCandidate> findCandidate(long candidateId) { return Optional.ofNullable(candidates.get(candidateId)); }

    @Override
    public CandidateDetail findCandidateDetail(long candidateId) {
        return new CandidateDetail(candidate(candidateId), List.of(), List.of());
    }

    @Override
    public DiscoveryCandidate updateDraft(long candidateId, CandidateDraft draft) {
        DiscoveryCandidate current = candidate(candidateId);
        requireVersion(current, draft.expectedVersion());
        DiscoveryCandidate updated = copyCandidate(current, current.version() + 1, draft.name(), draft.jobLevel(),
                draft.technologyStack(), "DRAFT",
                draft.responsibilities(), draft.requiredSkills(), draft.bonusSkills(), draft.scenarios(), draft.editor(), current.reviewer());
        candidates.put(candidateId, updated);
        return updated;
    }

    @Override
    public DiscoveryCandidate submit(long candidateId, int expectedVersion, String creator) {
        DiscoveryCandidate current = candidate(candidateId);
        requireVersion(current, expectedVersion);
        if (!"RAG_SCHEMA_EVIDENCE".equals(current.generationMode())) {
            throw new BusinessException("CANDIDATE_GENERATION_REQUIRED", "候选尚未完成受证据约束的岗位定义生成");
        }
        DiscoveryCandidate updated = copyCandidate(current, current.version(), current.name(), current.jobLevel(),
                current.technologyStack(), "PENDING_REVIEW",
                current.responsibilities(), current.requiredSkills(), current.bonusSkills(), current.scenarios(), creator, current.reviewer());
        candidates.put(candidateId, updated);
        return updated;
    }

    @Override
    public DiscoveryCandidate review(long candidateId, int expectedVersion, String decision, String reviewer, String comment) {
        DiscoveryCandidate current = candidate(candidateId);
        requireVersion(current, expectedVersion);
        if (reviewer.equals(current.creator())) throw new BusinessException("SELF_REVIEW_FORBIDDEN", "创建人不能审核自己的候选");
        String status = "APPROVE".equalsIgnoreCase(decision) ? "APPROVED" : "REJECTED";
        DiscoveryCandidate updated = copyCandidate(current, current.version(), current.name(), current.jobLevel(),
                current.technologyStack(), status,
                current.responsibilities(), current.requiredSkills(), current.bonusSkills(), current.scenarios(), current.creator(), reviewer);
        candidates.put(candidateId, updated);
        return updated;
    }

    @Override
    public DiscoveryCandidate publish(long candidateId, int expectedVersion, String version, String publisher,
                                      EmergingDiscoveryPolicy policy) {
        throw new BusinessException("MYSQL_REQUIRED", "新岗位发布必须在 MySQL 真实环境执行");
    }

    private DiscoveryRun run(long id) { return findRun(id).orElseThrow(() -> new BusinessException("DISCOVERY_RUN_NOT_FOUND", "未找到发现运行")); }
    private DiscoveryCandidate candidate(long id) { return findCandidate(id).orElseThrow(() -> new BusinessException("EMERGING_CANDIDATE_NOT_FOUND", "未找到新岗位候选")); }
    private void requireVersion(DiscoveryCandidate candidate, int version) { if (candidate.version() != version) throw new BusinessException("CANDIDATE_VERSION_CONFLICT", "候选已被其他人修改，请刷新后重试"); }

    private DiscoveryRun copyRun(DiscoveryRun run, String status, Integer dimension, String model, String error,
                                 int clusters, int candidateCount, int noise, OffsetDateTime started, OffsetDateTime completed) {
        return new DiscoveryRun(run.id(), run.runCode(), status, run.policyVersion(), run.policyHash(), run.inputHash(),
                run.embeddingModel(), dimension, model, run.documentCount(), clusters, candidateCount, noise,
                error.isBlank() ? "" : "DISCOVERY_FAILED", error, run.requestedBy(), started, completed, run.createdAt());
    }

    private DiscoveryCandidate copyCandidate(DiscoveryCandidate value, int version, String name, String jobLevel,
                                             List<String> technologyStack, String status,
                                             List<String> responsibilities, List<String> required, List<String> bonus,
                                             List<String> scenarios, String creator, String reviewer) {
        return new DiscoveryCandidate(value.id(), value.runId(), version, name, jobLevel, technologyStack, value.nearestRole(),
                value.nearestRoleSimilarity(), value.emergenceScore(), value.uncertainty(), value.documentCount(),
                value.companyCount(), value.sourceCount(), value.clusterStability(), status, value.generationMode(),
                value.metrics(), responsibilities, required, bonus, scenarios, value.supportingEvidence(),
                value.opposingEvidence(), creator, reviewer, OffsetDateTime.now());
    }
}
