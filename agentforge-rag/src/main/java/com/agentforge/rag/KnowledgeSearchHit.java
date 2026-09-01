package com.agentforge.rag;

public record KnowledgeSearchHit(
        String citationId,
        String documentId,
        String chunkId,
        int chunkIndex,
        String title,
        KnowledgeSourceType sourceType,
        String sourceRef,
        String content,
        double score,
        String matchType,
        Double vectorSimilarity,
        Double rerankScore) {

    public KnowledgeSearchHit withRerankScore(double value) {
        return new KnowledgeSearchHit(
                citationId,
                documentId,
                chunkId,
                chunkIndex,
                title,
                sourceType,
                sourceRef,
                content,
                score,
                matchType,
                vectorSimilarity,
                value);
    }
}
