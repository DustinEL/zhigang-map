package com.zhigang.platform.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.analysis.SkillNormalizationService;
import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.domain.PlatformModels.JobRole;
import com.zhigang.platform.domain.PlatformModels.RawDocument;
import com.zhigang.platform.evolution.RoleEvolutionModels.ChangeView;
import com.zhigang.platform.evolution.RoleEvolutionModels.DraftUpdate;
import com.zhigang.platform.evolution.RoleEvolutionModels.EvidenceView;
import com.zhigang.platform.evolution.RoleEvolutionModels.PointView;
import com.zhigang.platform.evolution.RoleEvolutionModels.RoleIdentity;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunView;
import com.zhigang.platform.evolution.RoleEvolutionModels.SkillAnchor;
import com.zhigang.platform.evolution.RoleEvolutionModels.SourceDocument;
import com.zhigang.platform.evolution.RoleEvolutionModels.WindowView;
import com.zhigang.platform.repository.PlatformDataProvider;
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
public class InMemoryRoleEvolutionRepository implements RoleEvolutionRepository {
    private final PlatformDataProvider dataProvider;
    private final SkillNormalizationService normalizationService;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, RunView> runs = new ConcurrentHashMap<>();

    public InMemoryRoleEvolutionRepository(PlatformDataProvider dataProvider,
                                           SkillNormalizationService normalizationService,
                                           ObjectMapper ignored) {
        this.dataProvider = dataProvider;
        this.normalizationService = normalizationService;
    }

    @Override
    public RoleIdentity role(long roleId) {
        JobRole role = dataProvider.findJobRole(roleId)
                .orElseThrow(() -> new BusinessException("ROLE_NOT_FOUND", "未找到目标岗位"));
        return new RoleIdentity(role.id(), role.code(), role.name());
    }

    @Override
    public List<SourceDocument> sourceDocuments(String roleCode) {
        return dataProvider.findDocuments().stream().filter(document -> roleCode.equals(document.industry())
                        || document.jobTitle().toLowerCase().contains(roleCode.replace("_ENGINEER", "").toLowerCase()))
                .map(this::sourceDocument).toList();
    }

    private SourceDocument sourceDocument(RawDocument document) {
        List<SkillAnchor> anchors = new ArrayList<>();
        for (ExtractedFact fact : normalizationService.extract(document.content())) {
            int start = Math.max(0, document.content().indexOf(fact.rawValue()));
            int end = Math.min(document.content().length(), start + fact.rawValue().length());
            anchors.add(new SkillAnchor(null, fact.normalizedValue(), document.content().substring(start, end),
                    start, end, true, "NEGATED".equals(fact.requirementType()) ? "OPPOSE" : "SUPPORT"));
        }
        return new SourceDocument(document.id(), document.jobTitle(), document.company(), document.sourceName(), "",
                document.city(), document.level(), document.publishedAt() == null ? null : document.publishedAt().toLocalDate(), anchors);
    }

    @Override
    public RunView save(RunDraft draft) {
        Optional<RunView> existing = runs.values().stream().filter(run -> run.runCode().equals(draft.runCode())).findFirst();
        if (existing.isPresent()) return existing.get();
        long id = ids.incrementAndGet();
        OffsetDateTime now = OffsetDateTime.now();
        List<WindowView> windows = draft.windows().stream().map(window -> new WindowView(ids.incrementAndGet(),
                window.type(), window.label(), window.startDate(), window.endDate(), window.documentCount(),
                window.companyCount(), window.sourceCount(), window.sourceNames(), window.cityScope(),
                window.levelScope(), window.sampleHash())).toList();
        List<ChangeView> changes = draft.changes().stream().map(change -> new ChangeView(ids.incrementAndGet(),
                change.skillId(), change.skill(), change.changeType(), change.confidenceLevel(), change.absoluteChange(),
                change.differenceLowerBound(), change.differenceUpperBound(), change.sensitivityDirection(),
                change.consecutiveAbsentWindows(), change.publishable(), change.includedInDraft(), List.of(
                new PointView(windows.get(0).label(), change.baselineDocumentCount(), change.baselineCompanyCount(),
                        change.baselineRate(), change.baselineLowerBound(), change.baselineUpperBound()),
                new PointView(windows.get(1).label(), change.currentDocumentCount(), change.currentCompanyCount(),
                        change.currentRate(), change.currentLowerBound(), change.currentUpperBound())),
                change.evidence().stream().map(evidence -> new EvidenceView(ids.incrementAndGet(), evidence.windowType(),
                        evidence.documentId(), evidence.title(), evidence.company(), evidence.source(), evidence.sourceUrl(),
                        evidence.publishedAt(), evidence.quote(), evidence.startOffset(), evidence.endOffset(),
                        evidence.anchorValid(), evidence.direction())).toList())).toList();
        RunView run = new RunView(id, draft.runCode(), draft.roleId(), draft.roleCode(), draft.roleName(),
                draft.policyVersion(), draft.policyHash(), draft.inputHash(), draft.status(), draft.conclusionLevel(),
                draft.sampleControl(), draft.statisticsMethod(), 1, "", null, "", windows, changes, now, now, null);
        runs.put(id, run);
        return run;
    }

    @Override public Optional<RunView> latest(long roleId) { return runs.values().stream().filter(run -> run.roleId() == roleId).max(java.util.Comparator.comparing(RunView::createdAt)); }
    @Override public Optional<RunView> find(long runId) { return Optional.ofNullable(runs.get(runId)); }

    @Override
    public RunView updateDraft(long runId, DraftUpdate update) {
        RunView run = find(runId).orElseThrow();
        if (run.version() != update.expectedVersion()) throw new BusinessException("OPTIMISTIC_LOCK_FAILED", "岗位演化草稿已被更新，请刷新后重试");
        var selected = SetSupport.ids(update.includedChangeIds());
        List<ChangeView> changes = run.changes().stream().map(change -> new ChangeView(change.id(), change.skillId(),
                change.skill(), change.changeType(), change.confidenceLevel(), change.absoluteChange(),
                change.differenceLowerBound(), change.differenceUpperBound(), change.sensitivityDirection(),
                change.consecutiveAbsentWindows(), change.publishable(), change.publishable() && selected.contains(change.id()),
                change.points(), change.evidence())).toList();
        RunView updated = new RunView(run.id(), run.runCode(), run.roleId(), run.roleCode(), run.roleName(), run.policyVersion(),
                run.policyHash(), run.inputHash(), run.status(), run.conclusionLevel(), run.sampleControl(), run.statisticsMethod(),
                run.version() + 1, update.note(), run.publishedReleaseId(), run.publishedReleaseVersion(), run.windows(), changes,
                run.createdAt(), OffsetDateTime.now(), run.publishedAt());
        runs.put(runId, updated);
        return updated;
    }

    @Override
    public RunView publish(long runId, int expectedVersion, String releaseVersion, String publisher) {
        throw new BusinessException("MYSQL_REQUIRED", "岗位演化版本发布需要 MySQL 动态环境");
    }

    private static final class SetSupport {
        static java.util.Set<Long> ids(List<Long> values) {
            return values == null ? java.util.Set.of() : java.util.Set.copyOf(values);
        }
    }
}
