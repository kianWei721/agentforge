package com.agentforge.tool.device;

import com.agentforge.tool.telemetry.ToolTelemetrySink;
import java.util.Optional;

public interface DeviceStatusQueryPort {

    Optional<DeviceStatusSnapshot> findByDeviceId(String deviceId);

    default Optional<DeviceStatusSnapshot> findByDeviceId(
            String deviceId, ToolTelemetrySink telemetry) {
        return findByDeviceId(deviceId);
    }
}
