package com.agentforge.app.api;

import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.health.InfrastructureStatus;
import com.agentforge.infrastructure.health.InfrastructureStatusService;
import com.agentforge.model.ModelGatewayCatalog;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/system")
public class SystemController {

    private final ModelGatewayCatalog modelGatewayCatalog;
    private final AgentRuntimeProperties agentProperties;
    private final AgentDefinitionService agentService;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeManager runtimeManager;
    private final InfrastructureStatusService infrastructureStatusService;

    public SystemController(
            ModelGatewayCatalog modelGatewayCatalog,
            AgentRuntimeProperties agentProperties,
            AgentDefinitionService agentService,
            ToolRegistry toolRegistry,
            AgentRuntimeManager runtimeManager,
            InfrastructureStatusService infrastructureStatusService) {
        this.modelGatewayCatalog = modelGatewayCatalog;
        this.agentProperties = agentProperties;
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
        this.runtimeManager = runtimeManager;
        this.infrastructureStatusService = infrastructureStatusService;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.fromCallable(this::buildHealth).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildHealth() {
        InfrastructureStatus infrastructure = infrastructureStatusService.check();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "AgentForge");
        result.put("version", "0.3.0-SNAPSHOT");
        result.put("status", infrastructure.ready() ? "UP" : "DEGRADED");
        result.put("milestone", "V0.3-session-message-persistence");
        result.put("mysql", infrastructure.mysqlUp() ? "UP" : "DOWN");
        result.put("redis", infrastructure.redisUp() ? "UP" : "DOWN");
        result.put("activeModelProvider", modelGatewayCatalog.activeProvider());
        result.put("registeredModelProviders", modelGatewayCatalog.registeredProviders());
        result.put("agentRuntimeEnabled", agentProperties.isEnabled());
        result.put("agentDefinitions", agentService.list().size());
        result.put("registeredTools", toolRegistry.descriptors().size());
        result.put("cachedAgentRuntimes", runtimeManager.cachedRuntimeCount());
        return result;
    }
}
