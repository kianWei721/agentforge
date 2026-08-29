package com.agentforge.tool;

import java.util.Map;

public record ToolDescriptor(
        String name,
        String description,
        Map<String, Object> inputSchema,
        boolean readOnly,
        boolean concurrencySafe,
        ToolRiskLevel riskLevel) {

    public static ToolDescriptor from(AgentTool tool) {
        return new ToolDescriptor(
                tool.name(),
                tool.description(),
                tool.inputSchema(),
                tool.readOnly(),
                tool.concurrencySafe(),
                tool.riskLevel());
    }
}
