package com.agentforge.app.api;

import com.agentforge.agent.AgentCreateCommand;
import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.agent.AgentUpdateCommand;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.core.security.AccessDeniedException;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.tool.ToolRegistry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/agents")
public class AgentManagementController {

    private final AgentDefinitionService agentService;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeManager runtimeManager;

    public AgentManagementController(
            AgentDefinitionService agentService,
            ToolRegistry toolRegistry,
            AgentRuntimeManager runtimeManager) {
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
        this.runtimeManager = runtimeManager;
    }

    @GetMapping
    public Mono<List<AgentDefinition>> list() {
        return blocking(agentService::list);
    }

    @GetMapping("/{agentId}")
    public Mono<AgentDefinition> get(@PathVariable("agentId") String agentId) {
        return blocking(() -> agentService.get(agentId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AgentDefinition> create(
            @Valid @RequestBody AgentCreateRequest request, AccessPrincipal principal) {
        AccessDeniedException.requireStaff(principal, "creating agents");
        return blocking(
                () -> {
                    validateTools(request.toolNames());
                    return agentService.create(
                            new AgentCreateCommand(
                                    request.name(),
                                    request.description(),
                                    request.systemPrompt(),
                                    request.modelProvider(),
                                    request.modelName(),
                                    request.toolNames(),
                                    request.enabled()));
                });
    }

    @PutMapping("/{agentId}")
    public Mono<AgentDefinition> update(
            @PathVariable("agentId") String agentId,
            @Valid @RequestBody AgentUpdateRequest request,
            AccessPrincipal principal) {
        AccessDeniedException.requireStaff(principal, "updating agents");
        return blocking(
                () -> {
                    validateTools(request.toolNames());
                    AgentDefinition updated =
                            agentService.update(
                                    agentId,
                                    new AgentUpdateCommand(
                                            request.name(),
                                            request.description(),
                                            request.systemPrompt(),
                                            request.modelProvider(),
                                            request.modelName(),
                                            request.toolNames(),
                                            request.enabled()));
                    runtimeManager.invalidate(agentId);
                    return updated;
                });
    }

    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable("agentId") String agentId, AccessPrincipal principal) {
        AccessDeniedException.requireStaff(principal, "deleting agents");
        return blocking(
                        () -> {
                            agentService.delete(agentId);
                            runtimeManager.invalidate(agentId);
                            return true;
                        })
                .then();
    }

    private void validateTools(List<String> toolNames) {
        toolRegistry.resolve(toolNames == null ? List.of() : toolNames);
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}
