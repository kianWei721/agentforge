package com.agentforge.tool.device;

import java.util.List;

public record DeviceEventsSnapshot(
        String deviceId, long total, List<DeviceEventRecord> events, String source) {
    public DeviceEventsSnapshot {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
