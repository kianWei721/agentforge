package com.agentforge.infrastructure.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.KnowledgeQueryRewriteProperties;
import com.agentforge.rag.KnowledgeQueryRewriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DashScopeKnowledgeQueryRewriterTest {

    @Test
    void shouldRewriteNaturalLanguageQueryAndPreserveExactIdentifier() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/compatible-mode/v1/chat/completions",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));

                    String content =
                            """
                            {"rewrittenQuery":"SignalStrength=0 字段语义 | SignalStrength=0 field semantics meaning","reasonCode":"CROSS_LANGUAGE_AUGMENTATION"}
                            """;
                    String body =
                            new ObjectMapper()
                                    .writeValueAsString(
                                            Map.of(
                                                    "choices",
                                                    List.of(
                                                            Map.of(
                                                                    "message",
                                                                    Map.of("content", content)))));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();

        try {
            KnowledgeQueryRewriteProperties config = config(server);
            DashScopeKnowledgeQueryRewriter rewriter =
                    new DashScopeKnowledgeQueryRewriter(
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            config);

            KnowledgeQueryRewriteResult result =
                    rewriter.rewrite("SignalStrength=0 在这个设备里具体代表什么？");

            assertTrue(result.retrievalQuery().contains("SignalStrength=0"));
            assertTrue(result.diagnostics().changed());
            assertEquals("REWRITTEN", result.diagnostics().decision());
            assertEquals(
                    "CROSS_LANGUAGE_AUGMENTATION",
                    result.diagnostics().reasonCode());
            assertEquals("Bearer secret-rewrite-key", authorization.get());
            assertTrue(requestBody.get().contains("\"json_schema\""));
            assertTrue(requestBody.get().contains("\"enable_thinking\":false"));
            assertTrue(requestBody.get().contains("SignalStrength=0"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldAllowAlreadyConciseQueryToRemainUnchanged() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    String content =
                            """
                            {"rewrittenQuery":"shadowStatus CONFLICT","reasonCode":"UNCHANGED"}
                            """;
                    String body =
                            new ObjectMapper()
                                    .writeValueAsString(
                                            Map.of(
                                                    "choices",
                                                    List.of(
                                                            Map.of(
                                                                    "message",
                                                                    Map.of("content", content)))));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();

        try {
            KnowledgeQueryRewriteProperties config = new KnowledgeQueryRewriteProperties();
            config.setEnabled(true);
            config.setBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            config.setApiKey("secret-rewrite-key");
            config.setRequestTimeout(Duration.ofSeconds(2));

            DashScopeKnowledgeQueryRewriter rewriter =
                    new DashScopeKnowledgeQueryRewriter(
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            config);

            KnowledgeQueryRewriteResult result =
                    rewriter.rewrite("shadowStatus CONFLICT");

            assertFalse(result.diagnostics().changed());
            assertEquals("UNCHANGED", result.diagnostics().decision());
            assertEquals("shadowStatus CONFLICT", result.retrievalQuery());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotEchoSecretWhenProviderFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    byte[] body =
                            """
                            {"error":{"code":"QuotaExceeded","message":"quota exhausted"},"request_id":"req-rewrite-1"}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        try {
            KnowledgeQueryRewriteProperties config = new KnowledgeQueryRewriteProperties();
            config.setEnabled(true);
            config.setBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            config.setApiKey("secret-rewrite-key");
            config.setRequestTimeout(Duration.ofSeconds(2));

            DashScopeKnowledgeQueryRewriter rewriter =
                    new DashScopeKnowledgeQueryRewriter(
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            config);

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> rewriter.rewrite("设备离线怎么排查"));

            assertTrue(exception.getMessage().contains("HTTP 429"));
            assertTrue(exception.getMessage().contains("QuotaExceeded"));
            assertTrue(exception.getMessage().contains("req-rewrite-1"));
            assertFalse(exception.getMessage().contains("secret-rewrite-key"));
        } finally {
            server.stop(0);
        }
    }

    private static KnowledgeQueryRewriteProperties config(HttpServer server) {
        KnowledgeQueryRewriteProperties config = new KnowledgeQueryRewriteProperties();
        config.setEnabled(true);
        config.setBaseUrl(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/compatible-mode/v1");
        config.setApiKey("secret-rewrite-key");
        config.setModel("qwen3.8-flash");
        config.setRequestTimeout(Duration.ofSeconds(2));
        config.setMaxQueryChars(512);
        return config;
    }
}
