package com.agentforge.infrastructure.tool;

import com.agentforge.tool.device.DeviceEventRecord;
import com.agentforge.tool.device.DeviceEventsQueryPort;
import com.agentforge.tool.device.DeviceEventsSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DemoDeviceEventsQueryAdapter implements DeviceEventsQueryPort {
    @Override
    public Optional<DeviceEventsSnapshot> findRecentEvents(String deviceId, int limit) {
        List<DeviceEventRecord> events = List.of(new DeviceEventRecord(
                100L, "demo_heartbeat", Map.of("status", "ok"), Instant.now().toString()));
        return Optional.of(new DeviceEventsSnapshot(
                deviceId, events.size(), events, "demo-device-events"));
    }
}
