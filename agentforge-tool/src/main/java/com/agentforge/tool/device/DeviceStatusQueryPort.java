package com.agentforge.tool.device;

import java.util.Optional;

public interface DeviceStatusQueryPort {

    Optional<DeviceStatusSnapshot> findByDeviceId(String deviceId);
}
