package com.zhigang.platform.emerging;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDraft;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateDetail;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidatePublish;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.CandidateReview;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryCandidate;
import com.zhigang.platform.emerging.EmergingDiscoveryModels.DiscoveryRun;
import com.zhigang.platform.repository.PlatformDataProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EmergingDiscoveryService {
    private final EmergingDiscoveryRepository repository;
    private final EmergingDiscoveryExecutor executor;
    private final EmergingDiscoveryPolicy policy;
    private final PlatformDataProvider dataProvider;
    private final EmbeddingClient embeddingClient;

    public EmergingDiscoveryService(EmergingDiscoveryRepository repository, EmergingDiscoveryExecutor executor,
                                    EmergingDiscoveryPolicy policy, PlatformDataProvider dataProvider,
                                    EmbeddingClient embeddingClient) {
        this.repository = repository;
        this.executor = executor;
        this.policy = policy;
        this.dataProvider = dataProvider;
        this.embeddingClient = embeddingClient;
    }

    public DiscoveryRun start(String requestedBy) {
        List<RawDocument> documents = eligibleDocuments();
        if (documents.size() < policy.clustering().minimumClusterSize()) {
            throw new BusinessException("DISCOVERY_DATA_INSUFFICIENT", "已发布真实 JD 少于聚类最低样本数");
        }
        String inputHash = hashDocuments(documents);
        DiscoveryRun run = repository.createRun("emerging-" + UUID.randomUUID().toString().substring(0, 12),
                policy.version(), policy.hash(), inputHash, embeddingClient.model(), documents.size(), cleanActor(requestedBy));
        executor.execute(run.id(), documents, dataProvider.findPublishedBaselineJobRoles(),
                dataProvider.findDocumentSemanticFacts());
        return run;
    }

    public List<DiscoveryRun> runs() {
        return repository.findRuns();
    }

    public DiscoveryRun run(long id) {
        return repository.findRun(id)
                .orElseThrow(() -> new BusinessException("DISCOVERY_RUN_NOT_FOUND", "未找到发现运行"));
    }

    public List<DiscoveryCandidate> candidates() {
        return repository.findCandidates();
    }

    public DiscoveryCandidate candidate(long id) {
        return repository.findCandidate(id)
                .orElseThrow(() -> new BusinessException("EMERGING_CANDIDATE_NOT_FOUND", "未找到新岗位候选"));
    }

    public CandidateDetail candidateDetail(long id) {
        candidate(id);
        return repository.findCandidateDetail(id);
    }

    public DiscoveryCandidate update(long id, CandidateDraft draft) {
        requireDraft(draft);
        return repository.updateDraft(id, new CandidateDraft(draft.expectedVersion(), draft.name().trim(),
                draft.jobLevel() == null ? "未明确" : draft.jobLevel().trim(), cleanValues(draft.technologyStack()),
                cleanValues(draft.responsibilities()), cleanValues(draft.requiredSkills()), cleanValues(draft.bonusSkills()),
                cleanValues(draft.scenarios()), draft.reason().trim(), draft.evidenceIds() == null ? List.of() : draft.evidenceIds(),
                cleanActor(draft.editor())));
    }

    public DiscoveryCandidate submit(long id, int expectedVersion, String creator) {
        return repository.submit(id, expectedVersion, cleanActor(creator));
    }

    public DiscoveryCandidate review(long id, int expectedVersion, CandidateReview review) {
        if (!List.of("APPROVE", "REJECT").contains(review.decision().toUpperCase())) {
            throw new BusinessException("INVALID_REVIEW_DECISION", "审核决定必须为 APPROVE 或 REJECT");
        }
        return repository.review(id, expectedVersion, review.decision(), cleanActor(review.reviewer()), review.comment());
    }

    public DiscoveryCandidate publish(long id, int expectedVersion, CandidatePublish publish) {
        if (publish.version() == null || publish.version().isBlank()) throw new BusinessException("RELEASE_VERSION_REQUIRED", "请填写图谱版本号");
        return repository.publish(id, expectedVersion, publish.version().trim(), cleanActor(publish.publisher()), policy);
    }

    private List<RawDocument> eligibleDocuments() {
        return deduplicateEligible(dataProvider.findPublishedDiscoveryDocuments());
    }

    static List<RawDocument> deduplicateEligible(List<RawDocument> documents) {
        Map<String, RawDocument> unique = new LinkedHashMap<>();
        documents.stream()
                .filter(document -> !document.demoData() && document.id() > 0 && document.content() != null
                        && !document.content().isBlank() && document.contentHash() != null && !document.contentHash().isBlank())
                .sorted(java.util.Comparator.comparingLong(RawDocument::id))
                .forEach(document -> unique.putIfAbsent(document.contentHash().trim().toLowerCase(java.util.Locale.ROOT), document));
        return List.copyOf(unique.values());
    }

    private String hashDocuments(List<RawDocument> documents) {
        String value = documents.stream().sorted(java.util.Comparator.comparingLong(RawDocument::id))
                .map(document -> document.id() + ":" + document.contentHash()).collect(java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private void requireDraft(CandidateDraft draft) {
        if (draft == null || draft.name() == null || draft.name().isBlank() || draft.jobLevel() == null
                || draft.jobLevel().isBlank() || draft.reason() == null || draft.reason().isBlank()) {
            throw new BusinessException("CANDIDATE_DRAFT_INVALID", "岗位名称、职级和修改原因不能为空");
        }
        if (cleanValues(draft.responsibilities()).isEmpty() || cleanValues(draft.requiredSkills()).isEmpty()
                || cleanValues(draft.technologyStack()).isEmpty() || cleanValues(draft.scenarios()).isEmpty()) {
            throw new BusinessException("CANDIDATE_DRAFT_INVALID", "职责、技术栈、必备技能和典型场景不能为空");
        }
        if (draft.evidenceIds() == null || draft.evidenceIds().isEmpty()) {
            throw new BusinessException("CANDIDATE_REVISION_EVIDENCE_REQUIRED", "字段修改必须选择至少一条有效原文证据");
        }
    }

    private List<String> cleanValues(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private String cleanActor(String actor) {
        if (actor == null || actor.isBlank()) throw new BusinessException("ACTOR_REQUIRED", "操作人不能为空");
        return actor.trim();
    }
}
