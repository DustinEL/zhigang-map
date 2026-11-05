package com.zhigang.platform.evolution;

import com.zhigang.platform.evolution.RoleEvolutionModels.DraftUpdate;
import com.zhigang.platform.evolution.RoleEvolutionModels.RoleIdentity;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunDraft;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunView;
import com.zhigang.platform.evolution.RoleEvolutionModels.SourceDocument;

import java.util.List;
import java.util.Optional;

public interface RoleEvolutionRepository {
    RoleIdentity role(long roleId);

    List<SourceDocument> sourceDocuments(String roleCode);

    RunView save(RunDraft draft);

    Optional<RunView> latest(long roleId);

    Optional<RunView> find(long runId);

    RunView updateDraft(long runId, DraftUpdate update);

    RunView publish(long runId, int expectedVersion, String releaseVersion, String publisher);
}
