package com.agentforge.tool.device;

import com.agentforge.tool.telemetry.ToolTelemetrySink;
import java.util.Optional;

public interface DevicePropertiesQueryPort {
    Optional<DevicePropertiesSnapshot> findByDeviceId(String deviceId);

    default Optional<DevicePropertiesSnapshot> findByDeviceId(
            String deviceId, ToolTelemetrySink telemetry) {
        return findByDeviceId(deviceId);
    }
}
