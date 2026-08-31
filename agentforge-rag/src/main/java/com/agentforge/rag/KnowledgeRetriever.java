package com.agentforge.rag;

import java.util.List;

public interface KnowledgeRetriever {

    List<KnowledgeSearchHit> search(String query, int topK);

    default String mode() {
        return "UNKNOWN";
    }
}
