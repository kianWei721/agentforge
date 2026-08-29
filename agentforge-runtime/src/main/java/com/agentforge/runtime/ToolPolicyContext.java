package com.agentforge.runtime;

import com.agentforge.tool.ToolRiskLevel;
import java.util.Map;
import java.util.Objects;

public record ToolPolicyContext(
        String agentId,
        String userId,
        String sessionId,
        String toolName,
        boolean readOnly,
        ToolRiskLevel riskLevel,
        Map<String, Object> arguments) {

    public ToolPolicyContext {
        Objects.requireNonNull(toolName, "toolName must not be null");
        riskLevel = riskLevel == null ? ToolRiskLevel.HIGH : riskLevel;
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
