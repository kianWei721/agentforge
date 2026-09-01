package com.agentforge.infrastructure.rag;

import com.agentforge.infrastructure.config.KnowledgeAnswerabilityProperties;
import com.agentforge.rag.KnowledgeAnswerabilityDecision;
import com.agentforge.rag.KnowledgeAnswerabilityDiagnostics;
import com.agentforge.rag.KnowledgeAnswerabilityJudge;
import com.agentforge.rag.KnowledgeAnswerabilityResult;
import com.agentforge.rag.KnowledgeSearchHit;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DashScopeKnowledgeAnswerabilityJudge
        implements KnowledgeAnswerabilityJudge {

    private static final Set<String> REASON_CODES =
            Set.of(
                    "DIRECT_ANSWER",
                    "TOPIC_ONLY",
                    "MISSING_REQUIRED_SEMANTICS",
                    "INSUFFICIENT_EVIDENCE");

    private static final String SYSTEM_PROMPT =
            """
            You are a conservative knowledge-grounding answerability judge.

            Your task is NOT to answer the user's question.

            Determine only whether each supplied KNOWLEDGE PASSAGE, by itself, contains sufficient explicit information to directly answer the QUERY.

            Rules:
            1. Use ONLY the supplied passage.
            2. Do not use world knowledge, assumptions, or inferred device semantics.
            3. Topic similarity is not sufficient.
            4. If the query asks for the meaning of a field or value, the passage must explicitly define that field or value.
            5. If the query asks for a procedure, the passage must explicitly provide that procedure.
            6. A passage that merely discusses the same device/fault/topic is UNSUPPORTED.
            7. When uncertain, return supported=false.
            8. Return exactly one decision for every candidate index.
            9. Output JSON only according to the provided schema.
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeAnswerabilityProperties properties;
    private final URI endpoint;

    public DashScopeKnowledgeAnswerabilityJudge(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            KnowledgeAnswerabilityProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        String baseUrl =
                properties.getBaseUrl().trim().replaceAll("/+$", "");
        this.endpoint =
                URI.create(
                        baseUrl.endsWith("/chat/completions")
                                ? baseUrl
                                : baseUrl + "/chat/completions");
    }

    @Override
    public KnowledgeAnswerabilityResult judge(
            String query,
            List<KnowledgeSearchHit> candidates) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(
                    "answerability query must not be blank");
        }
        if (candidates == null || candidates.isEmpty()) {
            KnowledgeAnswerabilityDiagnostics diagnostics =
                    new KnowledgeAnswerabilityDiagnostics(
                            mode(),
                            0,
                            0,
                            "NO_CANDIDATES",
                            List.of());
            return new KnowledgeAnswerabilityResult(
                    List.of(),
                    diagnostics);
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", properties.getModel());
            body.put("temperature", 0);
            body.put("enable_thinking", false);

            ArrayNode messages = body.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", SYSTEM_PROMPT);

            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put(
                    "content",
                    buildUserPrompt(
                            query.trim(),
                            candidates));

            addStructuredOutputSchema(body);

            HttpRequest request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(properties.getRequestTimeout())
                            .header(
                                    "Authorization",
                                    bearer(properties.getApiKey()))
                            .header(
                                    "Content-Type",
                                    "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(body),
                                            StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8));
            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {
                throw providerFailure(response);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException(
                        "answerability provider returned no choices");
            }

            String content =
                    choices.get(0)
                            .path("message")
                            .path("content")
                            .asText("");
            if (content.isBlank()) {
                throw new IllegalStateException(
                        "answerability provider returned empty content");
            }

            JsonNode decisionsNode =
                    objectMapper.readTree(content).path("decisions");
            if (!decisionsNode.isArray()
                    || decisionsNode.size() != candidates.size()) {
                throw new IllegalStateException(
                        "answerability provider returned unexpected decision count");
            }

            List<DecisionItem> items =
                    new ArrayList<>(decisionsNode.size());
            Set<Integer> seen = new HashSet<>();
            for (JsonNode node : decisionsNode) {
                int index = node.path("index").asInt(-1);
                if (index < 0
                        || index >= candidates.size()
                        || !seen.add(index)) {
                    throw new IllegalStateException(
                            "answerability provider returned invalid candidate index");
                }
                if (!node.has("supported")
                        || !node.path("supported").isBoolean()) {
                    throw new IllegalStateException(
                            "answerability provider returned missing supported flag");
                }

                boolean supported =
                        node.path("supported").asBoolean();
                String reasonCode =
                        node.path("reasonCode").asText("");
                if (!REASON_CODES.contains(reasonCode)) {
                    throw new IllegalStateException(
                            "answerability provider returned invalid reason code");
                }
                if (supported
                        && !"DIRECT_ANSWER".equals(reasonCode)) {
                    throw new IllegalStateException(
                            "supported answerability decision must use DIRECT_ANSWER");
                }
                if (!supported
                        && "DIRECT_ANSWER".equals(reasonCode)) {
                    throw new IllegalStateException(
                            "unsupported answerability decision cannot use DIRECT_ANSWER");
                }

                String reason =
                        sanitize(
                                node.path("reason").asText(""),
                                500);
                items.add(
                        new DecisionItem(
                                index,
                                supported,
                                reasonCode,
                                reason));
            }
            items.sort(
                    Comparator.comparingInt(DecisionItem::index));

            List<KnowledgeSearchHit> accepted =
                    new ArrayList<>();
            List<KnowledgeAnswerabilityDecision> decisions =
                    new ArrayList<>();
            for (DecisionItem item : items) {
                KnowledgeSearchHit candidate =
                        candidates.get(item.index());
                decisions.add(
                        new KnowledgeAnswerabilityDecision(
                                item.index(),
                                candidate.citationId(),
                                item.supported(),
                                item.reasonCode(),
                                item.reason()));
                if (item.supported()) {
                    accepted.add(candidate);
                }
            }

            KnowledgeAnswerabilityDiagnostics diagnostics =
                    new KnowledgeAnswerabilityDiagnostics(
                            mode(),
                            candidates.size(),
                            accepted.size(),
                            accepted.isEmpty()
                                    ? "NO_ANSWER"
                                    : "KNOWLEDGE",
                            decisions);

            return new KnowledgeAnswerabilityResult(
                    accepted,
                    diagnostics);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "answerability request interrupted",
                    exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(
                    "answerability request failed",
                    exception);
        }
    }

    @Override
    public String mode() {
        return "QWEN3_8_FLASH_ANSWERABILITY_V1";
    }

    private void addStructuredOutputSchema(ObjectNode body) {
        ObjectNode responseFormat =
                body.putObject("response_format");
        responseFormat.put("type", "json_schema");

        ObjectNode jsonSchema =
                responseFormat.putObject("json_schema");
        jsonSchema.put("name", "knowledge_answerability");
        jsonSchema.put("strict", true);

        ObjectNode schema =
                jsonSchema.putObject("schema");
        schema.put("type", "object");

        ObjectNode propertiesNode =
                schema.putObject("properties");
        ObjectNode decisions =
                propertiesNode.putObject("decisions");
        decisions.put("type", "array");

        ObjectNode item =
                decisions.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties =
                item.putObject("properties");
        itemProperties.putObject("index")
                .put("type", "integer");
        itemProperties.putObject("supported")
                .put("type", "boolean");

        ObjectNode reasonCode =
                itemProperties.putObject("reasonCode");
        reasonCode.put("type", "string");
        ArrayNode reasonEnum =
                reasonCode.putArray("enum");
        reasonEnum.add("DIRECT_ANSWER");
        reasonEnum.add("TOPIC_ONLY");
        reasonEnum.add("MISSING_REQUIRED_SEMANTICS");
        reasonEnum.add("INSUFFICIENT_EVIDENCE");

        itemProperties.putObject("reason")
                .put("type", "string");

        ArrayNode itemRequired =
                item.putArray("required");
        itemRequired.add("index");
        itemRequired.add("supported");
        itemRequired.add("reasonCode");
        itemRequired.add("reason");
        item.put("additionalProperties", false);

        ArrayNode required =
                schema.putArray("required");
        required.add("decisions");
        schema.put("additionalProperties", false);
    }

    private static String buildUserPrompt(
            String query,
            List<KnowledgeSearchHit> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("QUERY:\n")
                .append(query)
                .append("\n\nCANDIDATES:\n");
        for (int i = 0; i < candidates.size(); i++) {
            prompt.append("\n[CANDIDATE ")
                    .append(i)
                    .append("]\n")
                    .append(candidates.get(i).content())
                    .append("\n");
        }
        return prompt.toString();
    }

    private IllegalStateException providerFailure(
            HttpResponse<String> response) {
        String code = null;
        String message = null;
        String requestId = null;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode error = root.path("error");
            code = firstText(error.path("code"), root.path("code"));
            message =
                    firstText(
                            error.path("message"),
                            root.path("message"));
            requestId =
                    firstText(
                            root.path("request_id"),
                            root.path("requestId"),
                            root.path("id"));
        } catch (Exception ignored) {
            // Never echo arbitrary provider bodies or credentials.
        }

        StringBuilder detail =
                new StringBuilder(
                                "answerability provider returned HTTP ")
                        .append(response.statusCode());
        if (hasText(code)) {
            detail.append(", code=")
                    .append(sanitize(code, 120));
        }
        if (hasText(message)) {
            detail.append(", message=")
                    .append(sanitize(message, 500));
        }
        if (hasText(requestId)) {
            detail.append(", requestId=")
                    .append(sanitize(requestId, 160));
        }
        return new IllegalStateException(detail.toString());
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null
                    && node.isTextual()
                    && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String sanitize(
            String value,
            int maxLength) {
        String singleLine =
                value.replaceAll("[\\r\\n\\t]+", " ")
                        .trim();
        return singleLine.length() <= maxLength
                ? singleLine
                : singleLine.substring(0, maxLength) + "...";
    }

    private static String bearer(String apiKey) {
        String trimmed =
                apiKey == null ? "" : apiKey.trim();
        return trimmed.regionMatches(
                        true,
                        0,
                        "Bearer ",
                        0,
                        7)
                ? trimmed
                : "Bearer " + trimmed;
    }

    private record DecisionItem(
            int index,
            boolean supported,
            String reasonCode,
            String reason) {}
}
