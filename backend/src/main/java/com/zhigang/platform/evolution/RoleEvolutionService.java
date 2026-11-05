package com.zhigang.platform.evolution;

import com.zhigang.platform.common.BusinessException;
import com.zhigang.platform.evolution.RoleEvolutionModels.DraftUpdate;
import com.zhigang.platform.evolution.RoleEvolutionModels.PublishCommand;
import com.zhigang.platform.evolution.RoleEvolutionModels.RoleIdentity;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunView;
import org.springframework.stereotype.Service;

@Service
public class RoleEvolutionService {
    private final RoleEvolutionRepository repository;
    private final RoleEvolutionStatisticsEngine statisticsEngine;

    public RoleEvolutionService(RoleEvolutionRepository repository, RoleEvolutionStatisticsEngine statisticsEngine) {
        this.repository = repository;
        this.statisticsEngine = statisticsEngine;
    }

    public RunView start(long roleId) {
        RoleIdentity role = repository.role(roleId);
        return repository.save(statisticsEngine.analyze(role.id(), role.code(), role.name(),
                repository.sourceDocuments(role.code())));
    }

    public RunView latest(long roleId) {
        repository.role(roleId);
        return repository.latest(roleId)
                .orElseThrow(() -> new BusinessException("ROLE_EVOLUTION_RUN_NOT_FOUND",
                        "该岗位尚未生成演化运行，请由岗位编辑者执行一次重新计算"));
    }

    public RunView get(long runId) {
        return repository.find(runId)
                .orElseThrow(() -> new BusinessException("ROLE_EVOLUTION_RUN_NOT_FOUND", "未找到岗位演化运行"));
    }

    public RunView updateDraft(long runId, DraftUpdate update) {
        if (update.reason() == null || update.reason().isBlank()) {
            throw new BusinessException("EVOLUTION_REVISION_REASON_REQUIRED", "人工优化必须填写修改原因");
        }
        if (update.editor() == null || update.editor().isBlank()) {
            throw new BusinessException("EVOLUTION_EDITOR_REQUIRED", "人工优化必须记录操作人");
        }
        return repository.updateDraft(runId, update);
    }

    public RunView publish(long runId, PublishCommand command) {
        if (command.version() == null || command.version().isBlank()) {
            throw new BusinessException("RELEASE_VERSION_REQUIRED", "请填写图谱版本号");
        }
        if (command.publisher() == null || command.publisher().isBlank()) {
            throw new BusinessException("PUBLISHER_REQUIRED", "请填写发布操作人");
        }
        return repository.publish(runId, command.expectedVersion(), command.version().trim(), command.publisher().trim());
    }
}
