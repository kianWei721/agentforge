package com.agentforge.tool;

import com.agentforge.tool.telemetry.ToolTelemetrySink;

public record ToolInvocationContext(
        String agentId,
        String userId,
        String sessionId,
        ToolTelemetrySink telemetry) {

    public ToolInvocationContext(String agentId, String userId, String sessionId) {
        this(agentId, userId, sessionId, ToolTelemetrySink.noop());
    }

    public ToolInvocationContext {
        telemetry = telemetry == null ? ToolTelemetrySink.noop() : telemetry;
    }
}
