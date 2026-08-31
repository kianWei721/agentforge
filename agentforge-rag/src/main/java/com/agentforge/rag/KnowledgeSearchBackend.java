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
}
