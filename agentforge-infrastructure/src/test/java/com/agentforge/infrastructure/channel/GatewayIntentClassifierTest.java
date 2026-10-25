package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.intent.IntentClassifier.Classification;
import com.agentforge.channel.intent.IntentClassifier.InvalidClassificationException;
import com.agentforge.channel.intent.IntentDefinition;
import com.agentforge.channel.intent.IntentTarget;
import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.gateway.FakeGateway;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayIntentClassifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<IntentDefinition> INTENTS =
            List.of(
                    new IntentDefinition("answer", "业务咨询", List.of(), IntentTarget.ANSWER, null),
                    new IntentDefinition("handoff", "需要人工", List.of("转人工"), IntentTarget.HANDOFF, "已登记"));

    private FakeGateway gateway;
    private GatewayIntentClassifier classifier;

    @BeforeEach
    void setUp() throws Exception {
        gateway = FakeGateway.start();
        classifier = new GatewayIntentClassifier(gateway.client(), "rag-json", JSON);
    }

    @AfterEach
    void tearDown() {
        gateway.close();
    }

    @Test
    void asksTheRouteForOneOfTheConfiguredIntentsWithOnlyTheQuestion() throws Exception {
        gateway.respond(
                200,
                FakeGateway.completion("{\"intent\":\"handoff\",\"confidence\":0.91}"),
                Map.of(ModelGatewayClient.REQUEST_ID_HEADER, "gw-1"));

        Classification result = classifier.classify("我要退款", INTENTS, "wecom-in-1", Duration.ofSeconds(3));

        assertEquals(new Classification("handoff", 0.91, "gw-1"), result);
        FakeGateway.Recorded request = gateway.lastRequest();
        assertEquals("wecom-in-1", request.header(ModelGatewayClient.CORRELATION_HEADER));
        assertNotNull(request.header(ModelGatewayClient.DEADLINE_HEADER));
        JsonNode body = JSON.readTree(request.body());
        assertEquals("rag-json", body.path("model").asText());
        assertEquals(0, body.path("temperature").asInt());
        assertEquals(64, body.path("max_tokens").asInt());
        JsonNode schema = body.path("response_format").path("json_schema");
        assertTrue(schema.path("strict").asBoolean());
        List<String> names = new ArrayList<>();
        schema.path("schema").path("properties").path("intent").path("enum").forEach(n -> names.add(n.asText()));
        assertEquals(List.of("answer", "handoff"), names);
        JsonNode messages = body.path("messages");
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(1).path("role").asText());
        assertEquals("我要退款", messages.get(1).path("content").asText());
        assertFalse(messages.get(0).path("content").asText().contains("我要退款"), "the question is only the user message");
    }

    @Test
    void anAnswerThatIsNotTheExpectedJsonIsInvalid() {
        for (String content : List.of("handoff", "{\"intent\":\"handoff\"}", "{\"intent\":1,\"confidence\":0.9}", "")) {
            gateway.respond(200, FakeGateway.completion(content), Map.of(ModelGatewayClient.REQUEST_ID_HEADER, "gw-2"));

            InvalidClassificationException invalid =
                    assertThrows(
                            InvalidClassificationException.class,
                            () -> classifier.classify("x", INTENTS, "c", Duration.ofSeconds(3)),
                            content);
            assertEquals("gw-2", invalid.gatewayRequestId());
        }
    }

    @Test
    void aGatewayFailureCarriesItsRequestId() {
        gateway.respond(429, FakeGateway.error("RATE_LIMITED"), Map.of(ModelGatewayClient.REQUEST_ID_HEADER, "gw-3"));

        ModelCallFailedException failed =
                assertThrows(
                        ModelCallFailedException.class,
                        () -> classifier.classify("x", INTENTS, "c", Duration.ofSeconds(3)));
        assertEquals("RATE_LIMITED", failed.errorCode());
        assertEquals("gw-3", failed.gatewayRequestId());
    }
}
