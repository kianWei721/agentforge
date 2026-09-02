package com.agentforge.rag;

import java.util.List;

public class HybridKnowledgeRetriever implements KnowledgeRetriever {

    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeSearchBackend backend;
    private final int candidateMultiplier;
    private final int rrfK;
    private final boolean relevanceGateEnabled;
    private final double minVectorSimilarity;
    private final KnowledgeQueryRewriter queryRewriter;
    private final KnowledgeReranker reranker;
    private final int rerankCandidateLimit;
    private final KnowledgeAnswerabilityJudge answerabilityJudge;
    private final int answerabilityCandidateLimit;

    public HybridKnowledgeRetriever(
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            int candidateMultiplier,
            int rrfK,
            boolean relevanceGateEnabled,
            double minVectorSimilarity) {
        this(
                embeddingProvider,
                backend,
                candidateMultiplier,
                rrfK,
                relevanceGateEnabled,
                minVectorSimilarity,
                null,
                null,
                8,
                null,
                8);
    }

    public HybridKnowledgeRetriever(
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            int candidateMultiplier,
            int rrfK,
            boolean relevanceGateEnabled,
            double minVectorSimilarity,
            KnowledgeReranker reranker,
            int rerankCandidateLimit) {
        this(
                embeddingProvider,
                backend,
                candidateMultiplier,
                rrfK,
                relevanceGateEnabled,
                minVectorSimilarity,
                null,
                reranker,
                rerankCandidateLimit,
                null,
                8);
    }

    public HybridKnowledgeRetriever(
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            int candidateMultiplier,
            int rrfK,
            boolean relevanceGateEnabled,
            double minVectorSimilarity,
            KnowledgeReranker reranker,
            int rerankCandidateLimit,
            KnowledgeAnswerabilityJudge answerabilityJudge,
            int answerabilityCandidateLimit) {
        this(
                embeddingProvider,
                backend,
                candidateMultiplier,
                rrfK,
                relevanceGateEnabled,
                minVectorSimilarity,
                null,
                reranker,
                rerankCandidateLimit,
                answerabilityJudge,
                answerabilityCandidateLimit);
    }

    public HybridKnowledgeRetriever(
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            int candidateMultiplier,
            int rrfK,
            boolean relevanceGateEnabled,
            double minVectorSimilarity,
            KnowledgeQueryRewriter queryRewriter,
            KnowledgeReranker reranker,
            int rerankCandidateLimit,
            KnowledgeAnswerabilityJudge answerabilityJudge,
            int answerabilityCandidateLimit) {
        if (candidateMultiplier < 1) {
            throw new IllegalArgumentException("candidateMultiplier must be positive");
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("rrfK must be positive");
        }
        if (minVectorSimilarity < -1d || minVectorSimilarity > 1d) {
            throw new IllegalArgumentException("minVectorSimilarity must be between -1 and 1");
        }
        if (rerankCandidateLimit < 1 || rerankCandidateLimit > 100) {
            throw new IllegalArgumentException("rerankCandidateLimit must be between 1 and 100");
        }
        if (answerabilityCandidateLimit < 1 || answerabilityCandidateLimit > 20) {
            throw new IllegalArgumentException(
                    "answerabilityCandidateLimit must be between 1 and 20");
        }
        this.embeddingProvider = embeddingProvider;
        this.backend = backend;
        this.candidateMultiplier = candidateMultiplier;
        this.rrfK = rrfK;
        this.relevanceGateEnabled = relevanceGateEnabled;
        this.minVectorSimilarity = minVectorSimilarity;
        this.queryRewriter = queryRewriter;
        this.reranker = reranker;
        this.rerankCandidateLimit = rerankCandidateLimit;
        this.answerabilityJudge = answerabilityJudge;
        this.answerabilityCandidateLimit = answerabilityCandidateLimit;
    }

    @Override
    public List<KnowledgeSearchHit> search(String query, int topK) {
        return searchDetailed(query, topK).hits();
    }

    @Override
    public KnowledgeRetrievalResult searchDetailed(String query, int topK) {
        if (query == null || query.isBlank()) {
            return KnowledgeRetrievalResult.of(List.of());
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }

        String originalQuery = query.trim();
        KnowledgeQueryRewriteResult rewrite = rewriteQuery(originalQuery);
        String retrievalQuery = rewrite.retrievalQuery();

        int candidateLimit = Math.min(100, Math.max(20, topK * candidateMultiplier));
        List<float[]> vectors = embeddingProvider.embed(List.of(retrievalQuery));
        if (vectors.size() != 1) {
            throw new IllegalStateException(
                    "query embedding response must contain exactly one vector");
        }

        List<KnowledgeBackendHit> lexical =
                backend.searchLexical(retrievalQuery, candidateLimit);
        List<KnowledgeBackendHit> vector =
                backend.searchVector(vectors.get(0), candidateLimit);

        List<KnowledgeSearchHit> ranked =
                RrfFusion.fuse(
                        lexical,
                        vector,
                        rrfK,
                        backend::normalizeVectorScore);

        if (reranker != null || answerabilityJudge != null) {
            int downstreamLimit =
                    answerabilityJudge == null
                            ? topK
                            : Math.max(topK, answerabilityCandidateLimit);
            int poolLimit =
                    reranker == null
                            ? downstreamLimit
                            : Math.max(downstreamLimit, rerankCandidateLimit);
            int poolSize = Math.min(ranked.size(), poolLimit);

            List<KnowledgeSearchHit> ordered =
                    ranked.stream()
                            .limit(poolSize)
                            .toList();

            KnowledgeRerankDiagnostics rerankDiagnostics = null;
            if (reranker != null && !ordered.isEmpty()) {
                int rerankOutputLimit = Math.min(ordered.size(), downstreamLimit);
                KnowledgeRerankResult rerankResult =
                        reranker.rerank(
                                originalQuery,
                                ordered,
                                rerankOutputLimit);
                ordered = rerankResult.hits();
                rerankDiagnostics = rerankResult.diagnostics();
            }

            if (answerabilityJudge != null && !ordered.isEmpty()) {
                List<KnowledgeSearchHit> judgeCandidates =
                        ordered.stream()
                                .limit(answerabilityCandidateLimit)
                                .toList();
                KnowledgeAnswerabilityResult answerability =
                        answerabilityJudge.judge(
                                originalQuery,
                                judgeCandidates);
                List<KnowledgeSearchHit> hits =
                        answerability.hits().stream()
                                .limit(topK)
                                .toList();
                return new KnowledgeRetrievalResult(
                        hits,
                        rerankDiagnostics,
                        answerability.diagnostics(),
                        rewrite.diagnostics());
            }

            List<KnowledgeSearchHit> hits =
                    ordered.stream().limit(topK).toList();
            return new KnowledgeRetrievalResult(
                    hits,
                    rerankDiagnostics,
                    null,
                    rewrite.diagnostics());
        }

        List<KnowledgeSearchHit> hits =
                ranked.stream()
                        .filter(this::passesRelevanceGate)
                        .limit(topK)
                        .toList();
        return new KnowledgeRetrievalResult(
                hits,
                null,
                null,
                rewrite.diagnostics());
    }

    @Override
    public String mode() {
        if (queryRewriter != null) {
            if (reranker != null && answerabilityJudge != null) {
                return "HYBRID_ES_RRF_REWRITE_RERANK_JUDGE_V1";
            }
            if (answerabilityJudge != null) {
                return "HYBRID_ES_RRF_REWRITE_JUDGE_V1";
            }
            if (reranker != null) {
                return "HYBRID_ES_RRF_REWRITE_RERANK_V1";
            }
            return relevanceGateEnabled
                    ? "HYBRID_ES_RRF_REWRITE_GATE_V1"
                    : "HYBRID_ES_RRF_REWRITE_V1";
        }
        if (reranker != null && answerabilityJudge != null) {
            return "HYBRID_ES_RRF_RERANK_JUDGE_V1";
        }
        if (answerabilityJudge != null) {
            return "HYBRID_ES_RRF_JUDGE_V1";
        }
        if (reranker != null) {
            return "HYBRID_ES_RRF_RERANK_V1";
        }
        return relevanceGateEnabled
                ? "HYBRID_ES_RRF_GATE_V1"
                : "HYBRID_ES_RRF_V1";
    }

    private KnowledgeQueryRewriteResult rewriteQuery(String originalQuery) {
        if (queryRewriter == null) {
            return KnowledgeQueryRewriteResult.original(
                    originalQuery,
                    "DISABLED",
                    "UNCHANGED",
                    "UNCHANGED");
        }
        try {
            KnowledgeQueryRewriteResult result = queryRewriter.rewrite(originalQuery);
            if (result == null
                    || result.retrievalQuery() == null
                    || result.retrievalQuery().isBlank()) {
                throw new IllegalStateException("query rewriter returned invalid result");
            }
            return result;
        } catch (RuntimeException exception) {
            return KnowledgeQueryRewriteResult.original(
                    originalQuery,
                    queryRewriter.mode(),
                    "FALLBACK_ORIGINAL",
                    "PROVIDER_ERROR");
        }
    }

    private boolean passesRelevanceGate(KnowledgeSearchHit hit) {
        if (!relevanceGateEnabled) {
            return true;
        }
        if (!"VECTOR".equals(hit.matchType())) {
            return true;
        }
        return hit.vectorSimilarity() != null
                && hit.vectorSimilarity() >= minVectorSimilarity;
    }
}
