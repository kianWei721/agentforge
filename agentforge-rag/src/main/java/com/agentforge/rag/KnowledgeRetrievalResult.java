package com.agentforge.rag;

import java.util.List;

public record KnowledgeRetrievalResult(
        List<KnowledgeSearchHit> hits,
        KnowledgeRerankDiagnostics rerankDiagnostics,
        KnowledgeAnswerabilityDiagnostics answerabilityDiagnostics,
        KnowledgeQueryRewriteDiagnostics queryRewriteDiagnostics,
        KnowledgeAccessDiagnostics accessDiagnostics,
        KnowledgeModelCallDiagnostics modelCallDiagnostics) {

    public KnowledgeRetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public KnowledgeRetrievalResult(
            List<KnowledgeSearchHit> hits,
            KnowledgeRerankDiagnostics rerankDiagnostics,
            KnowledgeAnswerabilityDiagnostics answerabilityDiagnostics,
            KnowledgeQueryRewriteDiagnostics queryRewriteDiagnostics,
            KnowledgeAccessDiagnostics accessDiagnostics) {
        this(hits, rerankDiagnostics, answerabilityDiagnostics, queryRewriteDiagnostics, accessDiagnostics, null);
    }

    public KnowledgeRetrievalResult(
            List<KnowledgeSearchHit> hits,
            KnowledgeRerankDiagnostics rerankDiagnostics,
            KnowledgeAnswerabilityDiagnostics answerabilityDiagnostics,
            KnowledgeQueryRewriteDiagnostics queryRewriteDiagnostics) {
        this(hits, rerankDiagnostics, answerabilityDiagnostics, queryRewriteDiagnostics, null, null);
    }

    public KnowledgeRetrievalResult(
            List<KnowledgeSearchHit> hits,
            KnowledgeRerankDiagnostics rerankDiagnostics,
            KnowledgeAnswerabilityDiagnostics answerabilityDiagnostics) {
        this(hits, rerankDiagnostics, answerabilityDiagnostics, null, null, null);
    }

    public KnowledgeRetrievalResult(
            List<KnowledgeSearchHit> hits,
            KnowledgeRerankDiagnostics rerankDiagnostics) {
        this(hits, rerankDiagnostics, null, null, null, null);
    }

    public static KnowledgeRetrievalResult of(List<KnowledgeSearchHit> hits) {
        return new KnowledgeRetrievalResult(hits, null, null, null, null, null);
    }
}
