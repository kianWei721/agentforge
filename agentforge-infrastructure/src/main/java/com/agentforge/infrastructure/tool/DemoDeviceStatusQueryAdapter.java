package com.agentforge.infrastructure.tool;

import com.agentforge.tool.device.DeviceStatusQueryPort;
import com.agentforge.tool.device.DeviceStatusSnapshot;
import java.util.Map;
import java.util.Optional;

public class DemoDeviceStatusQueryAdapter implements DeviceStatusQueryPort {

    private final Map<String, DeviceStatusSnapshot> demoData =
            Map.of(
                    "367",
                    new DeviceStatusSnapshot(
                            "367",
                            "ONLINE",
                            "2026-08-28T10:00:00Z",
                            "demo-adapter",
                            Map.of(
                                    "product", "智能虫情测报灯",
                                    "network", "MQTT",
                                    "note", "V0.2 deterministic demo data")),
                    "343",
                    new DeviceStatusSnapshot(
                            "343",
                            "OFFLINE",
                            "2026-08-28T08:12:00Z",
                            "demo-adapter",
                            Map.of(
                                    "product", "智能虫情测报灯",
                                    "network", "MQTT",
                                    "note", "V0.2 deterministic demo data")));

    @Override
    public Optional<DeviceStatusSnapshot> findByDeviceId(String deviceId) {
        return Optional.ofNullable(demoData.get(deviceId));
    }
}
