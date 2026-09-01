package com.agentforge.infrastructure.rag;

import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.rag.KnowledgeBackendHit;
import com.agentforge.rag.KnowledgeIndexedChunk;
import com.agentforge.rag.KnowledgeSearchBackend;
import com.agentforge.rag.KnowledgeSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class ElasticsearchKnowledgeSearchBackend implements KnowledgeSearchBackend {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeHybridProperties.Elasticsearch properties;
    private final String baseUrl;
    private final String indexName;
    private volatile Integer elasticsearchMajorVersion;

    public ElasticsearchKnowledgeSearchBackend(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            KnowledgeHybridProperties.Elasticsearch properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        this.indexName = validateIndexName(properties.getIndexName());
    }

    @Override
    public void recreateIndex(int dimensions) {
        int majorVersion = elasticsearchMajorVersion();

        HttpResponse<String> delete = send("DELETE", "/" + indexName, null, "application/json");
        if (delete.statusCode() != 200
                && delete.statusCode() != 202
                && delete.statusCode() != 404) {
            throw failure("delete index", delete.statusCode());
        }

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode propertiesNode =
                root.putObject("mappings").putObject("properties");
        propertiesNode.putObject("documentId").put("type", "keyword");
        propertiesNode.putObject("chunkId").put("type", "keyword");
        propertiesNode.putObject("chunkIndex").put("type", "integer");
        propertiesNode.putObject("title").put("type", "text");
        propertiesNode.putObject("sourceType").put("type", "keyword");
        propertiesNode.putObject("sourceRef").put("type", "keyword");
        propertiesNode.putObject("content").put("type", "text");
        ObjectNode vector = propertiesNode.putObject("embedding");
        vector.put("type", "dense_vector");
        vector.put("dims", dimensions);
        if (majorVersion >= 8) {
            vector.put("index", true);
            vector.put("similarity", "cosine");
        }

        HttpResponse<String> create =
                send("PUT", "/" + indexName, json(root), "application/json");
        require2xx("create index", create);
    }

    @Override
    public void indexBatch(List<KnowledgeIndexedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            StringBuilder ndjson = new StringBuilder();
            for (KnowledgeIndexedChunk indexedChunk : chunks) {
                ObjectNode action = objectMapper.createObjectNode();
                ObjectNode index = action.putObject("index");
                index.put("_index", indexName);
                index.put("_id", indexedChunk.candidate().chunkId());
                ndjson.append(objectMapper.writeValueAsString(action)).append('\n');

                ObjectNode source = objectMapper.createObjectNode();
                var candidate = indexedChunk.candidate();
                source.put("documentId", candidate.documentId());
                source.put("chunkId", candidate.chunkId());
                source.put("chunkIndex", candidate.chunkIndex());
                source.put("title", candidate.title());
                source.put("sourceType", candidate.sourceType().name());
                source.put("sourceRef", candidate.sourceRef());
                source.put("content", candidate.content());
                ArrayNode embedding = source.putArray("embedding");
                for (float value : indexedChunk.embedding()) {
                    embedding.add(value);
                }
                ndjson.append(objectMapper.writeValueAsString(source)).append('\n');
            }

            HttpResponse<String> response =
                    send("POST", "/_bulk", ndjson.toString(), "application/x-ndjson");
            require2xx("bulk index", response);
            JsonNode body = objectMapper.readTree(response.body());
            if (body.path("errors").asBoolean(false)) {
                throw new IllegalStateException(
                        "Elasticsearch bulk indexing reported item errors");
            }
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Elasticsearch bulk indexing failed", exception);
        }
    }

    @Override
    public void refresh() {
        HttpResponse<String> response =
                send("POST", "/" + indexName + "/_refresh", null, "application/json");
        require2xx("refresh index", response);
    }

    @Override
    public List<KnowledgeBackendHit> searchLexical(String query, int limit) {
        ObjectNode root = searchSource(limit);
        ObjectNode multiMatch = root.putObject("query").putObject("multi_match");
        multiMatch.put("query", query);
        ArrayNode fields = multiMatch.putArray("fields");
        fields.add("title^2");
        fields.add("content");
        return search(root);
    }

    @Override
    public List<KnowledgeBackendHit> searchVector(float[] queryVector, int limit) {
        return elasticsearchMajorVersion() >= 8
                ? searchApproximateKnn(queryVector, limit)
                : searchExactCosineScriptScore(queryVector, limit);
    }

    @Override
    public String indexName() {
        return indexName;
    }

    @Override
    public String vectorSearchMode() {
        return elasticsearchMajorVersion() >= 8
                ? "APPROXIMATE_KNN"
                : "EXACT_SCRIPT_SCORE_COSINE";
    }

    @Override
    public double normalizeVectorScore(double backendScore) {
        double cosine =
                elasticsearchMajorVersion() >= 8
                        ? (2.0d * backendScore) - 1.0d
                        : backendScore - 1.0d;
        return Math.max(-1.0d, Math.min(1.0d, cosine));
    }

    private List<KnowledgeBackendHit> searchApproximateKnn(float[] queryVector, int limit) {
        ObjectNode root = searchSource(limit);
        ObjectNode knn = root.putObject("knn");
        knn.put("field", "embedding");
        ArrayNode vector = knn.putArray("query_vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        knn.put("k", limit);
        knn.put("num_candidates", Math.min(10000, Math.max(50, limit * 5)));
        return search(root);
    }

    private List<KnowledgeBackendHit> searchExactCosineScriptScore(
            float[] queryVector, int limit) {
        ObjectNode root = searchSource(limit);
        ObjectNode scriptScore = root.putObject("query").putObject("script_score");
        scriptScore.putObject("query").putObject("match_all");
        ObjectNode script = scriptScore.putObject("script");
        script.put("source", "cosineSimilarity(params.query_vector, 'embedding') + 1.0");
        ArrayNode vector =
                script.putObject("params").putArray("query_vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        return search(root);
    }

    private int elasticsearchMajorVersion() {
        Integer current = elasticsearchMajorVersion;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (elasticsearchMajorVersion == null) {
                HttpResponse<String> response =
                        send("GET", "/", null, "application/json");
                require2xx("read version", response);
                try {
                    String version =
                            objectMapper
                                    .readTree(response.body())
                                    .path("version")
                                    .path("number")
                                    .asText("");
                    if (!version.matches("\\d+\\..*")) {
                        throw new IllegalStateException(
                                "Elasticsearch version number is missing");
                    }
                    elasticsearchMajorVersion =
                            Integer.parseInt(version.substring(0, version.indexOf('.')));
                } catch (Exception exception) {
                    if (exception instanceof IllegalStateException stateException) {
                        throw stateException;
                    }
                    throw new IllegalStateException(
                            "Failed to parse Elasticsearch version", exception);
                }
            }
            return elasticsearchMajorVersion;
        }
    }

    private ObjectNode searchSource(int limit) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", limit);
        ArrayNode source = root.putArray("_source");
        source.add("documentId");
        source.add("chunkId");
        source.add("chunkIndex");
        source.add("title");
        source.add("sourceType");
        source.add("sourceRef");
        source.add("content");
        return root;
    }

    private List<KnowledgeBackendHit> search(ObjectNode body) {
        try {
            HttpResponse<String> response =
                    send(
                            "POST",
                            "/" + indexName + "/_search",
                            json(body),
                            "application/json");
            require2xx("search", response);
            JsonNode hits = objectMapper.readTree(response.body()).path("hits").path("hits");
            if (!hits.isArray()) {
                return List.of();
            }

            List<KnowledgeBackendHit> result = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                result.add(
                        new KnowledgeBackendHit(
                                source.path("documentId").asText(),
                                source.path("chunkId").asText(),
                                source.path("chunkIndex").asInt(),
                                source.path("title").asText(),
                                KnowledgeSourceType.valueOf(source.path("sourceType").asText()),
                                source.path("sourceRef").asText(""),
                                source.path("content").asText(),
                                hit.path("_score").asDouble()));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Elasticsearch search failed", exception);
        }
    }

    private HttpResponse<String> send(
            String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(properties.getRequestTimeout())
                            .header("Accept", "application/json");

            String authorization = authorization();
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            if (body != null) {
                builder.header("Content-Type", contentType);
            }

            HttpRequest.BodyPublisher publisher =
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(
                                    body, StandardCharsets.UTF_8);
            builder.method(method, publisher);

            return httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Elasticsearch request interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Elasticsearch request failed", exception);
        }
    }

    private String authorization() {
        if (hasText(properties.getApiKey())) {
            String value = properties.getApiKey().trim();
            return value.regionMatches(true, 0, "ApiKey ", 0, 7)
                    ? value
                    : "ApiKey " + value;
        }
        if (hasText(properties.getUsername())) {
            String raw =
                    properties.getUsername().trim()
                            + ":"
                            + (properties.getPassword() == null
                                    ? ""
                                    : properties.getPassword());
            return "Basic "
                    + Base64.getEncoder()
                            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    private static void require2xx(
            String operation, HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(operation, response.statusCode());
        }
    }

    private static IllegalStateException failure(
            String operation, int statusCode) {
        return new IllegalStateException(
                "Elasticsearch " + operation + " failed with HTTP " + statusCode);
    }

    private static String validateIndexName(String value) {
        String index = value == null ? "" : value.trim();
        if (!index.matches("[a-z0-9][a-z0-9._-]{0,254}")) {
            throw new IllegalArgumentException("Invalid Elasticsearch index name");
        }
        return index;
    }
}
