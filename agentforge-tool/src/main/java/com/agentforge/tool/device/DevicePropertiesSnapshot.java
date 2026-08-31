package com.agentforge.tool.device;

import java.util.Map;

public record DevicePropertiesSnapshot(
        String deviceId,
        String deviceNickname,
        Long productId,
        String observedAt,
        String source,
        Map<String, DevicePropertyValue> properties) {
    public DevicePropertiesSnapshot {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
