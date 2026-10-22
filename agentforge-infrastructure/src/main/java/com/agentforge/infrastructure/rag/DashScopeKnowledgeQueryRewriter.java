package com.agentforge.infrastructure.rag;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.config.KnowledgeQueryRewriteProperties;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.rag.KnowledgeModelCallContext;
import com.agentforge.rag.KnowledgeQueryRewriteDiagnostics;
import com.agentforge.rag.KnowledgeQueryRewriteResult;
import com.agentforge.rag.KnowledgeQueryRewriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class DashScopeKnowledgeQueryRewriter implements KnowledgeQueryRewriter {

    private static final Set<String> REASON_CODES =
            Set.of(
                    "UNCHANGED",
                    "REMOVE_FILLER",
                    "CANONICALIZE_INTENT",
                    "CROSS_LANGUAGE_AUGMENTATION",
                    "MIXED");

    private static final String SYSTEM_PROMPT =
            """
            You are a conservative multilingual technical knowledge retrieval query rewriter.

            Rewrite the user's question into ONE concise retrieval query. Your output is used only
            for candidate retrieval, not as an answer.

            Rules:
            1. Do not answer the question.
            2. Do not add troubleshooting steps, causes, field meanings, values, or facts that are
               not already present in the user's question.
            3. Preserve exact technical identifiers, field names, error codes, numeric values,
               operators, device IDs, and casing verbatim (examples: SignalStrength=0,
               shadowStatus, CONFLICT, PeripheralStatus).
            4. Remove conversational filler and make the retrieval intent explicit.
            5. For non-English natural-language concepts, you may keep the original core terms and
               append concise English equivalents for the SAME concepts to improve multilingual
               retrieval. Do not translate or alter exact technical identifiers.
            6. If the query is already concise and retrieval-ready, return it unchanged.
            7. Return JSON only according to the provided schema.
            """;

    static final String STAGE = "QUERY_REWRITE";
    static final int GATEWAY_MAX_TOKENS = 512;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeQueryRewriteProperties properties;
    private final URI endpoint;
    private final ModelGatewayClient gateway;

    public DashScopeKnowledgeQueryRewriter(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            KnowledgeQueryRewriteProperties properties) {
        this(httpClient, objectMapper, properties, null);
    }

    /**
     * @param gateway when non-null, every call goes through the model gateway and {@code
     *     properties.model} is a gateway route name
     */
    public DashScopeKnowledgeQueryRewriter(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            KnowledgeQueryRewriteProperties properties,
            ModelGatewayClient gateway) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.gateway = gateway;
        String baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        this.endpoint =
                URI.create(
                        baseUrl.endsWith("/chat/completions")
                                ? baseUrl
                                : baseUrl + "/chat/completions");
    }

    @Override
    public KnowledgeQueryRewriteResult rewrite(String query) {
        return rewrite(query, null);
    }

    @Override
    public KnowledgeQueryRewriteResult rewrite(
            String query, KnowledgeModelCallContext context) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query rewrite input must not be blank");
        }

        String original = query.trim();
        if (original.length() > properties.getMaxQueryChars()) {
            throw new IllegalArgumentException("query rewrite input exceeds maxQueryChars");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", properties.getModel());
            body.put("temperature", 0);
            if (gateway == null) {
                body.put("enable_thinking", false);
            } else {
                // The gateway takes standard fields only and adds enable_thinking itself for
                // the models that need it.
                body.put("max_tokens", GATEWAY_MAX_TOKENS);
            }

            ArrayNode messages = body.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", SYSTEM_PROMPT);

            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", "QUERY:\n" + original);

            addStructuredOutputSchema(body);

            JsonNode root =
                    gateway == null
                            ? callDirect(body)
                            : KnowledgeGatewayCalls.complete(
                                    gateway, STAGE, body, context, properties.getRequestTimeout());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("query rewrite provider returned no choices");
            }

            String content =
                    choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IllegalStateException("query rewrite provider returned empty content");
            }

            JsonNode result = objectMapper.readTree(content);
            String rewritten = result.path("rewrittenQuery").asText("").trim();
            String reasonCode = result.path("reasonCode").asText("").trim();

            if (rewritten.isBlank()) {
                throw new IllegalStateException("query rewrite provider returned blank query");
            }
            if (rewritten.length() > properties.getMaxQueryChars()) {
                throw new IllegalStateException("query rewrite provider returned query above maxQueryChars");
            }
            if (!REASON_CODES.contains(reasonCode)) {
                throw new IllegalStateException("query rewrite provider returned invalid reason code");
            }

            boolean changed = !original.equals(rewritten);
            String decision = changed ? "REWRITTEN" : "UNCHANGED";
            return new KnowledgeQueryRewriteResult(
                    rewritten,
                    new KnowledgeQueryRewriteDiagnostics(
                            mode(),
                            original,
                            rewritten,
                            changed,
                            decision,
                            reasonCode));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("query rewrite request interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            if (exception instanceof ModelCallFailedException failure) {
                throw failure;
            }
            throw new IllegalStateException("query rewrite request failed", exception);
        }
    }

    @Override
    public String mode() {
        return "QWEN3_8_FLASH_QUERY_REWRITE_V1";
    }

    private JsonNode callDirect(ObjectNode body) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(properties.getRequestTimeout())
                        .header("Authorization", bearer(properties.getApiKey()))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(body),
                                        StandardCharsets.UTF_8))
                        .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw providerFailure(response);
        }
        return objectMapper.readTree(response.body());
    }

    private void addStructuredOutputSchema(ObjectNode body) {
        ObjectNode responseFormat = body.putObject("response_format");
        responseFormat.put("type", "json_schema");

        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "knowledge_query_rewrite");
        jsonSchema.put("strict", true);

        ObjectNode schema = jsonSchema.putObject("schema");
        schema.put("type", "object");

        ObjectNode propertiesNode = schema.putObject("properties");
        propertiesNode.putObject("rewrittenQuery").put("type", "string");

        ObjectNode reasonCode = propertiesNode.putObject("reasonCode");
        reasonCode.put("type", "string");
        ArrayNode reasonEnum = reasonCode.putArray("enum");
        reasonEnum.add("UNCHANGED");
        reasonEnum.add("REMOVE_FILLER");
        reasonEnum.add("CANONICALIZE_INTENT");
        reasonEnum.add("CROSS_LANGUAGE_AUGMENTATION");
        reasonEnum.add("MIXED");

        ArrayNode required = schema.putArray("required");
        required.add("rewrittenQuery");
        required.add("reasonCode");
        schema.put("additionalProperties", false);
    }

    private IllegalStateException providerFailure(HttpResponse<String> response) {
        String code = null;
        String message = null;
        String requestId = null;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode error = root.path("error");
            code = firstText(error.path("code"), root.path("code"));
            message = firstText(error.path("message"), root.path("message"));
            requestId =
                    firstText(
                            root.path("request_id"),
                            root.path("requestId"),
                            root.path("id"));
        } catch (Exception ignored) {
            // Never echo arbitrary provider bodies or credentials.
        }

        StringBuilder detail =
                new StringBuilder("query rewrite provider returned HTTP ")
                        .append(response.statusCode());
        if (hasText(code)) {
            detail.append(", code=").append(sanitize(code, 120));
        }
        if (hasText(message)) {
            detail.append(", message=").append(sanitize(message, 500));
        }
        if (hasText(requestId)) {
            detail.append(", requestId=").append(sanitize(requestId, 160));
        }
        return new IllegalStateException(detail.toString());
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String sanitize(String value, int maxLength) {
        String singleLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.length() <= maxLength
                ? singleLine
                : singleLine.substring(0, maxLength) + "...";
    }

    private static String bearer(String apiKey) {
        String trimmed = apiKey == null ? "" : apiKey.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7)
                ? trimmed
                : "Bearer " + trimmed;
    }
}
