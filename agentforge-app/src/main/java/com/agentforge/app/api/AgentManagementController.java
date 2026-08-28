package com.agentforge.app.api;

import com.agentforge.agent.AgentCreateCommand;
import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.agent.AgentUpdateCommand;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.tool.ToolRegistry;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
    public List<AgentDefinition> list() {
        return agentService.list();
    }

    @GetMapping("/{agentId}")
    public AgentDefinition get(@PathVariable String agentId) {
        return agentService.get(agentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDefinition create(@Valid @RequestBody AgentCreateRequest request) {
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
    }

    @PutMapping("/{agentId}")
    public AgentDefinition update(
            @PathVariable String agentId,
            @Valid @RequestBody AgentUpdateRequest request) {
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
    }

    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String agentId) {
        agentService.delete(agentId);
        runtimeManager.invalidate(agentId);
    }

    private void validateTools(List<String> toolNames) {
        toolRegistry.resolve(toolNames == null ? List.of() : toolNames);
    }
}
