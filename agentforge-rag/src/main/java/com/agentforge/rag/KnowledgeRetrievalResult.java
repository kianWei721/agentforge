package com.agentforge.rag;

import java.util.List;

public record KnowledgeRetrievalResult(
        List<KnowledgeSearchHit> hits,
        KnowledgeRerankDiagnostics rerankDiagnostics,
        KnowledgeAnswerabilityDiagnostics answerabilityDiagnostics,
        KnowledgeQueryRewriteDiagnostics queryRewriteDiagnostics) {

    public KnowledgeRetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public KnowledgeRetrievalResult(
            List<KnowledgeSearchHit> hits,
            KnowledgeRerankDiagnostics rerankDiagnostics,
            KnowledgeAnswerabilityDiagnostics answerabilityDiagnostics) {
        this(hits, rerankDiagnostics, answerabilityDiagnostics, null);
    }

    public KnowledgeRetrievalResult(
            List<KnowledgeSearchHit> hits,
            KnowledgeRerankDiagnostics rerankDiagnostics) {
        this(hits, rerankDiagnostics, null, null);
    }

    public static KnowledgeRetrievalResult of(List<KnowledgeSearchHit> hits) {
        return new KnowledgeRetrievalResult(hits, null, null, null);
    }
}
