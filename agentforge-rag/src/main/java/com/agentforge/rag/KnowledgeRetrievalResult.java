package com.agentforge.rag;

import java.util.List;

public record KnowledgeRetrievalResult(
        List<KnowledgeSearchHit> hits,
        KnowledgeRerankDiagnostics rerankDiagnostics) {

    public KnowledgeRetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static KnowledgeRetrievalResult of(List<KnowledgeSearchHit> hits) {
        return new KnowledgeRetrievalResult(hits, null);
    }
}
