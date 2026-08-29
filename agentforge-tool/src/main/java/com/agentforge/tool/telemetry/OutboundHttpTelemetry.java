package com.agentforge.tool.telemetry;

public record OutboundHttpTelemetry(
        String protocol,
        String downstreamSystem,
        int attempts,
        Integer httpStatus,
        Integer businessCode,
        long downstreamDurationMs,
        String outcome) {

    public OutboundHttpTelemetry {
        protocol = normalize(protocol, "HTTP");
        downstreamSystem = normalize(downstreamSystem, "unknown");
        outcome = normalize(outcome, "UNKNOWN");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        if (downstreamDurationMs < 0) {
            throw new IllegalArgumentException("downstreamDurationMs must not be negative");
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
