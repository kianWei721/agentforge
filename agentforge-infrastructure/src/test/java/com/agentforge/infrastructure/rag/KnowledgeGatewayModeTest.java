package com.agentforge.infrastructure.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.config.KnowledgeAnswerabilityProperties;
import com.agentforge.infrastructure.config.KnowledgeQueryRewriteProperties;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.gateway.FakeGateway;
import com.agentforge.rag.KnowledgeAnswerabilityResult;
import com.agentforge.rag.KnowledgeModelCall;
import com.agentforge.rag.KnowledgeModelCallContext;
import com.agentforge.rag.KnowledgeQueryRewriteResult;
import com.agentforge.rag.KnowledgeSearchHit;
import com.agentforge.rag.KnowledgeSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Query rewrite and answerability through the model gateway instead of DashScope directly. */
class KnowledgeGatewayModeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> SERVED =
            Map.of(
                    "X-Gateway-Request-Id", "gw-1",
                    "X-Gateway-Served-Provider", "DASHSCOPE",
                    "X-Gateway-Served-Model", "qwen3.8-flash");

    @Test
    void rewriteSendsOnlyStandardFieldsToTheRagRouteAndRecordsTheCall() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(
                    200,
                    FakeGateway.completion(
                            "{\"rewrittenQuery\":\"shadowStatus CONFLICT\",\"reasonCode\":\"UNCHANGED\"}"),
                    SERVED);
            KnowledgeModelCallContext context = new KnowledgeModelCallContext("kr-test");

            KnowledgeQueryRewriteResult result =
                    rewriter(gateway).rewrite("shadowStatus CONFLICT", context);

            assertEquals("shadowStatus CONFLICT", result.retrievalQuery());
            JsonNode body = JSON.readTree(gateway.lastRequest().body());
            assertEquals("rag-json", body.path("model").asText());
            assertEquals(
                    DashScopeKnowledgeQueryRewriter.GATEWAY_MAX_TOKENS,
                    body.path("max_tokens").asInt());
            // Vendor-specific; the gateway adds it for the models that need it.
            assertFalse(body.has("enable_thinking"));
            assertEquals("json_schema", body.path("response_format").path("type").asText());
            assertEquals("kr-test", gateway.lastRequest().header("X-Correlation-Id"));

            assertEquals(
                    List.of(
                            new KnowledgeModelCall(
                                    "QUERY_REWRITE", "gw-1", "DASHSCOPE", "qwen3.8-flash", null)),
                    context.diagnostics().calls());
        }
    }

    @Test
    void rewriteFailureKeepsTheGatewayCodeAndRecordsTheFailedCall() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(
                    429,
                    FakeGateway.error("BUDGET_EXCEEDED"),
                    Map.of("X-Gateway-Request-Id", "gw-2"));
            KnowledgeModelCallContext context = new KnowledgeModelCallContext("kr-test");

            ModelCallFailedException failure =
                    assertThrows(
                            ModelCallFailedException.class,
                            () -> rewriter(gateway).rewrite("设备离线怎么排查", context));

            assertEquals("BUDGET_EXCEEDED", failure.errorCode());
            assertFalse(failure.retryable());
            assertEquals(
                    List.of(new KnowledgeModelCall("QUERY_REWRITE", "gw-2", null, null, "BUDGET_EXCEEDED")),
                    context.diagnostics().calls());
        }
    }

    @Test
    void judgeLeavesRoomForEveryCandidateAndRecordsTheServedModel() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(
                    200,
                    FakeGateway.completion(
                            "{\"decisions\":[{\"index\":0,\"supported\":true,\"reasonCode\":\"DIRECT_ANSWER\",\"reason\":\"explicit\"},"
                                    + "{\"index\":1,\"supported\":false,\"reasonCode\":\"TOPIC_ONLY\",\"reason\":\"related only\"}]}"),
                    SERVED);
            KnowledgeModelCallContext context = new KnowledgeModelCallContext("kr-test");

            KnowledgeAnswerabilityResult result =
                    judge(gateway).judge("离线排查顺序", List.of(hit("a"), hit("b")), context);

            assertEquals(1, result.hits().size());
            JsonNode body = JSON.readTree(gateway.lastRequest().body());
            assertEquals("rag-json", body.path("model").asText());
            assertEquals(
                    DashScopeKnowledgeAnswerabilityJudge.gatewayMaxTokens(2),
                    body.path("max_tokens").asInt());
            assertFalse(body.has("enable_thinking"));
            assertEquals("ANSWERABILITY", context.diagnostics().calls().get(0).stage());
            assertEquals("qwen3.8-flash", context.diagnostics().calls().get(0).servedModel());
        }
    }

    @Test
    void judgeFailureSurfacesAsModelCallFailureNotAGenericStateError() throws Exception {
        try (FakeGateway gateway = FakeGateway.start()) {
            gateway.respond(
                    504,
                    FakeGateway.error("UPSTREAM_OUTCOME_UNKNOWN"),
                    Map.of("X-Gateway-Request-Id", "gw-3"));

            ModelCallFailedException failure =
                    assertThrows(
                            ModelCallFailedException.class,
                            () -> judge(gateway).judge("离线排查顺序", List.of(hit("a"))));

            assertEquals("UPSTREAM_OUTCOME_UNKNOWN", failure.errorCode());
            assertFalse(failure.retryable());
            assertEquals("gw-3", failure.gatewayRequestId());
        }
    }

    @Test
    void viaGatewayIgnoresDirectDashScopeSettings() {
        KnowledgeQueryRewriteProperties direct = new KnowledgeQueryRewriteProperties();
        direct.setEnabled(true);
        direct.setBaseUrl("https://dashscope.example/compatible-mode/v1");
        direct.setApiKey("sk-direct");
        direct.setRequestTimeout(Duration.ofSeconds(7));
        ModelGatewayProperties gateway = new ModelGatewayProperties();
        gateway.setEnabled(true);
        gateway.setBaseUrl("http://gateway.internal/v1");
        gateway.setApiKey("gk-agentforge");

        KnowledgeQueryRewriteProperties effective = direct.viaGateway(gateway);

        assertEquals("http://gateway.internal/v1", effective.getBaseUrl());
        assertEquals("gk-agentforge", effective.getApiKey());
        assertEquals("rag-json", effective.getModel());
        assertEquals(Duration.ofSeconds(7), effective.getRequestTimeout());
        effective.validate();
    }

    private static DashScopeKnowledgeQueryRewriter rewriter(FakeGateway gateway) {
        KnowledgeQueryRewriteProperties properties = new KnowledgeQueryRewriteProperties();
        properties.setEnabled(true);
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return new DashScopeKnowledgeQueryRewriter(
                HttpClient.newHttpClient(),
                JSON,
                properties.viaGateway(gateway.properties()),
                gateway.client());
    }

    private static DashScopeKnowledgeAnswerabilityJudge judge(FakeGateway gateway) {
        KnowledgeAnswerabilityProperties properties = new KnowledgeAnswerabilityProperties();
        properties.setEnabled(true);
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return new DashScopeKnowledgeAnswerabilityJudge(
                HttpClient.newHttpClient(),
                JSON,
                properties.viaGateway(gateway.properties()),
                gateway.client());
    }

    private static KnowledgeSearchHit hit(String documentId) {
        return new KnowledgeSearchHit(
                "KB:" + documentId + "#0",
                documentId,
                "chunk-" + documentId,
                0,
                "Title " + documentId,
                KnowledgeSourceType.TEST,
                "test://" + documentId,
                "content " + documentId,
                0.016d,
                "VECTOR",
                0.5d,
                0.8d);
    }
}
