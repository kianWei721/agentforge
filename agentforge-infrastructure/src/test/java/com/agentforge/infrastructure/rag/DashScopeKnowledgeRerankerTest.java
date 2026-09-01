package com.agentforge.infrastructure.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.KnowledgeRerankProperties;
import com.agentforge.rag.KnowledgeRerankResult;
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

class DashScopeKnowledgeRerankerTest {

    @Test
    void shouldKeepOnlyRealCandidatesRankedBeforeNoAnswerSentinel() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/compatible-api/v1/reranks",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] body =
                            """
                            {"results":[
                              {"index":0,"relevance_score":0.95},
                              {"index":2,"relevance_score":0.78},
                              {"index":1,"relevance_score":0.35}
                            ]}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeRerankProperties config = config(server);
            DashScopeKnowledgeReranker reranker =
                    new DashScopeKnowledgeReranker(
                            HttpClient.newHttpClient(), new ObjectMapper(), config);

            KnowledgeRerankResult result =
                    reranker.rerank(
                            "offline troubleshooting",
                            List.of(hit("doc-good"), hit("doc-below-sentinel")),
                            5);

            assertFalse(result.noAnswer());
            assertEquals(1, result.hits().size());
            assertEquals("doc-good", result.hits().get(0).documentId());
            assertEquals(0.95d, result.hits().get(0).rerankScore(), 0.0001d);
            assertEquals(0.78d, result.noAnswerScore(), 0.0001d);
            assertEquals("Bearer secret-rerank-key", authorization.get());
            assertTrue(requestBody.get().contains("\"qwen3-rerank\""));
            assertTrue(requestBody.get().contains("NO_ANSWER_SENTINEL"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnNoAnswerWhenSentinelRanksFirst() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/reranks",
                exchange -> {
                    byte[] body =
                            """
                            {"results":[
                              {"index":1,"relevance_score":0.82},
                              {"index":0,"relevance_score":0.41}
                            ]}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeRerankProperties config = new KnowledgeRerankProperties();
            config.setEnabled(true);
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/reranks");
            config.setApiKey("secret-rerank-key");
            config.setRequestTimeout(Duration.ofSeconds(2));

            DashScopeKnowledgeReranker reranker =
                    new DashScopeKnowledgeReranker(
                            HttpClient.newHttpClient(), new ObjectMapper(), config);

            KnowledgeRerankResult result =
                    reranker.rerank(
                            "SignalStrength=0 means what?",
                            List.of(hit("doc-offline")),
                            5);

            assertTrue(result.noAnswer());
            assertTrue(result.hits().isEmpty());
            assertEquals(0.82d, result.noAnswerScore(), 0.0001d);
            assertNotNull(result.diagnostics());
            assertEquals(0.41d, result.diagnostics().topRealScore(), 0.0001d);
            assertEquals(2, result.diagnostics().topRealRank());
            assertEquals(1, result.diagnostics().noAnswerRank());
            assertEquals("NO_ANSWER_SENTINEL", result.diagnostics().decision());
            assertEquals(0, result.diagnostics().acceptedCount());
            assertNotNull(result.diagnostics().topRealTextSha256());
            assertEquals(64, result.diagnostics().topRealTextSha256().length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotEchoSecretWhenProviderFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/reranks",
                exchange -> {
                    byte[] body =
                            """
                            {"error":{"code":"QuotaExceeded","message":"quota exhausted"},"request_id":"req-rerank-1"}
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeRerankProperties config = new KnowledgeRerankProperties();
            config.setEnabled(true);
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/reranks");
            config.setApiKey("secret-rerank-key");
            config.setRequestTimeout(Duration.ofSeconds(2));

            DashScopeKnowledgeReranker reranker =
                    new DashScopeKnowledgeReranker(
                            HttpClient.newHttpClient(), new ObjectMapper(), config);

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    reranker.rerank(
                                            "query",
                                            List.of(hit("doc")),
                                            5));

            assertTrue(exception.getMessage().contains("HTTP 429"));
            assertTrue(exception.getMessage().contains("QuotaExceeded"));
            assertTrue(exception.getMessage().contains("req-rerank-1"));
            assertFalse(exception.getMessage().contains("secret-rerank-key"));
        } finally {
            server.stop(0);
        }
    }

    private static KnowledgeRerankProperties config(HttpServer server) {
        KnowledgeRerankProperties config = new KnowledgeRerankProperties();
        config.setEnabled(true);
        config.setBaseUrl(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/compatible-api/v1");
        config.setApiKey("secret-rerank-key");
        config.setModel("qwen3-rerank");
        config.setCandidateLimit(8);
        config.setRequestTimeout(Duration.ofSeconds(2));
        return config;
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
                "Content " + documentId,
                0.016d,
                "VECTOR",
                0.5d,
                null);
    }
}
