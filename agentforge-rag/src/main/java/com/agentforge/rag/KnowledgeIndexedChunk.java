package com.agentforge.rag;

public record KnowledgeIndexedChunk(
        KnowledgeCandidate candidate,
        float[] embedding) {

    public KnowledgeIndexedChunk {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding is required");
        }
    }
}
