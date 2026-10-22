package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.core.security.AccessPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HybridKnowledgeRetrieverTest {

    private static final AccessPrincipal STAFF = AccessPrincipal.staff("staff-1");
    private static final KnowledgeAccessGuard ALL_READABLE =
            new KnowledgeAccessGuard(
                    ids ->
                            ids.stream()
                                    .distinct()
                                    .collect(
                                            Collectors.toMap(
                                                    Function.identity(),
                                                    ignored -> KnowledgeAccessScope.internal())));

    @Test
    void guardShouldDropUnreadableAndStaleHitsBeforeRerank() {
        // Simulates an index whose filter is wrong or stale: it returns other customers', internal
        // and already-deleted chunks to customer A.
        KnowledgeBackendHit own = hit("doc-a", "chunk-a", "A 的申报说明", 5d);
        KnowledgeBackendHit published = hit("doc-public", "chunk-public", "政策解读", 4d);
        KnowledgeBackendHit otherCustomer = hit("doc-b", "chunk-b", "B 的申报说明", 6d);
        KnowledgeBackendHit internal = hit("doc-internal", "chunk-internal", "内部 SOP", 7d);
        KnowledgeBackendHit deleted = hit("doc-deleted", "chunk-deleted", "已删除", 8d);
        Map<String, KnowledgeAccessScope> table =
                Map.of(
                        "doc-a", KnowledgeAccessScope.customer("cust-a"),
                        "doc-public", KnowledgeAccessScope.publicAudience(),
                        "doc-b", KnowledgeAccessScope.customer("cust-b"),
                        "doc-internal", KnowledgeAccessScope.internal());
        KnowledgeAccessGuard guard =
                new KnowledgeAccessGuard(
                        ids ->
                                ids.stream()
                                        .filter(table::containsKey)
                                        .collect(Collectors.toMap(Function.identity(), table::get)));

        List<String> rerankInput = new ArrayList<>();
        KnowledgeReranker reranker =
                (query, candidates, topK) -> {
                    candidates.forEach(candidate -> rerankInput.add(candidate.documentId()));
                    return new KnowledgeRerankResult(candidates, false, null);
                };

        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(otherCustomer, internal, deleted, own),
                                List.of(published, otherCustomer)),
                        guard,
                        4,
                        60,
                        true,
                        0.60d,
                        reranker,
                        8);

        KnowledgeRetrievalResult result =
                retriever.searchDetailed(
                        "申报说明",
                        5,
                        AccessPrincipal.customer("user-a", "cust-a"));

        assertEquals(
                List.of("doc-a", "doc-public"),
                result.hits().stream().map(KnowledgeSearchHit::documentId).sorted().toList());
        assertEquals(
                List.of("doc-a", "doc-public"),
                rerankInput.stream().sorted().toList());
        assertEquals("CUSTOMER", result.accessDiagnostics().principalType());
        assertEquals(4, result.accessDiagnostics().guardDroppedHits());
    }

    @Test
    void shouldPushPrincipalDownToBothChannels() {
        List<AccessPrincipal> seen = new ArrayList<>();
        KnowledgeSearchBackend backend =
                new KnowledgeSearchBackend() {
                    @Override
                    public void recreateIndex(int dimensions) {}

                    @Override
                    public void indexBatch(List<KnowledgeIndexedChunk> chunks) {}

                    @Override
                    public void refresh() {}

                    @Override
                    public List<KnowledgeBackendHit> searchLexical(
                            String query, int limit, AccessPrincipal principal) {
                        seen.add(principal);
                        return List.of();
                    }

                    @Override
                    public List<KnowledgeBackendHit> searchVector(
                            float[] queryVector, int limit, AccessPrincipal principal) {
                        seen.add(principal);
                        return List.of();
                    }

                    @Override
                    public String indexName() {
                        return "test";
                    }
                };
        AccessPrincipal customer = AccessPrincipal.customer("user-a", "cust-a");

        new HybridKnowledgeRetriever(embeddingProvider(), backend, ALL_READABLE, 4, 60, true, 0.6d)
                .search("申报", 5, customer);

        assertEquals(List.of(customer, customer), seen);
    }

    @Test
    void shouldRefuseToSearchWithoutPrincipal() {
        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(List.of(), List.of()),
                        ALL_READABLE,
                        4,
                        60,
                        true,
                        0.60d);

        assertThrows(IllegalArgumentException.class, () -> retriever.search("申报", 5, null));
    }

    @Test
    void shouldFuseLexicalAndVectorRanksWithStableCitation() {
        KnowledgeBackendHit shared =
                hit(
                        "doc-shared",
                        "chunk-shared",
                        "Offline troubleshooting",
                        10d);
        KnowledgeBackendHit lexicalOnly =
                hit(
                        "doc-lex",
                        "chunk-lex",
                        "Exact wording",
                        20d);
        KnowledgeBackendHit vectorOnly =
                hit(
                        "doc-vector",
                        "chunk-vector",
                        "Semantic match",
                        0.80d);

        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(shared, lexicalOnly),
                                List.of(shared, vectorOnly)),
                        ALL_READABLE,
                        4,
                        60,
                        true,
                        0.60d);

        List<KnowledgeSearchHit> hits =
                retriever.search(
                        "设备离线怎么排查",
                        3,
                        STAFF);

        assertEquals(3, hits.size());
        assertEquals(
                "doc-shared",
                hits.get(0).documentId());
        assertEquals(
                "KB:doc-shared#0",
                hits.get(0).citationId());
        assertEquals(
                "HYBRID",
                hits.get(0).matchType());
        assertEquals(
                0.80d,
                hits.get(2).vectorSimilarity(),
                0.0001d);
        assertEquals(
                "HYBRID_ES_RRF_GATE_V1",
                retriever.mode());
    }

    @Test
    void shouldKeepStrongVectorOnlyCrossLanguageHit() {
        KnowledgeBackendHit vectorOnly =
                hit(
                        "doc-offline",
                        "chunk-offline",
                        "Offline troubleshooting",
                        0.72d);
        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(),
                                List.of(vectorOnly)),
                        ALL_READABLE,
                        4,
                        60,
                        true,
                        0.60d);

        List<KnowledgeSearchHit> hits =
                retriever.search(
                        "设备长期离线应该按什么顺序排查？",
                        5,
                        STAFF);

        assertEquals(1, hits.size());
        assertEquals(
                "doc-offline",
                hits.get(0).documentId());
        assertEquals(
                "VECTOR",
                hits.get(0).matchType());
        assertEquals(
                0.72d,
                hits.get(0).vectorSimilarity(),
                0.0001d);
    }

    @Test
    void shouldReturnNoAnswerForWeakVectorOnlyCandidateWithLegacyGate() {
        KnowledgeBackendHit unrelated =
                hit(
                        "doc-offline",
                        "chunk-offline",
                        "Offline troubleshooting",
                        0.41d);
        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(),
                                List.of(unrelated)),
                        ALL_READABLE,
                        4,
                        60,
                        true,
                        0.60d);

        assertTrue(
                retriever.search(
                                "shadowStatus CONFLICT 的字段语义是什么？",
                                5,
                                STAFF)
                        .isEmpty());
    }

    @Test
    void rerankerShouldReceiveCandidatesBeforeLegacySimilarityGate() {
        KnowledgeBackendHit crossLanguage =
                hit(
                        "doc-offline",
                        "chunk-offline",
                        "Offline troubleshooting",
                        0.48d);
        KnowledgeReranker reranker =
                new KnowledgeReranker() {
                    @Override
                    public KnowledgeRerankResult rerank(
                            String query,
                            List<KnowledgeSearchHit> candidates,
                            int topK) {
                        assertEquals(1, candidates.size());
                        assertEquals(
                                0.48d,
                                candidates.get(0).vectorSimilarity(),
                                0.0001d);
                        return new KnowledgeRerankResult(
                                List.of(
                                        candidates.get(0)
                                                .withRerankScore(0.92d)),
                                false,
                                null);
                    }

                    @Override
                    public String mode() {
                        return "TEST_RERANK";
                    }
                };

        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(),
                                List.of(crossLanguage)),
                        ALL_READABLE,
                        4,
                        60,
                        true,
                        0.60d,
                        reranker,
                        8);

        List<KnowledgeSearchHit> hits =
                retriever.search(
                        "设备长期离线应该按什么顺序排查？",
                        5,
                        STAFF);

        assertEquals(1, hits.size());
        assertEquals(
                0.92d,
                hits.get(0).rerankScore(),
                0.0001d);
        assertEquals(
                "HYBRID_ES_RRF_RERANK_V1",
                retriever.mode());
    }

    @Test
    void gatewayRewriteFailureShouldFallBackWithItsCodeAndShareOneCorrelationId() {
        List<String> correlationIds = new ArrayList<>();
        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter() {
                    @Override
                    public KnowledgeQueryRewriteResult rewrite(String query) {
                        throw new AssertionError("the retriever must pass its call context");
                    }

                    @Override
                    public KnowledgeQueryRewriteResult rewrite(
                            String query, KnowledgeModelCallContext context) {
                        correlationIds.add(context.correlationId());
                        context.record(
                                new KnowledgeModelCall(
                                        "QUERY_REWRITE", "gw-1", null, null, "RATE_LIMITED"));
                        throw new ModelCallFailedException(
                                "RATE_LIMITED", true, "gw-1", "rate limited");
                    }
                };
        KnowledgeAnswerabilityJudge judge =
                new KnowledgeAnswerabilityJudge() {
                    @Override
                    public KnowledgeAnswerabilityResult judge(
                            String query, List<KnowledgeSearchHit> candidates) {
                        throw new AssertionError("the retriever must pass its call context");
                    }

                    @Override
                    public KnowledgeAnswerabilityResult judge(
                            String query,
                            List<KnowledgeSearchHit> candidates,
                            KnowledgeModelCallContext context) {
                        correlationIds.add(context.correlationId());
                        context.record(
                                new KnowledgeModelCall(
                                        "ANSWERABILITY", "gw-2", "DASHSCOPE", "qwen-plus", null));
                        return new KnowledgeAnswerabilityResult(
                                List.of(candidates.get(0)),
                                new KnowledgeAnswerabilityDiagnostics(
                                        "TEST_JUDGE", 1, 1, "KNOWLEDGE", List.of()));
                    }
                };

        KnowledgeRetrievalResult result =
                new HybridKnowledgeRetriever(
                                embeddingProvider(),
                                backend(
                                        List.of(),
                                        List.of(hit("doc-offline", "chunk-offline", "Offline", 0.9d))),
                                ALL_READABLE,
                                4,
                                60,
                                true,
                                0.60d,
                                rewriter,
                                null,
                                8,
                                judge,
                                8)
                        .searchDetailed("设备长期离线应该按什么顺序排查？", 5, STAFF);

        assertEquals(1, result.hits().size());
        assertEquals("FALLBACK_ORIGINAL", result.queryRewriteDiagnostics().decision());
        assertEquals("RATE_LIMITED", result.queryRewriteDiagnostics().reasonCode());

        assertEquals(2, correlationIds.size());
        assertEquals(correlationIds.get(0), correlationIds.get(1));
        KnowledgeModelCallDiagnostics calls = result.modelCallDiagnostics();
        assertEquals(correlationIds.get(0), calls.correlationId());
        assertEquals(
                List.of("QUERY_REWRITE", "ANSWERABILITY"),
                calls.calls().stream().map(KnowledgeModelCall::stage).toList());
        assertEquals("qwen-plus", calls.calls().get(1).servedModel());
    }

    @Test
    void answerabilityJudgeShouldAcceptPositiveBelowLegacyCosineThreshold() {
        KnowledgeBackendHit crossLanguage =
                hit(
                        "doc-offline",
                        "chunk-offline",
                        "Offline troubleshooting",
                        0.48d);

        KnowledgeReranker reranker =
                (query, candidates, topK) ->
                        new KnowledgeRerankResult(
                                List.of(
                                        candidates.get(0)
                                                .withRerankScore(0.91d)),
                                false,
                                null);

        KnowledgeAnswerabilityJudge judge =
                (query, candidates) -> {
                    KnowledgeSearchHit candidate =
                            candidates.get(0);
                    KnowledgeAnswerabilityDecision decision =
                            new KnowledgeAnswerabilityDecision(
                                    0,
                                    candidate.citationId(),
                                    true,
                                    "DIRECT_ANSWER",
                                    "The passage explicitly contains the procedure.");
                    KnowledgeAnswerabilityDiagnostics diagnostics =
                            new KnowledgeAnswerabilityDiagnostics(
                                    "TEST_JUDGE",
                                    1,
                                    1,
                                    "KNOWLEDGE",
                                    List.of(decision));
                    return new KnowledgeAnswerabilityResult(
                            List.of(candidate),
                            diagnostics);
                };

        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(),
                                List.of(crossLanguage)),
                        ALL_READABLE,
                        4,
                        60,
                        true,
                        0.60d,
                        reranker,
                        8,
                        judge,
                        8);

        KnowledgeRetrievalResult result =
                retriever.searchDetailed(
                        "设备长期离线应该按什么顺序排查？",
                        5,
                        STAFF);

        assertEquals(1, result.hits().size());
        assertEquals(
                "doc-offline",
                result.hits().get(0).documentId());
        assertEquals(
                "KNOWLEDGE",
                result.answerabilityDiagnostics().decision());
        assertEquals(
                "HYBRID_ES_RRF_RERANK_JUDGE_V1",
                retriever.mode());
    }

    @Test
    void answerabilityJudgeShouldRejectTopicallyRelatedCandidate() {
        KnowledgeBackendHit unrelated =
                hit(
                        "doc-offline",
                        "chunk-offline",
                        "Offline troubleshooting",
                        0.56d);

        KnowledgeReranker reranker =
                (query, candidates, topK) ->
                        new KnowledgeRerankResult(
                                List.of(
                                        candidates.get(0)
                                                .withRerankScore(0.45d)),
                                false,
                                null);

        KnowledgeAnswerabilityJudge judge =
                (query, candidates) -> {
                    KnowledgeSearchHit candidate =
                            candidates.get(0);
                    KnowledgeAnswerabilityDecision decision =
                            new KnowledgeAnswerabilityDecision(
                                    0,
                                    candidate.citationId(),
                                    false,
                                    "MISSING_REQUIRED_SEMANTICS",
                                    "The passage does not define SignalStrength.");
                    KnowledgeAnswerabilityDiagnostics diagnostics =
                            new KnowledgeAnswerabilityDiagnostics(
                                    "TEST_JUDGE",
                                    1,
                                    0,
                                    "NO_ANSWER",
                                    List.of(decision));
                    return new KnowledgeAnswerabilityResult(
                            List.of(),
                            diagnostics);
                };

        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(),
                                List.of(unrelated)),
                        ALL_READABLE,
                        4,
                        60,
                        true,
                        0.60d,
                        reranker,
                        8,
                        judge,
                        8);

        KnowledgeRetrievalResult result =
                retriever.searchDetailed(
                        "SignalStrength=0 在这个设备里具体代表什么？",
                        5,
                        STAFF);

        assertTrue(result.hits().isEmpty());
        assertEquals(
                "NO_ANSWER",
                result.answerabilityDiagnostics().decision());
    }

    @Test
    void shouldKeepLexicalCandidateEvenWhenVectorSimilarityIsBelowGate() {
        KnowledgeBackendHit exact =
                hit(
                        "doc-exact",
                        "chunk-exact",
                        "shadowStatus CONFLICT",
                        4.2d);
        KnowledgeBackendHit sameVector =
                hit(
                        "doc-exact",
                        "chunk-exact",
                        "shadowStatus CONFLICT",
                        0.30d);
        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(exact),
                                List.of(sameVector)),
                        ALL_READABLE,
                        4,
                        60,
                        true,
                        0.60d);

        List<KnowledgeSearchHit> hits =
                retriever.search(
                        "shadowStatus CONFLICT",
                        5,
                        STAFF);

        assertEquals(1, hits.size());
        assertEquals(
                "HYBRID",
                hits.get(0).matchType());
        assertEquals(
                0.30d,
                hits.get(0).vectorSimilarity(),
                0.0001d);
    }

    @Test
    void shouldPreserveV062BehaviorWhenGateDisabled() {
        KnowledgeBackendHit unrelated =
                hit(
                        "doc-any",
                        "chunk-any",
                        "Any candidate",
                        0.10d);
        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider(),
                        backend(
                                List.of(),
                                List.of(unrelated)),
                        ALL_READABLE,
                        4,
                        60,
                        false,
                        0.60d);

        assertEquals(
                1,
                retriever.search(
                                "unrelated query",
                                5,
                                STAFF)
                        .size());
        assertEquals(
                "HYBRID_ES_RRF_V1",
                retriever.mode());
    }

    private static EmbeddingProvider embeddingProvider() {
        return new EmbeddingProvider() {
            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream()
                        .map(
                                ignored ->
                                        new float[] {
                                            1f, 0f, 0f
                                        })
                        .toList();
            }

            @Override
            public String model() {
                return "test-embedding";
            }

            @Override
            public int dimensions() {
                return 3;
            }
        };
    }

    private static KnowledgeSearchBackend backend(
            List<KnowledgeBackendHit> lexical,
            List<KnowledgeBackendHit> vector) {
        return new KnowledgeSearchBackend() {
            @Override
            public void recreateIndex(int dimensions) {}

            @Override
            public void indexBatch(
                    List<KnowledgeIndexedChunk> chunks) {}

            @Override
            public void refresh() {}

            @Override
            public List<KnowledgeBackendHit> searchLexical(
                    String query,
                    int limit,
                    AccessPrincipal principal) {
                return lexical;
            }

            @Override
            public List<KnowledgeBackendHit> searchVector(
                    float[] queryVector,
                    int limit,
                    AccessPrincipal principal) {
                return vector;
            }

            @Override
            public String indexName() {
                return "test";
            }

            @Override
            public double normalizeVectorScore(
                    double backendScore) {
                return backendScore;
            }
        };
    }

    private static KnowledgeBackendHit hit(
            String documentId,
            String chunkId,
            String title,
            double score) {
        return new KnowledgeBackendHit(
                documentId,
                chunkId,
                0,
                title,
                KnowledgeSourceType.TEST,
                "test://" + documentId,
                title + " content.",
                score);
    }
}
