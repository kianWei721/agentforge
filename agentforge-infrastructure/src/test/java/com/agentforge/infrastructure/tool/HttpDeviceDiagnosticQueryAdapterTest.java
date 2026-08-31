package com.agentforge.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.device.DeviceDiagnosticQueryException;
import com.agentforge.tool.device.DeviceEventsSnapshot;
import com.agentforge.tool.device.DeviceEventsTool;
import com.agentforge.tool.device.DevicePropertiesSnapshot;
import com.agentforge.tool.device.DevicePropertiesTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpDeviceDiagnosticQueryAdapterTest {
    @Test
    void shouldNormalizeLatestPropertiesFromTenantCheckedDetails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin-api/device/web/devices/373/details", exchange ->
                respond(exchange,
                        "{\"code\":200,\"data\":{\"deviceId\":373,"
                                + "\"deviceNickname\":\"测试测报灯\",\"productId\":25,"
                                + "\"latestUpdateTime\":1788060000000,\"properties\":[{"
                                + "\"model\":{\"identifier\":\"PeripheralStatus\",\"propertyName\":\"外设状态\"},"
                                + "\"latestValue\":{\"TrapLight\":1,\"RainCompartment\":0},"
                                + "\"latestTime\":1788060000000,\"source\":\"DEVICE_REPORT\","
                                + "\"shadowStatus\":\"SYNCED\"}]},\"msg\":\"\"}"));
        server.start();
        try {
            HttpDevicePropertiesQueryAdapter adapter = new HttpDevicePropertiesQueryAdapter(
                    HttpClient.newHttpClient(), new ObjectMapper(), properties(server));
            DevicePropertiesSnapshot snapshot = adapter.findByDeviceId("373").orElseThrow();
            assertEquals("测试测报灯", snapshot.deviceNickname());
            @SuppressWarnings("unchecked")
            var status = (java.util.Map<String, Object>)
                    snapshot.properties().get("PeripheralStatus").value();
            assertEquals(1L, status.get("TrapLight"));

            ToolResult result = new DevicePropertiesTool(id -> java.util.Optional.of(snapshot))
                    .execute(new ToolInvocationContext("a", "u", "s"), java.util.Map.of("deviceId", "373"));
            assertTrue(result.success());
            assertEquals(1, result.data().get("propertyCount"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldGuardEventHistoryWithTenantCheckedDetailsFirst() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin-api/device/web/devices/373/details", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            respond(exchange, "{\"code\":200,\"data\":{\"deviceId\":373},\"msg\":\"\"}");
        });
        server.createContext("/admin-api/device/web/devices/373/events/history", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            respond(exchange,
                    "{\"code\":200,\"data\":{\"list\":[{\"eventId\":169,"
                            + "\"eventName\":\"外设状态\",\"deviceId\":373,"
                            + "\"eventData\":\"{\\\"TrapLight\\\":1}\","
                            + "\"createTime\":1788060000000}],\"total\":1},\"msg\":\"\"}");
        });
        server.start();
        try {
            HttpDeviceEventsQueryAdapter adapter = new HttpDeviceEventsQueryAdapter(
                    HttpClient.newHttpClient(), new ObjectMapper(), properties(server));
            DeviceEventsSnapshot snapshot = adapter.findRecentEvents("373", 5).orElseThrow();
            assertEquals(2, requests.size());
            assertEquals("/admin-api/device/web/devices/373/details", requests.get(0));
            assertTrue(requests.get(1).contains("pageNo=1"));
            assertTrue(requests.get(1).contains("pageSize=5"));
            assertEquals("外设状态", snapshot.events().get(0).eventName());

            ToolResult result = new DeviceEventsTool((id, limit) -> java.util.Optional.of(snapshot))
                    .execute(new ToolInvocationContext("a", "u", "s"),
                            java.util.Map.of("deviceId", "373", "limit", 5));
            assertTrue(result.success());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotReadEventHistoryWhenDetailsGuardIsForbidden() throws Exception {
        AtomicInteger historyCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin-api/device/web/devices/373/details", exchange ->
                respond(exchange, "{\"code\":403,\"data\":null,\"msg\":\"forbidden\"}"));
        server.createContext("/admin-api/device/web/devices/373/events/history", exchange -> {
            historyCalls.incrementAndGet();
            respond(exchange, "{\"code\":200,\"data\":{\"list\":[],\"total\":0}}");
        });
        server.start();
        try {
            HttpDeviceEventsQueryAdapter adapter = new HttpDeviceEventsQueryAdapter(
                    HttpClient.newHttpClient(), new ObjectMapper(), properties(server));
            DeviceDiagnosticQueryException exception = assertThrows(
                    DeviceDiagnosticQueryException.class,
                    () -> adapter.findRecentEvents("373", 10));
            assertEquals(DeviceDiagnosticQueryException.Reason.FORBIDDEN, exception.reason());
            assertEquals(0, historyCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void eventToolShouldRejectInvalidLimitBeforeCallingBackend() {
        DeviceEventsTool tool = new DeviceEventsTool((id, limit) -> {
            fail("backend must not be called");
            return java.util.Optional.empty();
        });
        ToolResult result = tool.execute(
                new ToolInvocationContext("a", "u", "s"),
                java.util.Map.of("deviceId", "373", "limit", 21));
        assertFalse(result.success());
        assertEquals("INVALID_ARGUMENT", result.failureCode());
    }

    private static IoTDeviceStatusProperties properties(HttpServer server) {
        IoTDeviceStatusProperties p = new IoTDeviceStatusProperties();
        p.setMode(IoTDeviceStatusProperties.Mode.HTTP);
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setAccessToken("test-token");
        p.setTenantId("1");
        p.setMaxAttempts(1);
        p.setRetryBackoff(Duration.ZERO);
        return p;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String value)
            throws java.io.IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
