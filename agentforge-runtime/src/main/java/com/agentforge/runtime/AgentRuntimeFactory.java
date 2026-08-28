package com.agentforge.runtime;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.tool.AgentTool;
import java.util.List;

public interface AgentRuntimeFactory {

    AgentRuntime create(AgentDefinition definition, List<AgentTool> tools);
}
