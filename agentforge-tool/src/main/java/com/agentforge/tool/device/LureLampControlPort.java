package com.agentforge.tool.device;

import com.agentforge.tool.telemetry.ToolTelemetrySink;

public interface LureLampControlPort {

    LureLampStateSnapshot readState(String deviceId);

    LureLampCommandReceipt invokeToggle(
            String deviceId,
            ToolTelemetrySink telemetry);
}
