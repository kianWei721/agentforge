package com.agentforge.infrastructure.gateway;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Blocking client for the model gateway's OpenAI-compatible {@code /v1/chat/completions}.
 *
 * <p>Every call carries the caller's correlation id and how long the caller will still wait, so the
 * gateway does not start an upstream attempt nobody will read. Failures surface as {@link
 * ModelCallFailedException} with the gateway's own error code and request id.
 */
public class ModelGatewayClient {

    public static final String REQUEST_ID_HEADER = "X-Gateway-Request-Id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String DEADLINE_HEADER = "X-Gateway-Deadline-Ms";
    static final String SERVED_PROVIDER_HEADER = "X-Gateway-Served-Provider";
    static final String SERVED_MODEL_HEADER = "X-Gateway-Served-Model";

    /** Leaves the gateway time to answer before our own HTTP timeout fires. */
    static final long DEADLINE_MARGIN_MILLIS = 500;
    private static final long MAX_DEADLINE_MILLIS = 600_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String authorization;

    public ModelGatewayClient(
            HttpClient httpClient, ObjectMapper objectMapper, ModelGatewayProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        String baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        this.endpoint =
                URI.create(
                        baseUrl.endsWith("/chat/completions")
                                ? baseUrl
                                : baseUrl + "/chat/completions");
        this.authorization = "Bearer " + properties.getApiKey().trim();
    }

    public Completion complete(ObjectNode body, String correlationId, Duration timeout) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/json")
                        .header(DEADLINE_HEADER, String.valueOf(deadlineMillis(timeout)));
        if (correlationId != null && !correlationId.isBlank()) {
            request.header(CORRELATION_HEADER, correlationId);
        }

        HttpResponse<String> response;
        try {
            request.POST(
                    HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            response =
                    httpClient.send(
                            request.build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException | ConnectException exception) {
            // Nothing reached the gateway, so nothing can have been billed.
            throw new ModelCallFailedException(
                    "GATEWAY_UNREACHABLE", true, null, "model gateway unreachable", exception);
        } catch (HttpTimeoutException exception) {
            // The gateway may still be calling, and paying, the vendor.
            throw new ModelCallFailedException(
                    "GATEWAY_TIMEOUT", false, null,
                    "model gateway did not answer within " + timeout, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelCallFailedException(
                    "GATEWAY_INTERRUPTED", false, null, "model gateway call interrupted", exception);
        } catch (IOException exception) {
            // The request may have been sent before the connection broke.
            throw new ModelCallFailedException(
                    "GATEWAY_CONNECTION_LOST", false, null,
                    "model gateway connection lost", exception);
        }

        String requestId = header(response, REQUEST_ID_HEADER);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(response, requestId);
        }
        JsonNode root;
        try {
            root = readJson(response.body());
        } catch (IOException exception) {
            throw new ModelCallFailedException(
                    "INVALID_GATEWAY_RESPONSE", false, requestId,
                    "model gateway returned a non-JSON body", exception);
        }
        // An empty 2xx is not an empty answer: the call may have been billed, so it is not retried.
        if (!root.isObject()) {
            throw new ModelCallFailedException(
                    "INVALID_GATEWAY_RESPONSE", false, requestId,
                    "model gateway returned an empty or non-object body");
        }
        return new Completion(
                root,
                requestId,
                header(response, SERVED_PROVIDER_HEADER),
                header(response, SERVED_MODEL_HEADER));
    }

    static long deadlineMillis(Duration timeout) {
        return Math.min(
                MAX_DEADLINE_MILLIS, Math.max(1, timeout.toMillis() - DEADLINE_MARGIN_MILLIS));
    }

    /**
     * The body as JSON, never null. Jackson 2.21 reads an empty body as a MissingNode, but some
     * versions and overloads have returned null, so this does not depend on which.
     */
    private JsonNode readJson(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body == null ? "" : body);
        return root == null ? MissingNode.getInstance() : root;
    }

    private ModelCallFailedException failure(HttpResponse<String> response, String requestId) {
        String code = null;
        String message = null;
        try {
            JsonNode error = readJson(response.body()).path("error");
            code = text(error.path("code"));
            message = text(error.path("message"));
        } catch (IOException ignored) {
            // Not the gateway's error envelope (a proxy in between, say); the status still tells.
        }
        if (code == null) {
            code = "HTTP_" + response.statusCode();
        }
        StringBuilder detail =
                new StringBuilder("model gateway returned HTTP ")
                        .append(response.statusCode())
                        .append(' ')
                        .append(code);
        if (message != null) {
            detail.append(": ").append(sanitize(message));
        }
        return new ModelCallFailedException(
                code, ModelCallFailedException.retryable(code), requestId, detail.toString());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String sanitize(String value) {
        String singleLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300) + "...";
    }

    /**
     * A successful gateway answer.
     *
     * @param body the OpenAI-format response body
     * @param servedModel the model that actually answered, which can differ from the route's first
     *     choice after a failover
     */
    public record Completion(
            JsonNode body, String gatewayRequestId, String servedProvider, String servedModel) {

        /** {@code choices[0].message.content}, or an empty string. */
        public String content() {
            return body.path("choices").path(0).path("message").path("content").asText("");
        }
    }
}
