package com.agentforge.rag;

public record KnowledgeCandidate(
        String documentId,
        String chunkId,
        int chunkIndex,
        String title,
        KnowledgeSourceType sourceType,
        String sourceRef,
        String content) {}
