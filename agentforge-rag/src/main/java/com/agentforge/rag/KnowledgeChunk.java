package com.agentforge.rag;

public record KnowledgeChunk(
        String id,
        String documentId,
        int chunkIndex,
        String content,
        int charCount) {}
