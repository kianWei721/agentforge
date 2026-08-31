package com.agentforge.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HybridKnowledgeRetriever implements KnowledgeRetriever {

    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeSearchBackend backend;
    private final int candidateMultiplier;
    private final int rrfK;

    public HybridKnowledgeRetriever(
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            int candidateMultiplier,
            int rrfK) {
        if (candidateMultiplier < 1) {
            throw new IllegalArgumentException("candidateMultiplier must be positive");
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("rrfK must be positive");
        }
        this.embeddingProvider = embeddingProvider;
        this.backend = backend;
        this.candidateMultiplier = candidateMultiplier;
        this.rrfK = rrfK;
    }

    @Override
    public List<KnowledgeSearchHit> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }

        int candidateLimit = Math.min(100, Math.max(20, topK * candidateMultiplier));
        List<float[]> vectors = embeddingProvider.embed(List.of(query.trim()));
        if (vectors.size() != 1) {
            throw new IllegalStateException("query embedding response must contain exactly one vector");
        }

        List<KnowledgeBackendHit> lexical =
                backend.searchLexical(query.trim(), candidateLimit);
        List<KnowledgeBackendHit> vector =
                backend.searchVector(vectors.get(0), candidateLimit);

        Map<String, FusedHit> fused = new LinkedHashMap<>();
        addRanked(fused, lexical);
        addRanked(fused, vector);

        return fused.values().stream()
                .sorted(
                        Comparator.comparingDouble(FusedHit::score)
                                .reversed()
                                .thenComparing(hit -> hit.hit().documentId())
                                .thenComparingInt(hit -> hit.hit().chunkIndex()))
                .limit(topK)
                .map(HybridKnowledgeRetriever::toSearchHit)
                .toList();
    }

    @Override
    public String mode() {
        return "HYBRID_ES_RRF_V1";
    }

    private void addRanked(
            Map<String, FusedHit> fused,
            List<KnowledgeBackendHit> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            KnowledgeBackendHit hit = ranked.get(i);
            double rrfScore = 1.0d / (rrfK + i + 1.0d);
            fused.compute(
                    hit.chunkId(),
                    (ignored, current) ->
                            current == null
                                    ? new FusedHit(hit, rrfScore)
                                    : new FusedHit(current.hit(), current.score() + rrfScore));
        }
    }

    private static KnowledgeSearchHit toSearchHit(FusedHit fused) {
        KnowledgeBackendHit hit = fused.hit();
        return new KnowledgeSearchHit(
                "KB:" + hit.documentId() + "#" + hit.chunkIndex(),
                hit.documentId(),
                hit.chunkId(),
                hit.chunkIndex(),
                hit.title(),
                hit.sourceType(),
                hit.sourceRef(),
                hit.content(),
                fused.score());
    }

    private record FusedHit(KnowledgeBackendHit hit, double score) {}
}
