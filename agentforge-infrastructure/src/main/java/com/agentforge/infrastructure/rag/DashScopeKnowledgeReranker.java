package com.agentforge.infrastructure.rag;

import com.agentforge.infrastructure.config.KnowledgeRerankProperties;
import com.agentforge.rag.KnowledgeRerankDiagnostics;
import com.agentforge.rag.KnowledgeRerankResult;
import com.agentforge.rag.KnowledgeReranker;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DashScopeKnowledgeReranker implements KnowledgeReranker {

    static final String NO_ANSWER_SENTINEL =
            "[NO_ANSWER_SENTINEL]\n"
                    + "The available knowledge passages do not contain sufficient information "
                    + "to directly answer the user's specific question.\n"
                    + "Choose this passage over other candidates when the other passages are "
                    + "only topically related but do not actually answer the question.";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeRerankProperties properties;
    private final URI endpoint;

    public DashScopeKnowledgeReranker(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            KnowledgeRerankProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        String baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        this.endpoint =
                URI.create(baseUrl.endsWith("/reranks") ? baseUrl : baseUrl + "/reranks");
    }

    @Override
    public KnowledgeRerankResult rerank(
            String query, List<KnowledgeSearchHit> candidates, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("rerank query must not be blank");
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
        if (candidates == null || candidates.isEmpty()) {
            KnowledgeRerankDiagnostics diagnostics =
                    new KnowledgeRerankDiagnostics(
                            mode(),
                            0,
                            0,
                            "NO_CANDIDATES",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);
            return new KnowledgeRerankResult(
                    List.of(),
                    true,
                    null,
                    diagnostics);
        }

        int realCount = candidates.size();
        int sentinelIndex = realCount;

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", properties.getModel());
            requestBody.put("query", query.trim());
            ArrayNode documents = requestBody.putArray("documents");
            for (KnowledgeSearchHit candidate : candidates) {
                documents.add(rerankDocument(candidate));
            }
            documents.add(NO_ANSWER_SENTINEL);
            requestBody.put("top_n", realCount + 1);
            requestBody.put("instruct", properties.getInstruct());

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
                throw providerFailure(response);
            }

            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (!results.isArray() || results.size() != realCount + 1) {
                throw new IllegalStateException(
                        "rerank provider returned unexpected result count");
            }

            List<RankedItem> ranked = new ArrayList<>(results.size());
            Set<Integer> seen = new HashSet<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index > sentinelIndex || !seen.add(index)) {
                    throw new IllegalStateException("rerank provider returned invalid result index");
                }
                if (!item.has("relevance_score") || !item.path("relevance_score").isNumber()) {
                    throw new IllegalStateException("rerank provider returned missing score");
                }
                ranked.add(new RankedItem(index, item.path("relevance_score").asDouble()));
            }
            ranked.sort(
                    Comparator.comparingDouble(RankedItem::score)
                            .reversed()
                            .thenComparingInt(RankedItem::index));

            Double noAnswerScore = null;
            Integer noAnswerRank = null;
            Double topRealScore = null;
            Integer topRealRank = null;
            String topRealCitationId = null;
            Integer topRealTextChars = null;
            String topRealTextSha256 = null;

            for (int rank = 0; rank < ranked.size(); rank++) {
                RankedItem item = ranked.get(rank);
                int oneBasedRank = rank + 1;
                if (item.index() == sentinelIndex) {
                    noAnswerScore = item.score();
                    noAnswerRank = oneBasedRank;
                    continue;
                }
                if (topRealScore == null) {
                    KnowledgeSearchHit candidate = candidates.get(item.index());
                    String rerankText = rerankDocument(candidate);
                    topRealScore = item.score();
                    topRealRank = oneBasedRank;
                    topRealCitationId = candidate.citationId();
                    topRealTextChars = rerankText.length();
                    topRealTextSha256 = sha256Hex(rerankText);
                }
            }

            if (noAnswerRank == null) {
                throw new IllegalStateException("rerank provider omitted no-answer sentinel");
            }

            List<KnowledgeSearchHit> accepted = new ArrayList<>();
            for (int rank = 0; rank < ranked.size() && rank < noAnswerRank - 1; rank++) {
                RankedItem item = ranked.get(rank);
                if (item.index() == sentinelIndex) {
                    break;
                }
                if (accepted.size() < topK) {
                    accepted.add(
                            candidates.get(item.index()).withRerankScore(item.score()));
                }
            }

            String decision =
                    accepted.isEmpty()
                            ? "NO_ANSWER_SENTINEL"
                            : "KNOWLEDGE";
            KnowledgeRerankDiagnostics diagnostics =
                    new KnowledgeRerankDiagnostics(
                            mode(),
                            realCount,
                            accepted.size(),
                            decision,
                            topRealScore,
                            topRealRank,
                            topRealCitationId,
                            topRealTextChars,
                            topRealTextSha256,
                            noAnswerScore,
                            noAnswerRank);

            return new KnowledgeRerankResult(
                    accepted,
                    accepted.isEmpty(),
                    noAnswerScore,
                    diagnostics);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rerank request interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("rerank request failed", exception);
        }
    }

    @Override
    public String mode() {
        return "QWEN3_RERANK_SENTINEL_V1";
    }

    private static String rerankDocument(KnowledgeSearchHit candidate) {
        return candidate.title() + "\n\n" + candidate.content();
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
            // Never echo an arbitrary provider body or credentials.
        }

        StringBuilder detail =
                new StringBuilder("rerank provider returned HTTP ")
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

    private static String sha256Hex(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to hash rerank candidate", exception);
        }
    }

    private static String bearer(String apiKey) {
        String trimmed = apiKey == null ? "" : apiKey.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7)
                ? trimmed
                : "Bearer " + trimmed;
    }

    private record RankedItem(int index, double score) {}
}
