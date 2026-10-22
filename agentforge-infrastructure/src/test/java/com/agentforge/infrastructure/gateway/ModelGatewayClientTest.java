package com.agentforge.infrastructure.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelGatewayClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sendsCallerContractHeadersAndReportsTheServedModel() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(
                    200,
                    FakeGateway.completion("hello"),
                    Map.of(
                            "X-Gateway-Request-Id", "gw-1",
                            "X-Gateway-Served-Provider", "DASHSCOPE",
                            "X-Gateway-Served-Model", "qwen-plus"));

            ModelGatewayClient.Completion completion =
                    gateway.client().complete(body("rag-json"), "kr-123", Duration.ofSeconds(15));

            assertEquals("hello", completion.content());
            assertEquals("gw-1", completion.gatewayRequestId());
            assertEquals("DASHSCOPE", completion.servedProvider());
            assertEquals("qwen-plus", completion.servedModel());

            FakeGateway.Recorded request = gateway.lastRequest();
            assertEquals("Bearer gk-test", request.header("Authorization"));
            assertEquals("kr-123", request.header("X-Correlation-Id"));
            assertEquals("14500", request.header("X-Gateway-Deadline-Ms"));
            assertEquals("rag-json", JSON.readTree(request.body()).path("model").asText());
        }
    }

    @Test
    void retryabilityFollowsTheGatewayErrorCodeNotTheHttpStatus() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            assertFailure(gateway, 429, "RATE_LIMITED", true);
            // Same status, but a spent budget will not have refilled.
            assertFailure(gateway, 429, "BUDGET_EXCEEDED", false);
            assertFailure(gateway, 503, "NO_UPSTREAM_AVAILABLE", true);
            // The vendor may already have charged for this one.
            assertFailure(gateway, 504, "UPSTREAM_OUTCOME_UNKNOWN", false);
            assertFailure(gateway, 400, "UNSUPPORTED_FIELD", false);
        }
    }

    @Test
    void errorWithoutTheGatewayEnvelopeFallsBackToTheHttpStatus() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(502, "<html>bad gateway</html>", Map.of());

            ModelCallFailedException failure =
                    assertThrows(
                            ModelCallFailedException.class,
                            () -> gateway.client().complete(body("standard"), null, Duration.ofSeconds(2)));

            assertEquals("HTTP_502", failure.errorCode());
            assertFalse(failure.retryable());
            assertNull(failure.gatewayRequestId());
            assertNull(gateway.lastRequest().header("X-Correlation-Id"));
        }
    }

    @Test
    void anEmptyErrorBodyFallsBackToTheHttpStatus() throws Exception {
        // A proxy in front of the gateway may answer with no body at all.
        try (FakeGateway gateway = FakeGateway.start()) {
            for (String empty : new String[] {"", "   ", "\n"}) {
                gateway.respond(502, empty, Map.of("X-Gateway-Request-Id", "gw-empty"));

                ModelCallFailedException failure =
                        assertThrows(
                                ModelCallFailedException.class,
                                () -> gateway.client().complete(body("standard"), "c-1", Duration.ofSeconds(2)));

                assertEquals("HTTP_502", failure.errorCode());
                assertFalse(failure.retryable());
                assertEquals("gw-empty", failure.gatewayRequestId());
            }
        }
    }

    @Test
    void anEmptySuccessBodyIsAnInvalidResponseNotAnEmptyAnswer() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(200, "", Map.of("X-Gateway-Request-Id", "gw-1"));

            ModelCallFailedException failure =
                    assertThrows(
                            ModelCallFailedException.class,
                            () -> gateway.client().complete(body("standard"), "c-1", Duration.ofSeconds(2)));

            assertEquals("INVALID_GATEWAY_RESPONSE", failure.errorCode());
            // The vendor may already have charged for it.
            assertFalse(failure.retryable());
            assertEquals("gw-1", failure.gatewayRequestId());
        }
    }

    @Test
    void unreachableGatewayIsRetryableBecauseNothingWasSent() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setBaseUrl("http://127.0.0.1:" + closedPort + "/v1");
        properties.setApiKey("gk-test");
        ModelGatewayClient client =
                new ModelGatewayClient(HttpClient.newHttpClient(), JSON, properties);

        ModelCallFailedException failure =
                assertThrows(
                        ModelCallFailedException.class,
                        () -> client.complete(body("standard"), "c-1", Duration.ofSeconds(2)));

        assertEquals("GATEWAY_UNREACHABLE", failure.errorCode());
        assertTrue(failure.retryable());
    }

    @Test
    void timeoutIsNotRetryableBecauseTheGatewayMayStillBePaying() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.delay(3_000);

            ModelCallFailedException failure =
                    assertThrows(
                            ModelCallFailedException.class,
                            () -> gateway.client().complete(body("standard"), "c-1", Duration.ofMillis(300)));

            assertEquals("GATEWAY_TIMEOUT", failure.errorCode());
            assertFalse(failure.retryable());
        }
    }

    @Test
    void deadlineLeavesTheGatewayTimeToAnswer() {
        assertEquals(19_500, ModelGatewayClient.deadlineMillis(Duration.ofSeconds(20)));
        assertEquals(1, ModelGatewayClient.deadlineMillis(Duration.ofMillis(200)));
        assertEquals(600_000, ModelGatewayClient.deadlineMillis(Duration.ofMinutes(20)));
    }

    private static void assertFailure(
            FakeGateway gateway, int status, String code, boolean retryable) {
        gateway.respond(status, FakeGateway.error(code), Map.of("X-Gateway-Request-Id", "gw-" + code));

        ModelCallFailedException failure =
                assertThrows(
                        ModelCallFailedException.class,
                        () -> gateway.client().complete(body("standard"), "c-1", Duration.ofSeconds(2)));

        assertEquals(code, failure.errorCode());
        assertEquals(retryable, failure.retryable(), code);
        assertEquals("gw-" + code, failure.gatewayRequestId());
    }

    private static ObjectNode body(String route) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", route);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return body;
    }
}
