package com.agentforge.infrastructure.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.rag.KnowledgeAccessScope;
import com.agentforge.rag.KnowledgeCandidate;
import com.agentforge.rag.KnowledgeIndexedChunk;
import com.agentforge.rag.KnowledgeSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ElasticsearchKnowledgeSearchBackendTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AccessPrincipal STAFF = AccessPrincipal.staff("staff-1");
    private static final AccessPrincipal CUSTOMER_A = AccessPrincipal.customer("user-a", "cust-a");

    @Test
    void shouldUseIndexedKnnOnElasticsearch8() throws Exception {
        List<RequestCapture> requests = runScenario("8.19.0");

        String allBodies =
                requests.stream().map(RequestCapture::body).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allBodies.contains("\"type\":\"dense_vector\""));
        assertTrue(allBodies.contains("\"dims\":3"));
        assertTrue(allBodies.contains("\"index\":true"));
        assertTrue(allBodies.contains("\"similarity\":\"cosine\""));
        assertTrue(allBodies.contains("\"knn\""));
        assertFalse(allBodies.contains("cosineSimilarity(params.query_vector"));
        assertRefreshHasNoBody(requests);
    }

    @Test
    void shouldUseScriptScoreCosineOnElasticsearch7() throws Exception {
        List<RequestCapture> requests = runScenario("7.17.24");

        String allBodies =
                requests.stream().map(RequestCapture::body).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allBodies.contains("\"type\":\"dense_vector\""));
        assertTrue(allBodies.contains("\"dims\":3"));
        assertFalse(allBodies.contains("\"index\":true"));
        assertFalse(allBodies.contains("\"similarity\":\"cosine\""));
        assertFalse(allBodies.contains("\"knn\""));
        assertTrue(allBodies.contains("cosineSimilarity(params.query_vector, 'embedding') + 1.0"));
        assertTrue(allBodies.contains("\"script_score\""));
        assertRefreshHasNoBody(requests);
    }

    private static void assertRefreshHasNoBody(List<RequestCapture> requests) {
        RequestCapture refresh =
                requests.stream()
                        .filter(request -> request.path().endsWith("/_refresh"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("POST", refresh.method());
        assertEquals("", refresh.body());
    }

    @Test
    void customerFilterShouldRideInsideKnnAndBoolFilter() throws Exception {
        List<RequestCapture> requests = runScenario("8.19.0", CUSTOMER_A);

        JsonNode vector = searchBody(requests, "knn");
        assertCustomerAFilter(vector.path("knn").path("filter"));
        // A post_filter would be applied after k neighbours were chosen and starve the caller.
        assertTrue(vector.path("post_filter").isMissingNode());

        JsonNode lexical = searchBody(requests, "multi_match");
        assertTrue(lexical.path("query").path("bool").path("must").get(0).has("multi_match"));
        assertCustomerAFilter(lexical.path("query").path("bool").path("filter").get(0));
    }

    @Test
    void customerFilterShouldReplaceMatchAllOnElasticsearch7() throws Exception {
        List<RequestCapture> requests = runScenario("7.17.24", CUSTOMER_A);

        JsonNode vector = searchBody(requests, "script_score");
        JsonNode inner = vector.path("query").path("script_score").path("query");
        assertTrue(inner.path("match_all").isMissingNode());
        assertCustomerAFilter(inner.path("bool").path("filter").get(0));
    }

    @Test
    void staffFilterShouldListEveryVisibilityExplicitly() throws Exception {
        List<RequestCapture> requests = runScenario("8.19.0", STAFF);

        JsonNode should = searchBody(requests, "knn").path("knn").path("filter").path("bool").path("should");
        assertEquals(1, should.size());
        assertEquals(
                "[\"INTERNAL\",\"PUBLIC\",\"CUSTOMER\"]",
                should.get(0).path("terms").path("visibility").toString());
    }

    @Test
    void indexShouldCarryAccessFieldsForPreFiltering() throws Exception {
        List<RequestCapture> requests = runScenario("8.19.0", STAFF);

        JsonNode mapping =
                JSON.readTree(
                                requests.stream()
                                        .filter(request -> "PUT".equals(request.method()))
                                        .findFirst()
                                        .orElseThrow()
                                        .body())
                        .path("mappings")
                        .path("properties");
        assertEquals("keyword", mapping.path("visibility").path("type").asText());
        assertEquals("keyword", mapping.path("customerId").path("type").asText());

        String bulk =
                requests.stream()
                        .filter(request -> "/_bulk".equals(request.path()))
                        .findFirst()
                        .orElseThrow()
                        .body();
        JsonNode source = JSON.readTree(bulk.split("\n")[1]);
        assertEquals("CUSTOMER", source.path("visibility").asText());
        assertEquals("cust-a", source.path("customerId").asText());
    }

    private static void assertCustomerAFilter(JsonNode filter) {
        JsonNode bool = filter.path("bool");
        assertEquals(1, bool.path("minimum_should_match").asInt());
        JsonNode should = bool.path("should");
        assertEquals(2, should.size());
        assertEquals("[\"PUBLIC\"]", should.get(0).path("terms").path("visibility").toString());
        JsonNode own = should.get(1).path("bool").path("filter");
        assertEquals("CUSTOMER", own.get(0).path("term").path("visibility").asText());
        assertEquals("cust-a", own.get(1).path("term").path("customerId").asText());
    }

    private static JsonNode searchBody(List<RequestCapture> requests, String marker)
            throws Exception {
        return JSON.readTree(
                requests.stream()
                        .filter(request -> request.path().endsWith("/_search"))
                        .filter(request -> request.body().contains("\"" + marker + "\""))
                        .findFirst()
                        .orElseThrow()
                        .body());
    }

    private static List<RequestCapture> runScenario(String version) throws Exception {
        return runScenario(version, STAFF);
    }

    private static List<RequestCapture> runScenario(String version, AccessPrincipal principal)
            throws Exception {
        List<RequestCapture> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    requests.add(
                            new RequestCapture(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().toString(),
                                    body));

                    int status = 200;
                    String response;
                    if ("GET".equals(exchange.getRequestMethod())
                            && "/".equals(exchange.getRequestURI().getPath())) {
                        response = "{\"version\":{\"number\":\"" + version + "\"}}";
                    } else if ("DELETE".equals(exchange.getRequestMethod())) {
                        status = 404;
                        response = "{\"error\":\"not_found\"}";
                    } else if (exchange.getRequestURI().getPath().endsWith("/_search")) {
                        response =
                                """
                                {"hits":{"hits":[
                                  {"_score":1.25,"_source":{
                                    "documentId":"doc-1",
                                    "chunkId":"chunk-1",
                                    "chunkIndex":0,
                                    "title":"Offline troubleshooting",
                                    "sourceType":"TEST",
                                    "sourceRef":"acceptance://offline",
                                    "content":"Check power and SIM."
                                  }}
                                ]}}
                                """;
                    } else if ("/_bulk".equals(exchange.getRequestURI().getPath())) {
                        response = "{\"errors\":false,\"items\":[]}";
                    } else {
                        response = "{\"acknowledged\":true}";
                    }

                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            KnowledgeHybridProperties.Elasticsearch config =
                    new KnowledgeHybridProperties.Elasticsearch();
            config.setBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            config.setIndexName("agentforge-knowledge-test");
            config.setRequestTimeout(Duration.ofSeconds(2));

            ElasticsearchKnowledgeSearchBackend backend =
                    new ElasticsearchKnowledgeSearchBackend(
                            HttpClient.newHttpClient(), new ObjectMapper(), config);

            backend.recreateIndex(3);
            KnowledgeCandidate candidate =
                    new KnowledgeCandidate(
                            "doc-1",
                            "chunk-1",
                            0,
                            "Offline troubleshooting",
                            KnowledgeSourceType.TEST,
                            "acceptance://offline",
                            "Check power and SIM.",
                            KnowledgeAccessScope.customer("cust-a"));
            backend.indexBatch(
                    List.of(
                            new KnowledgeIndexedChunk(
                                    candidate, new float[] {0.1f, 0.2f, 0.3f})));
            backend.refresh();

            var lexical = backend.searchLexical("offline", 5, principal);
            var vector = backend.searchVector(new float[] {0.3f, 0.2f, 0.1f}, 5, principal);

            assertEquals(1, lexical.size());
            assertEquals("doc-1", lexical.get(0).documentId());
            assertEquals(1, vector.size());
            assertEquals(
                    version.startsWith("7.")
                            ? "EXACT_SCRIPT_SCORE_COSINE"
                            : "APPROXIMATE_KNN",
                    backend.vectorSearchMode());

            double normalized =
                    backend.normalizeVectorScore(
                            version.startsWith("7.") ? 1.75d : 0.875d);
            assertEquals(0.75d, normalized, 0.0001d);

            assertTrue(
                    requests.stream()
                            .anyMatch(
                                    request ->
                                            "POST".equals(request.method())
                                                    && "/_bulk".equals(request.path())));
            return List.copyOf(requests);
        } finally {
            server.stop(0);
        }
    }

    private record RequestCapture(String method, String path, String body) {}
}
