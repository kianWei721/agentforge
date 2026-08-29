package com.agentforge.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.infrastructure.config.IoTLureLampControlProperties;
import com.agentforge.tool.device.LureLampCommandReceipt;
import com.agentforge.tool.device.LureLampControlException;
import com.agentforge.tool.device.LureLampState;
import com.agentforge.tool.device.LureLampStateSnapshot;
import com.agentforge.tool.telemetry.OutboundHttpTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpLureLampControlAdapterTest {

    @Test
    void shouldReadTrapLightFromPeripheralStatus() {
        HttpLureLampControlAdapter adapter =
                newAdapter("https://iot.example.internal");

        String body =
                """
                {
                  "code": 200,
                  "data": [
                    {
                      "propertyId": 169,
                      "identifier": "PeripheralStatus",
                      "propertyData": "{\"PeripheralStatus\":{\"TrapLight\":1,\"HeatingTube\":0}}",
                      "updateTime": "2026-08-29 15:54:33"
                    }
                  ]
                }
                """;

        LureLampStateSnapshot snapshot =
                adapter.parseStateResponse(200, body, "373");

        assertEquals(LureLampState.ON, snapshot.state());
        assertEquals("2026-08-29 15:54:33", snapshot.observedAt());
    }

    @Test
    void shouldSendOneFixedServiceInvokeWithoutRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/admin-api/device/web/devices/command/send",
                exchange -> {
                    requests.incrementAndGet();
                    requestBody.set(
                            new ObjectMapper()
                                    .readTree(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8)));
                    byte[] body =
                            """
                            {"code":200,"data":{"commandId":2881,"messageId":"message-1","status":"ACCEPTED","message":"虫情灯命令已受理"}}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        try {
            AtomicReference<OutboundHttpTelemetry> observed = new AtomicReference<>();
            HttpLureLampControlAdapter adapter =
                    newAdapter("http://127.0.0.1:" + server.getAddress().getPort());

            LureLampCommandReceipt receipt =
                    adapter.invokeToggle("373", observed::set);

            assertEquals(1, requests.get());
            assertEquals(373, requestBody.get().path("deviceId").asInt());
            assertEquals(
                    "SERVICE_INVOKE",
                    requestBody.get().path("commandType").asText());
            assertEquals("TrapLight", requestBody.get().path("identifier").asText());
            assertTrue(requestBody.get().path("params").isObject());
            assertEquals(0, requestBody.get().path("params").size());

            assertEquals("2881", receipt.commandId());
            assertEquals("ACCEPTED", receipt.status());

            OutboundHttpTelemetry telemetry = observed.get();
            assertNotNull(telemetry);
            assertEquals(1, telemetry.attempts());
            assertEquals(200, telemetry.httpStatus());
            assertEquals(200, telemetry.businessCode());
            assertEquals("COMMAND_ACCEPTED", telemetry.outcome());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void backendFailureMustNotRetryWriteRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/admin-api/device/web/devices/command/send",
                exchange -> {
                    requests.incrementAndGet();
                    byte[] body = "temporary".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(503, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        try {
            AtomicReference<OutboundHttpTelemetry> observed = new AtomicReference<>();
            HttpLureLampControlAdapter adapter =
                    newAdapter("http://127.0.0.1:" + server.getAddress().getPort());

            LureLampControlException exception =
                    assertThrows(
                            LureLampControlException.class,
                            () -> adapter.invokeToggle("373", observed::set));

            assertEquals(
                    LureLampControlException.Reason.BACKEND_UNAVAILABLE,
                    exception.reason());
            assertEquals(1, requests.get());
            assertEquals(1, observed.get().attempts());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void adapterMustRejectAnyOtherDeviceBeforeNetwork() {
        HttpLureLampControlAdapter adapter =
                newAdapter("https://iot.example.internal");

        LureLampControlException exception =
                assertThrows(
                        LureLampControlException.class,
                        () -> adapter.invokeToggle("367", telemetry -> {}));

        assertEquals(
                LureLampControlException.Reason.DEVICE_NOT_ALLOWED,
                exception.reason());
        assertFalse(exception.mayHaveExecuted());
    }

    private static HttpLureLampControlAdapter newAdapter(String baseUrl) {
        IoTDeviceStatusProperties shared = new IoTDeviceStatusProperties();
        shared.setMode(IoTDeviceStatusProperties.Mode.HTTP);
        shared.setBaseUrl(baseUrl);
        shared.setAccessToken("test-token");
        shared.setTenantId("1");
        shared.setConnectTimeout(Duration.ofSeconds(1));
        shared.setRequestTimeout(Duration.ofSeconds(1));

        IoTLureLampControlProperties control = new IoTLureLampControlProperties();
        control.setEnabled(true);
        control.setRequestTimeout(Duration.ofSeconds(1));

        return new HttpLureLampControlAdapter(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .build(),
                new ObjectMapper(),
                shared,
                control);
    }
}
