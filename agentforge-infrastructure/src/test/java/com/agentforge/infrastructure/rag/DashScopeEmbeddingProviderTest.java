package com.agentforge.infrastructure.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DashScopeEmbeddingProviderTest {

    @Test
    void shouldCallOpenAiCompatibleEndpointAndPreserveInputOrder() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/compatible-mode/v1/embeddings",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] body =
                            """
                            {"object":"list","model":"qwen3.7-text-embedding","data":[
                              {"object":"embedding","index":1,"embedding":[0.4,0.5,0.6]},
                              {"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}
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
            KnowledgeHybridProperties.Embedding config =
                    new KnowledgeHybridProperties.Embedding();
            config.setBaseUrl(
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/compatible-mode/v1");
            config.setApiKey("secret-key");
            config.setModel("qwen3.7-text-embedding");
            config.setDimensions(3);
            config.setBatchSize(10);
            config.setRequestTimeout(Duration.ofSeconds(2));

            DashScopeEmbeddingProvider provider =
                    new DashScopeEmbeddingProvider(
                            HttpClient.newHttpClient(), new ObjectMapper(), config);

            List<float[]> vectors = provider.embed(List.of("first", "second"));

            assertEquals(2, vectors.size());
            assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, vectors.get(0), 0.0001f);
            assertArrayEquals(new float[] {0.4f, 0.5f, 0.6f}, vectors.get(1), 0.0001f);
            assertEquals("Bearer secret-key", authorization.get());
            assertTrue(requestBody.get().contains("\"dimensions\":3"));
            assertTrue(requestBody.get().contains("\"qwen3.7-text-embedding\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldExposeSafeProviderErrorDetails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/embeddings",
                exchange -> {
                    byte[] body =
                            """
                            {
                              "error":{
                                "message":"Free quota exhausted.",
                                "type":"AllocationQuota.FreeTierOnly",
                                "code":"AllocationQuota.FreeTierOnly"
                              },
                              "request_id":"req-123"
                            }
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(400, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeHybridProperties.Embedding config =
                    new KnowledgeHybridProperties.Embedding();
            config.setBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings");
            config.setApiKey("secret-key");
            config.setDimensions(3);
            config.setRequestTimeout(Duration.ofSeconds(2));

            DashScopeEmbeddingProvider provider =
                    new DashScopeEmbeddingProvider(
                            HttpClient.newHttpClient(), new ObjectMapper(), config);

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> provider.embed(List.of("query")));

            assertTrue(exception.getMessage().contains("HTTP 400"));
            assertTrue(
                    exception.getMessage()
                            .contains("code=AllocationQuota.FreeTierOnly"));
            assertTrue(exception.getMessage().contains("message=Free quota exhausted."));
            assertTrue(exception.getMessage().contains("requestId=req-123"));
            assertFalse(exception.getMessage().contains("secret-key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectUnexpectedEmbeddingDimensions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/embeddings",
                exchange -> {
                    byte[] body =
                            "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeHybridProperties.Embedding config =
                    new KnowledgeHybridProperties.Embedding();
            config.setBaseUrl(
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/embeddings");
            config.setApiKey("secret-key");
            config.setDimensions(3);
            config.setRequestTimeout(Duration.ofSeconds(2));

            DashScopeEmbeddingProvider provider =
                    new DashScopeEmbeddingProvider(
                            HttpClient.newHttpClient(), new ObjectMapper(), config);

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> provider.embed(List.of("query")));
            assertTrue(exception.getMessage().contains("dimension mismatch"));
        } finally {
            server.stop(0);
        }
    }
}
