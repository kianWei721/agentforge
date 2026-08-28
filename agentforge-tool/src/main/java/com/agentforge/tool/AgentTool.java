package com.agentforge.tool;

import java.util.Map;

public interface AgentTool {
    String name();
    String description();
    ToolResult execute(Map<String, Object> arguments);
}
