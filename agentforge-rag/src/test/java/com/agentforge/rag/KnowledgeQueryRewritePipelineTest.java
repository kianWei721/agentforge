package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KnowledgeQueryRewritePipelineTest {

    @Test
    void rewrittenQueryShouldDriveRecallWhileOriginalQuestionDrivesRerankAndJudge() {
        String original = "设备掉线很久了，一般先查什么后查什么？";
        String rewritten =
                "设备长期离线 排查顺序 | prolonged device offline troubleshooting procedure order";

        AtomicReference<String> embedded = new AtomicReference<>();
        AtomicReference<String> lexicalQuery = new AtomicReference<>();
        AtomicReference<String> rerankQuery = new AtomicReference<>();
        AtomicReference<String> judgeQuery = new AtomicReference<>();

        EmbeddingProvider embeddingProvider =
                new EmbeddingProvider() {
                    @Override
                    public List<float[]> embed(List<String> texts) {
                        embedded.set(texts.get(0));
                        return List.of(new float[] {1f, 0f, 0f});
                    }

                    @Override
                    public String model() {
                        return "test";
                    }

                    @Override
                    public int dimensions() {
                        return 3;
                    }
                };

        KnowledgeBackendHit hit =
                new KnowledgeBackendHit(
                        "doc-offline",
                        "chunk-offline",
                        0,
                        "Offline troubleshooting",
                        KnowledgeSourceType.TEST,
                        "test://offline",
                        "Troubleshoot prolonged offline devices in a defined order.",
                        0.8d);

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
                        lexicalQuery.set(query);
                        return List.of(hit);
                    }

                    @Override
                    public List<KnowledgeBackendHit> searchVector(
                            float[] queryVector, int limit, AccessPrincipal principal) {
                        return List.of(hit);
                    }

                    @Override
                    public String indexName() {
                        return "test";
                    }

                    @Override
                    public double normalizeVectorScore(double backendScore) {
                        return backendScore;
                    }
                };

        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter() {
                    @Override
                    public KnowledgeQueryRewriteResult rewrite(String query) {
                        return new KnowledgeQueryRewriteResult(
                                rewritten,
                                new KnowledgeQueryRewriteDiagnostics(
                                        "TEST_REWRITE",
                                        query,
                                        rewritten,
                                        true,
                                        "REWRITTEN",
                                        "CROSS_LANGUAGE_AUGMENTATION"));
                    }

                    @Override
                    public String mode() {
                        return "TEST_REWRITE";
                    }
                };

        KnowledgeReranker reranker =
                (query, candidates, topK) -> {
                    rerankQuery.set(query);
                    return new KnowledgeRerankResult(candidates, false, null);
                };

        KnowledgeAnswerabilityJudge judge =
                (query, candidates) -> {
                    judgeQuery.set(query);
                    KnowledgeSearchHit candidate = candidates.get(0);
                    KnowledgeAnswerabilityDecision decision =
                            new KnowledgeAnswerabilityDecision(
                                    0,
                                    candidate.citationId(),
                                    true,
                                    "DIRECT_ANSWER",
                                    "The passage explicitly provides the requested procedure.");
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
                        embeddingProvider,
                        backend,
                        new KnowledgeAccessGuard(
                                ids ->
                                        ids.stream()
                                                .collect(
                                                        Collectors.toMap(
                                                                Function.identity(),
                                                                ignored ->
                                                                        KnowledgeAccessScope
                                                                                .internal()))),
                        4,
                        60,
                        true,
                        0.60d,
                        rewriter,
                        reranker,
                        8,
                        judge,
                        8);

        KnowledgeRetrievalResult result = retriever.searchDetailed(original, 5, AccessPrincipal.staff("staff-1"));

        assertEquals(rewritten, embedded.get());
        assertEquals(rewritten, lexicalQuery.get());
        assertEquals(original, rerankQuery.get());
        assertEquals(original, judgeQuery.get());
        assertEquals(1, result.hits().size());
        assertEquals("REWRITTEN", result.queryRewriteDiagnostics().decision());
        assertEquals(
                "HYBRID_ES_RRF_REWRITE_RERANK_JUDGE_V1",
                retriever.mode());
    }

    @Test
    void providerFailureShouldFallBackToOriginalQueryWithoutSkippingRetrieval() {
        String original = "设备长期离线应该按什么顺序排查？";
        AtomicReference<String> lexicalQuery = new AtomicReference<>();

        EmbeddingProvider embeddingProvider =
                new EmbeddingProvider() {
                    @Override
                    public List<float[]> embed(List<String> texts) {
                        assertEquals(original, texts.get(0));
                        return List.of(new float[] {1f});
                    }

                    @Override
                    public String model() {
                        return "test";
                    }

                    @Override
                    public int dimensions() {
                        return 1;
                    }
                };

        KnowledgeBackendHit hit =
                new KnowledgeBackendHit(
                        "doc",
                        "chunk",
                        0,
                        "Offline",
                        KnowledgeSourceType.TEST,
                        "test://doc",
                        "Offline troubleshooting.",
                        0.8d);

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
                        lexicalQuery.set(query);
                        return List.of(hit);
                    }

                    @Override
                    public List<KnowledgeBackendHit> searchVector(
                            float[] queryVector, int limit, AccessPrincipal principal) {
                        return List.of(hit);
                    }

                    @Override
                    public String indexName() {
                        return "test";
                    }
                };

        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter() {
                    @Override
                    public KnowledgeQueryRewriteResult rewrite(String query) {
                        throw new IllegalStateException("provider unavailable");
                    }

                    @Override
                    public String mode() {
                        return "TEST_REWRITE";
                    }
                };

        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider,
                        backend,
                        new KnowledgeAccessGuard(
                                ids ->
                                        ids.stream()
                                                .collect(
                                                        Collectors.toMap(
                                                                Function.identity(),
                                                                ignored ->
                                                                        KnowledgeAccessScope
                                                                                .internal()))),
                        4,
                        60,
                        false,
                        0.60d,
                        rewriter,
                        null,
                        8,
                        null,
                        8);

        KnowledgeRetrievalResult result = retriever.searchDetailed(original, 5, AccessPrincipal.staff("staff-1"));

        assertEquals(original, lexicalQuery.get());
        assertEquals(1, result.hits().size());
        assertEquals("FALLBACK_ORIGINAL", result.queryRewriteDiagnostics().decision());
        assertEquals("PROVIDER_ERROR", result.queryRewriteDiagnostics().reasonCode());
    }
}
