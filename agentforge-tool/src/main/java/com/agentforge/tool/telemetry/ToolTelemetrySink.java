package com.agentforge.tool.telemetry;

@FunctionalInterface
public interface ToolTelemetrySink {

    void recordOutboundHttp(OutboundHttpTelemetry telemetry);

    static ToolTelemetrySink noop() {
        return telemetry -> {};
    }
}
