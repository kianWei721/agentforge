package com.agentforge.rag;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Shared rank-only fusion used by production Hybrid retrieval and evaluation.
 *
 * <p>RRF intentionally ignores backend score magnitudes. BM25 and vector channels
 * contribute only by rank, while vector similarity is retained separately for
 * observability/relevance-gate semantics.
 */
public final class RrfFusion {

    private RrfFusion() {}

    public static List<KnowledgeSearchHit> fuse(
            List<KnowledgeBackendHit> lexical,
            List<KnowledgeBackendHit> vector,
            int rrfK,
            DoubleUnaryOperator normalizeVectorScore) {
        if (rrfK < 1) {
            throw new IllegalArgumentException("rrfK must be positive");
        }
        if (normalizeVectorScore == null) {
            throw new IllegalArgumentException("normalizeVectorScore is required");
        }

        Map<String, FusedHit> fused = new LinkedHashMap<>();
        addLexicalRanked(fused, lexical == null ? List.of() : lexical, rrfK);
        addVectorRanked(
                fused,
                vector == null ? List.of() : vector,
                rrfK,
                normalizeVectorScore);

        return fused.values().stream()
                .sorted(
                        Comparator.comparingDouble(FusedHit::rrfScore)
                                .reversed()
                                .thenComparing(hit -> hit.hit().documentId())
                                .thenComparingInt(hit -> hit.hit().chunkIndex()))
                .map(RrfFusion::toSearchHit)
                .toList();
    }

    private static void addLexicalRanked(
            Map<String, FusedHit> fused,
            List<KnowledgeBackendHit> ranked,
            int rrfK) {
        for (int i = 0; i < ranked.size(); i++) {
            KnowledgeBackendHit hit = ranked.get(i);
            double contribution = contribution(rrfK, i);
            fused.compute(
                    hit.chunkId(),
                    (ignored, current) ->
                            current == null
                                    ? new FusedHit(hit, contribution, true, false, null)
                                    : new FusedHit(
                                            current.hit(),
                                            current.rrfScore() + contribution,
                                            true,
                                            current.vectorMatched(),
                                            current.vectorSimilarity()));
        }
    }

    private static void addVectorRanked(
            Map<String, FusedHit> fused,
            List<KnowledgeBackendHit> ranked,
            int rrfK,
            DoubleUnaryOperator normalizeVectorScore) {
        for (int i = 0; i < ranked.size(); i++) {
            KnowledgeBackendHit hit = ranked.get(i);
            double contribution = contribution(rrfK, i);
            double similarity = clampCosine(normalizeVectorScore.applyAsDouble(hit.score()));
            fused.compute(
                    hit.chunkId(),
                    (ignored, current) ->
                            current == null
                                    ? new FusedHit(hit, contribution, false, true, similarity)
                                    : new FusedHit(
                                            current.hit(),
                                            current.rrfScore() + contribution,
                                            current.lexicalMatched(),
                                            true,
                                            similarity));
        }
    }

    private static double contribution(int rrfK, int zeroBasedRank) {
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
