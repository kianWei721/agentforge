package com.agentforge.rag;

import com.agentforge.core.security.AccessPrincipal;
import java.util.List;

public interface KnowledgeSearchBackend {

    void recreateIndex(int dimensions);

    void indexBatch(List<KnowledgeIndexedChunk> chunks);

    void refresh();

    /**
     * Implementations must push {@link KnowledgeAccessPolicy} into the backend query as a
     * pre-filter, so {@code limit} counts readable chunks only. Filtering afterwards would let
     * unreadable chunks consume the window and starve the caller of results.
     */
    List<KnowledgeBackendHit> searchLexical(String query, int limit, AccessPrincipal principal);

    /** Same pre-filter contract as {@link #searchLexical}. */
    List<KnowledgeBackendHit> searchVector(
            float[] queryVector, int limit, AccessPrincipal principal);

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
