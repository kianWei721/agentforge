package com.agentforge.app.api;

import com.agentforge.core.AgentForgeConstants;
import com.agentforge.model.ModelGatewayCatalog;
import com.agentforge.runtime.AgentRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/system")
public class SystemController {
    private final ModelGatewayCatalog modelGatewayCatalog;
    private final ObjectProvider<AgentRuntime> agentRuntimeProvider;
    public SystemController(ModelGatewayCatalog modelGatewayCatalog, ObjectProvider<AgentRuntime> agentRuntimeProvider) { this.modelGatewayCatalog = modelGatewayCatalog; this.agentRuntimeProvider = agentRuntimeProvider; }
    @GetMapping("/health")
    public Map<String,Object> health() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("name","AgentForge"); result.put("version","0.1.0-SNAPSHOT"); result.put("status","UP");
        result.put("milestone","V0.1-real-model-and-agent-runtime"); result.put("activeModelProvider",modelGatewayCatalog.activeProvider());
        result.put("registeredModelProviders",modelGatewayCatalog.registeredProviders()); result.put("agentRuntimeEnabled",agentRuntimeProvider.getIfAvailable()!=null);
        return result;
    }
}
