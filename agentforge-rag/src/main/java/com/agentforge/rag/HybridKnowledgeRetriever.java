package com.agentforge.rag;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        Map<String, FusedHit> fused = new LinkedHashMap<>();
        addLexicalRanked(fused, lexical);
        addVectorRanked(fused, vector);

        List<FusedHit> ranked =
                fused.values().stream()
                        .sorted(
                                Comparator.comparingDouble(FusedHit::rrfScore)
                                        .reversed()
                                        .thenComparing(hit -> hit.hit().documentId())
                                        .thenComparingInt(hit -> hit.hit().chunkIndex()))
                        .toList();

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
                            .map(HybridKnowledgeRetriever::toSearchHit)
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
                        .map(HybridKnowledgeRetriever::toSearchHit)
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

    private boolean passesRelevanceGate(FusedHit hit) {
        if (!relevanceGateEnabled) {
            return true;
        }
        if (hit.lexicalMatched()) {
            return true;
        }
        return hit.vectorSimilarity() != null
                && hit.vectorSimilarity() >= minVectorSimilarity;
    }

    private void addLexicalRanked(
            Map<String, FusedHit> fused,
            List<KnowledgeBackendHit> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            KnowledgeBackendHit hit = ranked.get(i);
            double contribution = rrfContribution(i);
            fused.compute(
                    hit.chunkId(),
                    (ignored, current) ->
                            current == null
                                    ? new FusedHit(
                                            hit,
                                            contribution,
                                            true,
                                            false,
                                            null)
                                    : new FusedHit(
                                            current.hit(),
                                            current.rrfScore() + contribution,
                                            true,
                                            current.vectorMatched(),
                                            current.vectorSimilarity()));
        }
    }

    private void addVectorRanked(
            Map<String, FusedHit> fused,
            List<KnowledgeBackendHit> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            KnowledgeBackendHit hit = ranked.get(i);
            double contribution = rrfContribution(i);
            double vectorSimilarity =
                    clampCosine(
                            backend.normalizeVectorScore(hit.score()));
            fused.compute(
                    hit.chunkId(),
                    (ignored, current) ->
                            current == null
                                    ? new FusedHit(
                                            hit,
                                            contribution,
                                            false,
                                            true,
                                            vectorSimilarity)
                                    : new FusedHit(
                                            current.hit(),
                                            current.rrfScore() + contribution,
                                            current.lexicalMatched(),
                                            true,
                                            vectorSimilarity));
        }
    }

    private double rrfContribution(int zeroBasedRank) {
        return 1.0d / (rrfK + zeroBasedRank + 1.0d);
    }

    private static double clampCosine(double value) {
        return Math.max(-1d, Math.min(1d, value));
    }

    private static KnowledgeSearchHit toSearchHit(FusedHit fused) {
        KnowledgeBackendHit hit = fused.hit();
        String matchType;
        if (fused.lexicalMatched() && fused.vectorMatched()) {
            matchType = "HYBRID";
        } else if (fused.lexicalMatched()) {
            matchType = "LEXICAL";
        } else {
            matchType = "VECTOR";
        }
        return new KnowledgeSearchHit(
                "KB:" + hit.documentId() + "#" + hit.chunkIndex(),
                hit.documentId(),
                hit.chunkId(),
                hit.chunkIndex(),
                hit.title(),
                hit.sourceType(),
                hit.sourceRef(),
                hit.content(),
                fused.rrfScore(),
                matchType,
                fused.vectorSimilarity(),
                null);
    }

    private record FusedHit(
            KnowledgeBackendHit hit,
            double rrfScore,
            boolean lexicalMatched,
            boolean vectorMatched,
            Double vectorSimilarity) {}
}
