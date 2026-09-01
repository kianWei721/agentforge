package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class HybridKnowledgeRetrieverTest {

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
                        4,
                        60,
                        true,
                        0.60d);

        List<KnowledgeSearchHit> hits =
                retriever.search(
                        "设备离线怎么排查",
                        3);

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
                        4,
                        60,
                        true,
                        0.60d);

        List<KnowledgeSearchHit> hits =
                retriever.search(
                        "设备长期离线应该按什么顺序排查？",
                        5);

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
                        4,
                        60,
                        true,
                        0.60d);

        assertTrue(
                retriever.search(
                                "shadowStatus CONFLICT 的字段语义是什么？",
                                5)
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
                        4,
                        60,
                        true,
                        0.60d,
                        reranker,
                        8);

        List<KnowledgeSearchHit> hits =
                retriever.search(
                        "设备长期离线应该按什么顺序排查？",
                        5);

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
                        5);

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
                        5);

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
                        4,
                        60,
                        true,
                        0.60d);

        List<KnowledgeSearchHit> hits =
                retriever.search(
                        "shadowStatus CONFLICT",
                        5);

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
                        4,
                        60,
                        false,
                        0.60d);

        assertEquals(
                1,
                retriever.search(
                                "unrelated query",
                                5)
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
                    int limit) {
                return lexical;
            }

            @Override
            public List<KnowledgeBackendHit> searchVector(
                    float[] queryVector,
                    int limit) {
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
