package com.agentforge.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.device.DeviceStatusQueryPort;
import com.agentforge.tool.device.DeviceStatusSnapshot;
import com.agentforge.tool.device.LureLampCommandReceipt;
import com.agentforge.tool.device.LureLampControlPort;
import com.agentforge.tool.device.LureLampControlTool;
import com.agentforge.tool.device.LureLampState;
import com.agentforge.tool.device.LureLampStateSnapshot;
import com.agentforge.tool.telemetry.ToolTelemetrySink;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LureLampControlToolTest {

    @Test
    void shouldRejectEveryDeviceExcept373BeforeBackendAccess() {
        AtomicInteger statusCalls = new AtomicInteger();
        DeviceStatusQueryPort statusPort =
                deviceId -> {
                    statusCalls.incrementAndGet();
                    return Optional.empty();
                };
        FakeControlPort controlPort = new FakeControlPort(LureLampState.OFF, LureLampState.ON);
        LureLampControlTool tool =
                new LureLampControlTool(statusPort, controlPort, 3, Duration.ZERO);

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("agent", "user", "session"),
                        Map.of("deviceId", "367", "enabled", true));

        assertFalse(result.success());
        assertEquals("DEVICE_NOT_ALLOWED", result.failureCode());
        assertEquals(0, statusCalls.get());
        assertEquals(0, controlPort.readCalls.get());
        assertEquals(0, controlPort.toggleCalls.get());
    }

    @Test
    void shouldNoOpWhenCurrentStateAlreadyMatchesTarget() {
        DeviceStatusQueryPort statusPort = onlineProduct25();
        FakeControlPort controlPort = new FakeControlPort(LureLampState.OFF);
        LureLampControlTool tool =
                new LureLampControlTool(statusPort, controlPort, 3, Duration.ZERO);

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("agent", "user", "session"),
                        Map.of("deviceId", "373", "enabled", false));

        assertTrue(result.success());
        assertEquals("NO_OP_ALREADY_DESIRED", result.data().get("outcome"));
        assertEquals(false, result.data().get("commandSent"));
        assertEquals(1, controlPort.readCalls.get());
        assertEquals(0, controlPort.toggleCalls.get());
    }

    @Test
    void shouldSendToggleExactlyOnceAndConfirmTargetState() {
        DeviceStatusQueryPort statusPort = onlineProduct25();
        FakeControlPort controlPort =
                new FakeControlPort(
                        LureLampState.OFF,
                        LureLampState.OFF,
                        LureLampState.ON);
        LureLampControlTool tool =
                new LureLampControlTool(statusPort, controlPort, 4, Duration.ZERO);

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("agent", "user", "session"),
                        Map.of("deviceId", "373", "enabled", true));

        assertTrue(result.success());
        assertEquals("TARGET_STATE_CONFIRMED", result.data().get("outcome"));
        assertEquals("ON", result.data().get("observedState"));
        assertEquals(true, result.data().get("commandSent"));
        assertEquals("2881", result.data().get("commandId"));
        assertEquals(1, controlPort.toggleCalls.get());
    }

    @Test
    void shouldNeverRetryToggleWhenTargetStateCannotBeConfirmed() {
        DeviceStatusQueryPort statusPort = onlineProduct25();
        FakeControlPort controlPort =
                new FakeControlPort(
                        LureLampState.OFF,
                        LureLampState.OFF,
                        LureLampState.OFF,
                        LureLampState.OFF);
        LureLampControlTool tool =
                new LureLampControlTool(statusPort, controlPort, 3, Duration.ZERO);

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("agent", "user", "session"),
                        Map.of("deviceId", "373", "enabled", true));

        assertFalse(result.success());
        assertEquals("TARGET_STATE_UNCONFIRMED", result.failureCode());
        assertEquals("TARGET_STATE_UNCONFIRMED", result.data().get("outcome"));
        assertEquals(1, controlPort.toggleCalls.get());
    }

    @Test
    void shouldRefuseOfflineOrWrongProductBeforeToggle() {
        DeviceStatusQueryPort offline =
                deviceId ->
                        Optional.of(
                                new DeviceStatusSnapshot(
                                        deviceId,
                                        "OFFLINE",
                                        null,
                                        "test",
                                        Map.of("productId", 25L)));
        FakeControlPort controlPort = new FakeControlPort(LureLampState.OFF);
        LureLampControlTool tool =
                new LureLampControlTool(offline, controlPort, 1, Duration.ZERO);

        ToolResult offlineResult =
                tool.execute(
                        new ToolInvocationContext("agent", "user", "session"),
                        Map.of("deviceId", "373", "enabled", true));

        assertFalse(offlineResult.success());
        assertEquals("DEVICE_OFFLINE", offlineResult.failureCode());
        assertEquals(0, controlPort.toggleCalls.get());

        DeviceStatusQueryPort wrongProduct =
                deviceId ->
                        Optional.of(
                                new DeviceStatusSnapshot(
                                        deviceId,
                                        "ONLINE",
                                        null,
                                        "test",
                                        Map.of("productId", 33L)));
        LureLampControlTool wrongProductTool =
                new LureLampControlTool(wrongProduct, controlPort, 1, Duration.ZERO);

        ToolResult productResult =
                wrongProductTool.execute(
                        new ToolInvocationContext("agent", "user", "session"),
                        Map.of("deviceId", "373", "enabled", true));

        assertFalse(productResult.success());
        assertEquals("PRODUCT_MISMATCH", productResult.failureCode());
        assertEquals(0, controlPort.toggleCalls.get());
    }

    private static DeviceStatusQueryPort onlineProduct25() {
        return deviceId ->
                Optional.of(
                        new DeviceStatusSnapshot(
                                deviceId,
                                "ONLINE",
                                "2026-08-29T07:54:33Z",
                                "test",
                                Map.of("productId", 25L)));
    }

    private static final class FakeControlPort implements LureLampControlPort {

        private final LureLampState[] states;
        private final AtomicInteger readCalls = new AtomicInteger();
        private final AtomicInteger toggleCalls = new AtomicInteger();

        private FakeControlPort(LureLampState... states) {
            this.states = states;
        }

        @Override
        public LureLampStateSnapshot readState(String deviceId) {
            int index = Math.min(readCalls.getAndIncrement(), states.length - 1);
            return new LureLampStateSnapshot(
                    deviceId,
                    states[index],
                    "2026-08-29T07:54:33Z",
                    "test");
        }

        @Override
        public LureLampCommandReceipt invokeToggle(
                String deviceId,
                ToolTelemetrySink telemetry) {
            toggleCalls.incrementAndGet();
            return new LureLampCommandReceipt(
                    "2881",
                    "message-1",
                    "ACCEPTED",
                    "accepted");
        }
    }
}
