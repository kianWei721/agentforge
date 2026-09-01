package com.agentforge.rag;

import java.util.List;

public interface KnowledgeReranker {

    KnowledgeRerankResult rerank(
            String query, List<KnowledgeSearchHit> candidates, int topK);

    default String mode() {
        return "UNKNOWN";
    }
}
