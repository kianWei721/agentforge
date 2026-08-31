package com.agentforge.rag;

public record KnowledgeIndexReport(
        String indexName,
        String embeddingModel,
        int embeddingDimensions,
        int indexedChunks,
        long durationMs) {}
