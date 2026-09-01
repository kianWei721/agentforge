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
        Double vectorSimilarity) {}
