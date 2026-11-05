package com.zhigang.platform.api;

import com.zhigang.platform.common.ApiResponse;
import com.zhigang.platform.evolution.RoleEvolutionModels.DraftUpdate;
import com.zhigang.platform.evolution.RoleEvolutionModels.PublishCommand;
import com.zhigang.platform.evolution.RoleEvolutionModels.RunView;
import com.zhigang.platform.evolution.RoleEvolutionService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RoleEvolutionController {
    private final RoleEvolutionService service;

    public RoleEvolutionController(RoleEvolutionService service) {
        this.service = service;
    }

    @PostMapping("/job-roles/{roleId}/evolution-runs")
    public ApiResponse<RunView> start(@PathVariable long roleId) {
        return ApiResponse.success(service.start(roleId));
    }

    @GetMapping("/job-roles/{roleId}/evolution-runs/latest")
    public ApiResponse<RunView> latest(@PathVariable long roleId) {
        return ApiResponse.success(service.latest(roleId));
    }

    @GetMapping("/role-evolution-runs/{runId}")
    public ApiResponse<RunView> get(@PathVariable long runId) {
        return ApiResponse.success(service.get(runId));
    }

    @PutMapping("/role-evolution-runs/{runId}/draft")
    public ApiResponse<RunView> updateDraft(@PathVariable long runId, @RequestBody DraftUpdate update,
                                           Authentication authentication) {
        return ApiResponse.success(service.updateDraft(runId, new DraftUpdate(update.expectedVersion(),
                update.includedChangeIds(), update.note(), update.reason(), actor(authentication, update.editor()))));
    }

    @PostMapping("/role-evolution-runs/{runId}/publications")
    public ApiResponse<RunView> publish(@PathVariable long runId, @RequestBody PublishCommand command,
                                       Authentication authentication) {
        return ApiResponse.success(service.publish(runId, new PublishCommand(command.expectedVersion(),
                command.version(), actor(authentication, command.publisher()))));
    }

    private String actor(Authentication authentication, String requestedActor) {
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return requestedActor;
    }
}
