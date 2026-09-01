package com.agentforge.rag;

import java.util.List;

public interface KnowledgeRetriever {

    List<KnowledgeSearchHit> search(String query, int topK);

    default KnowledgeRetrievalResult searchDetailed(String query, int topK) {
        return KnowledgeRetrievalResult.of(search(query, topK));
    }

    default String mode() {
        return "UNKNOWN";
    }
}
