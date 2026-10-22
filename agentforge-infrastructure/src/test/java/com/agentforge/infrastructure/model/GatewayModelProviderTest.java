package com.agentforge.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.infrastructure.gateway.FakeGateway;
import com.agentforge.infrastructure.gateway.ModelGatewayConfiguration;
import com.agentforge.model.ModelChunk;
import com.agentforge.model.ModelRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class GatewayModelProviderTest {

    @Test
    void emitsTheWholeAnswerOnceAndNamesTheModelThatServedIt() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(
                    200,
                    FakeGateway.completion("你好"),
                    Map.of(
                            "X-Gateway-Request-Id", "gw-1",
                            "X-Gateway-Served-Provider", "DEEPSEEK",
                            "X-Gateway-Served-Model", "deepseek-chat"));
            GatewayModelProvider provider =
                    new GatewayModelProvider(gateway.client(), gateway.properties());

            StepVerifier.create(provider.stream(new ModelRequest("user-a", "session-1", "hi")))
                    .expectNext(new ModelChunk("你好", false, "gateway", "DEEPSEEK/deepseek-chat"))
                    .expectNext(new ModelChunk("", true, "gateway", "DEEPSEEK/deepseek-chat"))
                    .verifyComplete();

            JsonNode body = new ObjectMapper().readTree(gateway.lastRequest().body());
            assertEquals("standard", body.path("model").asText());
            // The user id stays inside AgentForge; vendors never see it.
            assertFalse(body.has("user"));
            assertEquals("chat-session-1", gateway.lastRequest().header("X-Correlation-Id"));
        }
    }

    @Test
    void gatewayFailureReachesTheSubscriber() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(429, FakeGateway.error("BUDGET_EXCEEDED"), Map.of());
            GatewayModelProvider provider =
                    new GatewayModelProvider(gateway.client(), gateway.properties());

            StepVerifier.create(provider.stream(new ModelRequest("user-a", null, "hi")))
                    .expectErrorSatisfies(
                            error -> {
                                ModelCallFailedException failure =
                                        assertInstanceOf(ModelCallFailedException.class, error);
                                assertEquals("BUDGET_EXCEEDED", failure.errorCode());
                                assertFalse(failure.retryable());
                            })
                    .verify();
        }
    }

    @Test
    void anEmptyErrorBodyStillReachesTheSubscriberAsAModelCallFailure() throws Exception {
        // ChatController turns ModelCallFailedException into the SSE error event; anything else
        // would end the stream without one.
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(502, "", Map.of());
            GatewayModelProvider provider =
                    new GatewayModelProvider(gateway.client(), gateway.properties());

            StepVerifier.create(provider.stream(new ModelRequest("user-a", "s", "hi")))
                    .expectErrorSatisfies(
                            error ->
                                    assertEquals(
                                            "HTTP_502",
                                            assertInstanceOf(ModelCallFailedException.class, error)
                                                    .errorCode()))
                    .verify();
        }
    }

    @Test
    void sessionIdsTheGatewayWouldRejectGetAFreshCorrelationId() {
        String correlationId =
                GatewayModelProvider.correlationId(new ModelRequest("u", "会话 1", "hi"));

        assertTrue(correlationId.matches("chat-[0-9a-f-]{36}"), correlationId);
    }

    @Test
    void choosingTheGatewayForChatWhileItIsOffFailsAtStartup() {
        ModelProviderProperties model = new ModelProviderProperties();
        model.setProvider(" Gateway ");

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> new ModelGatewayConfiguration(new ModelGatewayProperties(), model));

        assertTrue(failure.getMessage().contains("agentforge.model-gateway.enabled=true"));
    }

    @Test
    void enabledGatewayRequiresUrlAndKey() {
        ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:8090/v1");

        assertThrows(IllegalStateException.class, properties::validate);

        properties.setApiKey("gk-test");
        properties.validate();
    }
}
