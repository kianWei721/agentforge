package com.agentforge.infrastructure.rag;

import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.rag.EmbeddingProvider;
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
import java.util.Comparator;
import java.util.List;

public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeHybridProperties.Embedding properties;
    private final URI endpoint;

    public DashScopeEmbeddingProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            KnowledgeHybridProperties.Embedding properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        String baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        this.endpoint =
                URI.create(
                        baseUrl.endsWith("/embeddings")
                                ? baseUrl
                                : baseUrl + "/embeddings");
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (texts.size() > properties.getBatchSize()) {
            throw new IllegalArgumentException(
                    "embedding batch exceeds configured batch size: " + properties.getBatchSize());
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", properties.getModel());
            ArrayNode input = requestBody.putArray("input");
            for (String text : texts) {
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("embedding input must not be blank");
                }
                input.add(text);
            }
            requestBody.put("dimensions", properties.getDimensions());
            requestBody.put("encoding_format", "float");

            HttpRequest request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(properties.getRequestTimeout())
                            .header("Authorization", bearer(properties.getApiKey()))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(requestBody),
                                            StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "embedding provider returned HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new IllegalStateException(
                        "embedding provider returned unexpected data count");
            }

            List<IndexedVector> indexed = new ArrayList<>(data.size());
            int fallbackIndex = 0;
            for (JsonNode item : data) {
                int index = item.has("index") ? item.path("index").asInt() : fallbackIndex;
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray()) {
                    throw new IllegalStateException("embedding vector is missing");
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                if (vector.length != properties.getDimensions()) {
                    throw new IllegalStateException(
                            "embedding dimension mismatch: expected="
                                    + properties.getDimensions()
                                    + ", actual="
                                    + vector.length);
                }
                indexed.add(new IndexedVector(index, vector));
                fallbackIndex++;
            }

            indexed.sort(Comparator.comparingInt(IndexedVector::index));
            return indexed.stream().map(IndexedVector::vector).toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding request interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("embedding request failed", exception);
        }
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    private static String bearer(String apiKey) {
        String trimmed = apiKey == null ? "" : apiKey.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7)
                ? trimmed
                : "Bearer " + trimmed;
    }

    private record IndexedVector(int index, float[] vector) {}
}
