package com.agentforge.tool;

import java.util.Map;

public interface AgentTool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    default boolean readOnly() {
        return true;
    }

    default boolean concurrencySafe() {
        return true;
    }

    default ToolRiskLevel riskLevel() {
        return readOnly() ? ToolRiskLevel.LOW : ToolRiskLevel.HIGH;
    }

    ToolResult execute(ToolInvocationContext context, Map<String, Object> arguments);
}
