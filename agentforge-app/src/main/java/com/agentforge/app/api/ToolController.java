package com.agentforge.app.api;

import com.agentforge.core.AgentForgeConstants;
import com.agentforge.tool.ToolDescriptor;
import com.agentforge.tool.ToolRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public List<ToolDescriptor> list() {
        return toolRegistry.descriptors();
    }
}
