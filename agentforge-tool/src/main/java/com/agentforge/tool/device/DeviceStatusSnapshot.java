package com.agentforge.tool.device;

import java.util.Map;

public record DeviceStatusSnapshot(
        String deviceId,
        String status,
        String lastSeenAt,
        String source,
        Map<String, Object> attributes) {

    public DeviceStatusSnapshot {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
