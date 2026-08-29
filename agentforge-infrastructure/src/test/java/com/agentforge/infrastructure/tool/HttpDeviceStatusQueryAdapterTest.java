package com.agentforge.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.device.DeviceStatusQueryException;
import com.agentforge.tool.device.DeviceStatusSnapshot;
import com.agentforge.tool.device.DeviceStatusTool;
import com.agentforge.tool.telemetry.OutboundHttpTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        assertEquals("TIMEOUT", result.failureCode());
        assertTrue(result.retryable());
        assertFalse(result.message().contains("internal host"));
    }

    @Test
    void shouldRecordBusinessForbiddenTelemetryWithoutSensitiveDetails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body =
                            "{\"code\":403,\"data\":null,\"msg\":\"sensitive backend message\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        try {
            AtomicReference<OutboundHttpTelemetry> observed = new AtomicReference<>();
            HttpDeviceStatusQueryAdapter adapter =
                    newAdapter("http://127.0.0.1:" + server.getAddress().getPort(), 2);

            DeviceStatusQueryException exception =
                    assertThrows(
                            DeviceStatusQueryException.class,
                            () -> adapter.findByDeviceId("367", observed::set));

            assertEquals(DeviceStatusQueryException.Reason.FORBIDDEN, exception.reason());
            OutboundHttpTelemetry telemetry = observed.get();
            assertNotNull(telemetry);
            assertEquals("HTTP", telemetry.protocol());
            assertEquals("yudao-device-web", telemetry.downstreamSystem());
            assertEquals(1, telemetry.attempts());
            assertEquals(200, telemetry.httpStatus());
            assertEquals(403, telemetry.businessCode());
            assertEquals("FORBIDDEN", telemetry.outcome());
            assertTrue(telemetry.downstreamDurationMs() >= 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRecordRetryAttemptsAndFinalSuccessTelemetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    int attempt = requests.incrementAndGet();
                    if (attempt == 1) {
                        byte[] body = "temporary".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(503, body.length);
                        exchange.getResponseBody().write(body);
                    } else {
                        byte[] body =
                                "{\"code\":200,\"data\":{\"deviceId\":367,\"state\":\"在线\",\"lastReceiveTime\":1786772323000},\"msg\":\"\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                    exchange.close();
                });
        server.start();

        try {
            AtomicReference<OutboundHttpTelemetry> observed = new AtomicReference<>();
            HttpDeviceStatusQueryAdapter adapter =
                    newAdapter("http://127.0.0.1:" + server.getAddress().getPort(), 2);

            DeviceStatusSnapshot snapshot =
                    adapter.findByDeviceId("367", observed::set).orElseThrow();

            assertEquals("ONLINE", snapshot.status());
            assertEquals(2, requests.get());
            OutboundHttpTelemetry telemetry = observed.get();
            assertNotNull(telemetry);
            assertEquals(2, telemetry.attempts());
            assertEquals(200, telemetry.httpStatus());
            assertEquals(200, telemetry.businessCode());
            assertEquals("SUCCESS", telemetry.outcome());
        } finally {
            server.stop(0);
        }
    }

    private static HttpDeviceStatusQueryAdapter newAdapter() {
        return newAdapter("https://iot.example.internal", 2);
    }

    private static HttpDeviceStatusQueryAdapter newAdapter(
            String baseUrl, int maxAttempts) {
        IoTDeviceStatusProperties properties = new IoTDeviceStatusProperties();
        properties.setMode(IoTDeviceStatusProperties.Mode.HTTP);
        properties.setBaseUrl(baseUrl);
        properties.setAccessToken("test-token");
        properties.setTenantId("1");
        properties.setMaxAttempts(maxAttempts);
        properties.setRetryBackoff(Duration.ZERO);

        return new HttpDeviceStatusQueryAdapter(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                properties);
    }
}
