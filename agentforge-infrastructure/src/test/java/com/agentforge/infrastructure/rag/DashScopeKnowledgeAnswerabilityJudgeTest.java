package com.agentforge.infrastructure.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.KnowledgeAnswerabilityProperties;
import com.agentforge.rag.KnowledgeAnswerabilityResult;
import com.agentforge.rag.KnowledgeSearchHit;
import com.agentforge.rag.KnowledgeSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DashScopeKnowledgeAnswerabilityJudgeTest {

    @Test
    void shouldKeepOnlyCandidatesExplicitlySupportedByPassage()
            throws Exception {
        AtomicReference<String> authorization =
                new AtomicReference<>();
        AtomicReference<String> requestBody =
                new AtomicReference<>();

        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(
                                "127.0.0.1",
                                0),
                        0);
        server.createContext(
                "/compatible-mode/v1/chat/completions",
                exchange -> {
                    authorization.set(
                            exchange.getRequestHeaders()
                                    .getFirst("Authorization"));
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody()
                                            .readAllBytes(),
                                    StandardCharsets.UTF_8));

                    String content =
                            """
                            {"decisions":[
                              {"index":0,"supported":true,"reasonCode":"DIRECT_ANSWER","reason":"The passage explicitly provides the procedure."},
                              {"index":1,"supported":false,"reasonCode":"MISSING_REQUIRED_SEMANTICS","reason":"The passage does not define the field."}
                            ]}
                            """;
                    ObjectMapper mapper =
                            new ObjectMapper();
                    String body =
                            mapper.writeValueAsString(
                                    java.util.Map.of(
                                            "choices",
                                            List.of(
                                                    java.util.Map.of(
                                                            "message",
                                                            java.util.Map.of(
                                                                    "content",
                                                                    content)))));
                    byte[] bytes =
                            body.getBytes(
                                    StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set(
                                    "Content-Type",
                                    "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(
                            200,
                            bytes.length);
                    exchange.getResponseBody()
                            .write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeAnswerabilityProperties config =
                    config(server);
            DashScopeKnowledgeAnswerabilityJudge judge =
                    new DashScopeKnowledgeAnswerabilityJudge(
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            config);

            KnowledgeAnswerabilityResult result =
                    judge.judge(
                            "设备长期离线应该按什么顺序排查？",
                            List.of(
                                    hit(
                                            "doc-offline",
                                            "For a device that has been offline for a prolonged period, troubleshoot in this order: verify power, SIM, coverage, then firmware."),
                                    hit(
                                            "doc-field",
                                            "This passage does not define SignalStrength.")));

            assertEquals(1, result.hits().size());
            assertEquals(
                    "doc-offline",
                    result.hits().get(0).documentId());
            assertEquals(
                    "KNOWLEDGE",
                    result.diagnostics().decision());
            assertEquals(
                    1,
                    result.diagnostics().supportedCount());
            assertEquals(
                    "DIRECT_ANSWER",
                    result.diagnostics()
                            .decisions()
                            .get(0)
                            .reasonCode());
            assertEquals(
                    "Bearer secret-judge-key",
                    authorization.get());
            assertTrue(
                    requestBody.get()
                            .contains("\"json_schema\""));
            assertTrue(
                    requestBody.get()
                            .contains("\"enable_thinking\":false"));
            assertFalse(
                    requestBody.get()
                            .contains("NO_ANSWER_SENTINEL"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnNoAnswerWhenNoCandidateCanAnswer()
            throws Exception {
        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(
                                "127.0.0.1",
                                0),
                        0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    String content =
                            """
                            {"decisions":[
                              {"index":0,"supported":false,"reasonCode":"TOPIC_ONLY","reason":"The passage is related to offline troubleshooting but does not define SignalStrength."}
                            ]}
                            """;
                    ObjectMapper mapper =
                            new ObjectMapper();
                    String body =
                            mapper.writeValueAsString(
                                    java.util.Map.of(
                                            "choices",
                                            List.of(
                                                    java.util.Map.of(
                                                            "message",
                                                            java.util.Map.of(
                                                                    "content",
                                                                    content)))));
                    byte[] bytes =
                            body.getBytes(
                                    StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            200,
                            bytes.length);
                    exchange.getResponseBody()
                            .write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeAnswerabilityProperties config =
                    new KnowledgeAnswerabilityProperties();
            config.setEnabled(true);
            config.setBaseUrl(
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/chat/completions");
            config.setApiKey("secret-judge-key");
            config.setRequestTimeout(
                    Duration.ofSeconds(2));

            DashScopeKnowledgeAnswerabilityJudge judge =
                    new DashScopeKnowledgeAnswerabilityJudge(
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            config);

            KnowledgeAnswerabilityResult result =
                    judge.judge(
                            "SignalStrength=0 是什么意思？",
                            List.of(
                                    hit(
                                            "doc-offline",
                                            "Offline troubleshooting procedure.")));

            assertTrue(result.hits().isEmpty());
            assertEquals(
                    "NO_ANSWER",
                    result.diagnostics().decision());
            assertEquals(
                    "TOPIC_ONLY",
                    result.diagnostics()
                            .decisions()
                            .get(0)
                            .reasonCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotEchoSecretWhenProviderFails()
            throws Exception {
        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(
                                "127.0.0.1",
                                0),
                        0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    byte[] body =
                            """
                            {"error":{"code":"QuotaExceeded","message":"quota exhausted"},"request_id":"req-judge-1"}
                            """
                                    .getBytes(
                                            StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            429,
                            body.length);
                    exchange.getResponseBody()
                            .write(body);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeAnswerabilityProperties config =
                    new KnowledgeAnswerabilityProperties();
            config.setEnabled(true);
            config.setBaseUrl(
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/chat/completions");
            config.setApiKey("secret-judge-key");
            config.setRequestTimeout(
                    Duration.ofSeconds(2));

            DashScopeKnowledgeAnswerabilityJudge judge =
                    new DashScopeKnowledgeAnswerabilityJudge(
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            config);

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    judge.judge(
                                            "query",
                                            List.of(
                                                    hit(
                                                            "doc",
                                                            "content"))));

            assertTrue(
                    exception.getMessage()
                            .contains("HTTP 429"));
            assertTrue(
                    exception.getMessage()
                            .contains("QuotaExceeded"));
            assertTrue(
                    exception.getMessage()
                            .contains("req-judge-1"));
            assertFalse(
                    exception.getMessage()
                            .contains("secret-judge-key"));
        } finally {
            server.stop(0);
        }
    }

    private static KnowledgeAnswerabilityProperties config(
            HttpServer server) {
        KnowledgeAnswerabilityProperties config =
                new KnowledgeAnswerabilityProperties();
        config.setEnabled(true);
        config.setBaseUrl(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/compatible-mode/v1");
        config.setApiKey("secret-judge-key");
        config.setModel("qwen3.8-flash");
        config.setCandidateLimit(8);
        config.setRequestTimeout(
                Duration.ofSeconds(2));
        return config;
    }

    private static KnowledgeSearchHit hit(
            String documentId,
            String content) {
        return new KnowledgeSearchHit(
                "KB:" + documentId + "#0",
                documentId,
                "chunk-" + documentId,
                0,
                "Title " + documentId,
                KnowledgeSourceType.TEST,
                "test://" + documentId,
                content,
                0.016d,
                "VECTOR",
                0.5d,
                0.8d);
    }
}
