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

    public HybridKnowledgeRetriever(
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            int candidateMultiplier,
            int rrfK,
            boolean relevanceGateEnabled,
            double minVectorSimilarity) {
        if (candidateMultiplier < 1) {
            throw new IllegalArgumentException("candidateMultiplier must be positive");
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("rrfK must be positive");
        }
        if (minVectorSimilarity < -1d || minVectorSimilarity > 1d) {
            throw new IllegalArgumentException("minVectorSimilarity must be between -1 and 1");
        }
        this.embeddingProvider = embeddingProvider;
        this.backend = backend;
        this.candidateMultiplier = candidateMultiplier;
        this.rrfK = rrfK;
        this.relevanceGateEnabled = relevanceGateEnabled;
        this.minVectorSimilarity = minVectorSimilarity;
    }

    @Override
    public List<KnowledgeSearchHit> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }

        String normalizedQuery = query.trim();
        int candidateLimit = Math.min(100, Math.max(20, topK * candidateMultiplier));
        List<float[]> vectors = embeddingProvider.embed(List.of(normalizedQuery));
        if (vectors.size() != 1) {
            throw new IllegalStateException(
                    "query embedding response must contain exactly one vector");
        }

        List<KnowledgeBackendHit> lexical =
                backend.searchLexical(normalizedQuery, candidateLimit);
        List<KnowledgeBackendHit> vector =
                backend.searchVector(vectors.get(0), candidateLimit);

        Map<String, FusedHit> fused = new LinkedHashMap<>();
        addLexicalRanked(fused, lexical);
        addVectorRanked(fused, vector);

        return fused.values().stream()
                .filter(this::passesRelevanceGate)
                .sorted(
                        Comparator.comparingDouble(FusedHit::rrfScore)
                                .reversed()
                                .thenComparing(hit -> hit.hit().documentId())
                                .thenComparingInt(hit -> hit.hit().chunkIndex()))
                .limit(topK)
                .map(HybridKnowledgeRetriever::toSearchHit)
                .toList();
    }

    @Override
    public String mode() {
        return relevanceGateEnabled
                ? "HYBRID_ES_RRF_GATE_V1"
                : "HYBRID_ES_RRF_V1";
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
            double vectorSimilarity = clampCosine(backend.normalizeVectorScore(hit.score()));
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
                fused.vectorSimilarity());
    }

    private record FusedHit(
            KnowledgeBackendHit hit,
            double rrfScore,
            boolean lexicalMatched,
            boolean vectorMatched,
            Double vectorSimilarity) {}
}
