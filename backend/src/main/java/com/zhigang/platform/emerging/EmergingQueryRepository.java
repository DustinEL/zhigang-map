package com.zhigang.platform.emerging;

import com.zhigang.platform.emerging.EmergingQueryModels.QueryDiscovery;
import com.zhigang.platform.emerging.EmergingQueryModels.QueryDraft;

import java.util.List;
import java.util.Optional;

public interface EmergingQueryRepository {
    Optional<QueryDiscovery> findByNormalizedQuery(String normalizedQuery);

    Optional<QueryDiscovery> findById(long id);

    List<QueryDiscovery> findRecent(int limit);

    QueryDiscovery save(QueryDraft draft);

    QueryDiscovery publish(long discoveryId, String publisher);

    QueryDiscovery removeFromGraph(long discoveryId, String operator);
}
