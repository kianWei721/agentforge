package com.agentforge.runtime;

import com.agentforge.tool.telemetry.OutboundHttpTelemetry;
import com.agentforge.tool.telemetry.ToolTelemetrySink;
import java.util.concurrent.atomic.AtomicReference;

final class ToolTelemetryBuffer implements ToolTelemetrySink {

    private final AtomicReference<OutboundHttpTelemetry> latestOutboundHttp =
            new AtomicReference<>();

    @Override
    public void recordOutboundHttp(OutboundHttpTelemetry telemetry) {
        if (telemetry != null) {
            latestOutboundHttp.set(telemetry);
        }
    }

    OutboundHttpTelemetry latestOutboundHttp() {
        return latestOutboundHttp.get();
    }
}
