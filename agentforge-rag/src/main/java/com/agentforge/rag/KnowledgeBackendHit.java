package com.agentforge.rag;

public record KnowledgeBackendHit(
        String documentId,
        String chunkId,
        int chunkIndex,
        String title,
        KnowledgeSourceType sourceType,
        String sourceRef,
        String content,
        double score) {}
