package com.agentforge.infrastructure.tool;

import com.agentforge.tool.device.DevicePropertiesQueryPort;
import com.agentforge.tool.device.DevicePropertiesSnapshot;
import com.agentforge.tool.device.DevicePropertyValue;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class DemoDevicePropertiesQueryAdapter implements DevicePropertiesQueryPort {
    @Override
    public Optional<DevicePropertiesSnapshot> findByDeviceId(String deviceId) {
        String now = Instant.now().toString();
        Map<String, DevicePropertyValue> values = new LinkedHashMap<>();
        values.put("WorkMode", new DevicePropertyValue("工作模式", 1L, now, "DEMO", null));
        values.put("BatteryPercentage", new DevicePropertyValue("电池电量百分比", 76.0d, now, "DEMO", null));
        values.put("PeripheralStatus", new DevicePropertyValue(
                "外设状态",
                Map.of("TrapLight", 0L, "RainCompartment", 0L, "VibrationMotor", 0L),
                now, "DEMO", null));
        return Optional.of(new DevicePropertiesSnapshot(
                deviceId, "demo-device", 25L, now, "demo-device-properties", values));
    }
}
