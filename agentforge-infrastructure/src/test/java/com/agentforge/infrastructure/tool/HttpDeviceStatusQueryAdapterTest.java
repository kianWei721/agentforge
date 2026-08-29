package com.agentforge.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.device.DeviceStatusQueryException;
import com.agentforge.tool.device.DeviceStatusSnapshot;
import com.agentforge.tool.device.DeviceStatusTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpDeviceStatusQueryAdapterTest {

    @Test
    void shouldNormalizeRealYudaoDeviceDetailResponse() {
        HttpDeviceStatusQueryAdapter adapter = newAdapter();

        String body =
                """
                {
                  "code": 200,
                  "data": {
                    "deviceId": 367,
                    "deviceName": "23091014",
                    "deviceNickname": "办公室测报灯",
                    "productId": 25,
                    "state": "在线",
                    "location": "杭州市",
                    "longitude": 120.12,
                    "latitude": 30.28,
                    "lastReceiveTime": 1786772323000
                  },
                  "msg": ""
                }
                """;

        DeviceStatusSnapshot snapshot =
                adapter.parseResponse(200, body, "367").orElseThrow();

        assertEquals("367", snapshot.deviceId());
        assertEquals("ONLINE", snapshot.status());
        assertEquals("2026-08-15T05:38:43Z", snapshot.lastSeenAt());
        assertEquals(1786772323000L, snapshot.attributes().get("lastReceiveTimeRaw"));
        assertEquals("yudao-device-web-http", snapshot.source());
        assertEquals("在线", snapshot.attributes().get("stateRaw"));
        assertEquals("办公室测报灯", snapshot.attributes().get("deviceNickname"));
        assertEquals(25L, snapshot.attributes().get("productId"));
    }

    @Test
    void shouldKeepStringLastReceiveTimeWhenBackendAlreadyReturnsText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(
                "2026-08-29 11:22:33",
                HttpDeviceStatusQueryAdapter.normalizeLastSeenAt(
                        mapper.readTree("\"2026-08-29 11:22:33\"")));
    }

    @Test
    void shouldMapSleepState() {
        assertEquals("SLEEP", HttpDeviceStatusQueryAdapter.normalizeState("休眠"));
        assertEquals("OFFLINE", HttpDeviceStatusQueryAdapter.normalizeState("离线"));
        assertEquals("INACTIVE", HttpDeviceStatusQueryAdapter.normalizeState("未激活"));
        assertEquals("UNKNOWN", HttpDeviceStatusQueryAdapter.normalizeState(null));
    }

    @Test
    void shouldMapBusinessForbiddenWithoutExposingBackendBody() {
        HttpDeviceStatusQueryAdapter adapter = newAdapter();

        DeviceStatusQueryException exception =
                assertThrows(
                        DeviceStatusQueryException.class,
                        () ->
                                adapter.parseResponse(
                                        200,
                                        "{\"code\":403,\"data\":null,\"msg\":\"当前租户无权查看该设备\"}",
                                        "367"));

        assertEquals(DeviceStatusQueryException.Reason.FORBIDDEN, exception.reason());
        assertFalse(exception.retryable());
        assertFalse(exception.getMessage().contains("当前租户"));
    }

    @Test
    void shouldTreatSuccessfulNullDataAsNotFound() {
        HttpDeviceStatusQueryAdapter adapter = newAdapter();

        Optional<DeviceStatusSnapshot> result =
                adapter.parseResponse(
                        200,
                        "{\"code\":200,\"data\":null,\"msg\":\"\"}",
                        "999");

        assertTrue(result.isEmpty());
    }

    @Test
    void toolShouldExposeOnlyStatusSafeAttributes() {
        DeviceStatusTool tool =
                new DeviceStatusTool(
                        deviceId ->
                                Optional.of(
                                        new DeviceStatusSnapshot(
                                                deviceId,
                                                "OFFLINE",
                                                "2026-08-15T05:38:43Z",
                                                "yudao-device-web-http",
                                                java.util.Map.of(
                                                        "stateRaw", "离线",
                                                        "deviceNickname", "办公室测报灯",
                                                        "productId", 25L,
                                                        "productKey", "secret-like-business-key",
                                                        "location", "精确位置",
                                                        "longitude", 120.49,
                                                        "latitude", 30.32))));

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("default", "user", "session"),
                        java.util.Map.of("deviceId", "367"));

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> attributes =
                (java.util.Map<String, Object>) result.data().get("attributes");
        assertEquals("离线", attributes.get("stateRaw"));
        assertEquals("办公室测报灯", attributes.get("deviceNickname"));
        assertEquals(25L, attributes.get("productId"));
        assertFalse(attributes.containsKey("productKey"));
        assertFalse(attributes.containsKey("location"));
        assertFalse(attributes.containsKey("longitude"));
        assertFalse(attributes.containsKey("latitude"));
    }

    @Test
    void toolShouldRejectPathLikeDeviceIdBeforeCallingBackend() {
        DeviceStatusTool tool =
                new DeviceStatusTool(
                        deviceId -> {
                            fail("backend must not be called for invalid deviceId");
                            return Optional.empty();
                        });

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("default", "user", "session"),
                        java.util.Map.of("deviceId", "../367"));

        assertFalse(result.success());
        assertEquals("deviceId must be a numeric identifier", result.message());
    }

    @Test
    void toolShouldReturnSafeFailureReasonForBackendErrors() {
        DeviceStatusTool tool =
                new DeviceStatusTool(
                        deviceId -> {
                            throw new DeviceStatusQueryException(
                                    DeviceStatusQueryException.Reason.TIMEOUT,
                                    true,
                                    "internal host timed out");
                        });

        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("default", "user", "session"),
                        java.util.Map.of("deviceId", "367"));

        assertFalse(result.success());
        assertEquals("device status query unavailable: TIMEOUT", result.message());
        assertFalse(result.message().contains("internal host"));
    }

    private static HttpDeviceStatusQueryAdapter newAdapter() {
        IoTDeviceStatusProperties properties = new IoTDeviceStatusProperties();
        properties.setMode(IoTDeviceStatusProperties.Mode.HTTP);
        properties.setBaseUrl("https://iot.example.internal");
        properties.setAccessToken("test-token");
        properties.setTenantId("1");

        return new HttpDeviceStatusQueryAdapter(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                properties);
    }
}
