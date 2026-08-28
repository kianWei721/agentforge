package com.agentforge.app.api;

import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.model.ModelGatewayCatalog;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/system")
public class SystemController {

    private final ModelGatewayCatalog modelGatewayCatalog;
    private final AgentRuntimeProperties agentProperties;
    private final AgentDefinitionService agentService;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeManager runtimeManager;

    public SystemController(
            ModelGatewayCatalog modelGatewayCatalog,
            AgentRuntimeProperties agentProperties,
            AgentDefinitionService agentService,
            ToolRegistry toolRegistry,
            AgentRuntimeManager runtimeManager) {
        this.modelGatewayCatalog = modelGatewayCatalog;
        this.agentProperties = agentProperties;
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
        this.runtimeManager = runtimeManager;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "AgentForge");
        result.put("version", "0.2.0-SNAPSHOT");
        result.put("status", "UP");
        result.put("milestone", "V0.2-agent-crud-tool-calling");
        result.put("activeModelProvider", modelGatewayCatalog.activeProvider());
        result.put("registeredModelProviders", modelGatewayCatalog.registeredProviders());
        result.put("agentRuntimeEnabled", agentProperties.isEnabled());
        result.put("agentDefinitions", agentService.list().size());
        result.put("registeredTools", toolRegistry.descriptors().size());
        result.put("cachedAgentRuntimes", runtimeManager.cachedRuntimeCount());
        return result;
    }
}
