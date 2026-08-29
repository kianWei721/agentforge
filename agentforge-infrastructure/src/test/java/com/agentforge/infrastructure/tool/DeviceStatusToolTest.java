package com.agentforge.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.device.DeviceStatusTool;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeviceStatusToolTest {

    @Test
    void shouldQueryDemoDevice() {
        DeviceStatusTool tool = new DeviceStatusTool(new DemoDeviceStatusQueryAdapter());

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("agent-1", "user-1", "session-1"),
                        Map.of("deviceId", "367"));

        assertTrue(result.success());
        assertEquals("ONLINE", result.data().get("status"));
        assertEquals("demo-adapter", result.data().get("source"));
    }

    @Test
    void shouldReturnFailureForUnknownDevice() {
        DeviceStatusTool tool = new DeviceStatusTool(new DemoDeviceStatusQueryAdapter());

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("agent-1", "user-1", "session-1"),
                        Map.of("deviceId", "999"));

        assertFalse(result.success());
        assertTrue(result.message().contains("999"));
        assertEquals("NOT_FOUND", result.failureCode());
        assertFalse(result.retryable());
    }
}
