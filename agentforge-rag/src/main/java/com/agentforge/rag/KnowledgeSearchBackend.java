package com.agentforge.rag;

import java.util.List;

public interface KnowledgeSearchBackend {

    void recreateIndex(int dimensions);

    void indexBatch(List<KnowledgeIndexedChunk> chunks);

    void refresh();

    List<KnowledgeBackendHit> searchLexical(String query, int limit);

    List<KnowledgeBackendHit> searchVector(float[] queryVector, int limit);

    String indexName();

    default String vectorSearchMode() {
        return "UNKNOWN";
    }

    /**
     * Convert a backend-specific vector search score into cosine similarity in [-1, 1].
     *
     * <p>The relevance gate must not compare raw Elasticsearch scores across different
     * vector-search implementations. Implementations should normalize their own score
     * semantics here.
     */
    default double normalizeVectorScore(double backendScore) {
        return backendScore;
    }
}
