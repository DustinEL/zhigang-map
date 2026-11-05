package com.zhigang.platform.emerging;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDiscovery;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDraft;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryEvidence;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryFieldEvidence;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Profile("!mysql")
@Repository
public class InMemoryEmergingQueryRepository implements EmergingQueryRepository {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private final AtomicLong discoverySequence = new AtomicLong();
    private final AtomicLong evidenceSequence = new AtomicLong();
    private final Map<Long, QueryDiscovery> discoveries = new ConcurrentHashMap<>();

    @Override
    public Optional<QueryDiscovery> findByNormalizedQuery(String normalizedQuery) {
        return discoveries.values().stream()
                .filter(value -> value.normalizedQuery().equals(normalizedQuery))
                .findFirst();
    }

    @Override
    public Optional<QueryDiscovery> findById(long id) {
        return Optional.ofNullable(discoveries.get(id));
    }

    @Override
    public List<QueryDiscovery> findRecent(int limit) {
        return discoveries.values().stream()
                .sorted(Comparator.comparing(QueryDiscovery::updatedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 50)))
                .toList();
    }

    @Override
    public synchronized QueryDiscovery save(QueryDraft draft) {
        Optional<QueryDiscovery> existing = findByNormalizedQuery(draft.normalizedQuery());
        if (existing.isPresent() && existing.get().publishedRoleId() != null) return existing.get();
        long id = existing.map(QueryDiscovery::id).orElseGet(discoverySequence::incrementAndGet);
        OffsetDateTime now = OffsetDateTime.now(OFFSET);
        Map<String, QueryEvidence> byKey = new LinkedHashMap<>();
        for (var item : draft.evidence()) {
            QueryEvidence evidence = new QueryEvidence(evidenceSequence.incrementAndGet(), item.key(), item.title(),
                    item.source(), item.domain(), item.url(), item.publishedAt(), item.excerpt());
            byKey.put(item.key(), evidence);
        }
        List<QueryFieldEvidence> fieldEvidence = new ArrayList<>();
        for (var item : draft.fieldEvidence()) {
            QueryEvidence evidence = byKey.get(item.evidenceKey());
            if (evidence != null) fieldEvidence.add(new QueryFieldEvidence(item.fieldName(), item.fieldValue(),
                    evidence.id(), evidence.key()));
        }
        QueryDiscovery value = new QueryDiscovery(id, draft.query(), draft.normalizedQuery(), draft.status(),
                draft.domain(), draft.name(), draft.definition(), draft.responsibilities(), draft.requiredSkills(),
                draft.bonusSkills(), draft.scenarios(), draft.nearestRoleId(), draft.nearestRoleName(),
                draft.nearestRoleSimilarity(), draft.noveltyScore(), byKey.size(),
                (int) byKey.values().stream().map(QueryEvidence::domain).distinct().count(),
                draft.fieldEvidenceComplete(), draft.webSearchUsed(), draft.publishable(), draft.modelName(),
                draft.errorCode(), draft.errorMessage(), null, null, List.copyOf(byKey.values()),
                List.copyOf(fieldEvidence), false, 0, "", null,
                existing.map(QueryDiscovery::createdAt).orElse(now), now);
        discoveries.put(id, value);
        return value;
    }

    @Override
    public synchronized QueryDiscovery publish(long discoveryId, String publisher) {
        QueryDiscovery current = findById(discoveryId)
                .orElseThrow(() -> new BusinessException("QUERY_DISCOVERY_NOT_FOUND", "未找到查询发现结果"));
        if (current.publishedRoleId() != null) return current;

        // 验证：名称和定义不能为空（领域验证在Service层完成）
        if (current.name() == null || current.name().isBlank()
                || current.definition() == null || current.definition().isBlank()) {
            throw new BusinessException("QUERY_DISCOVERY_PUBLISH_GATE_FAILED", "岗位名称或定义为空，无法加入图谱");
        }

        OffsetDateTime now = OffsetDateTime.now(OFFSET);
        QueryDiscovery published = new QueryDiscovery(current.id(), current.query(), current.normalizedQuery(),
                current.status(), current.domain(), current.name(), current.definition(), current.responsibilities(),
                current.requiredSkills(), current.bonusSkills(), current.scenarios(), current.nearestRoleId(),
                current.nearestRoleName(), current.nearestRoleSimilarity(), current.noveltyScore(),
                current.evidenceCount(), current.sourceCount(), current.fieldEvidenceComplete(), current.webSearchUsed(),
                current.publishable(), current.modelName(), current.errorCode(), current.errorMessage(),
                100000L + current.id(), 100000L + current.id(), current.evidence(), current.fieldEvidence(),
                false, 0, "", now, current.createdAt(), now);
        discoveries.put(discoveryId, published);
        return published;
    }

    @Override
    public synchronized QueryDiscovery removeFromGraph(long discoveryId, String operator) {
        QueryDiscovery current = findById(discoveryId)
                .orElseThrow(() -> new BusinessException("QUERY_DISCOVERY_NOT_FOUND", "未找到查询发现结果"));
        if (current.publishedRoleId() == null) {
            throw new BusinessException("QUERY_DISCOVERY_NOT_PUBLISHED", "该岗位尚未加入图谱");
        }
        OffsetDateTime now = OffsetDateTime.now(OFFSET);
        QueryDiscovery removed = new QueryDiscovery(current.id(), current.query(), current.normalizedQuery(),
                current.status(), current.domain(), current.name(), current.definition(), current.responsibilities(),
                current.requiredSkills(), current.bonusSkills(), current.scenarios(), current.nearestRoleId(),
                current.nearestRoleName(), current.nearestRoleSimilarity(), current.noveltyScore(),
                current.evidenceCount(), current.sourceCount(), current.fieldEvidenceComplete(), current.webSearchUsed(),
                current.publishable(), current.modelName(), current.errorCode(), current.errorMessage(), null, null,
                current.evidence(), current.fieldEvidence(), false, 0, "", null, current.createdAt(), now);
        discoveries.put(discoveryId, removed);
        return removed;
    }
}
