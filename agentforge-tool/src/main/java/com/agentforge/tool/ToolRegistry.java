package com.agentforge.tool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    void register(AgentTool tool);

    Optional<AgentTool> find(String name);

    AgentTool getRequired(String name);

    List<AgentTool> resolve(List<String> names);

    List<ToolDescriptor> descriptors();
}
