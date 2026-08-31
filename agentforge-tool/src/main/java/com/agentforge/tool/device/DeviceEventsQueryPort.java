package com.agentforge.tool.device;

import com.agentforge.tool.telemetry.ToolTelemetrySink;
import java.util.Optional;

public interface DeviceEventsQueryPort {
    Optional<DeviceEventsSnapshot> findRecentEvents(String deviceId, int limit);

    default Optional<DeviceEventsSnapshot> findRecentEvents(
            String deviceId, int limit, ToolTelemetrySink telemetry) {
        return findRecentEvents(deviceId, limit);
    }
}
