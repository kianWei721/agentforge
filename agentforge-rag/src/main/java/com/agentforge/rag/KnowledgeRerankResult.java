package com.agentforge.rag;

import java.util.List;

public record KnowledgeRerankResult(
        List<KnowledgeSearchHit> hits,
        boolean noAnswer,
        Double noAnswerScore,
        KnowledgeRerankDiagnostics diagnostics) {

    public KnowledgeRerankResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public KnowledgeRerankResult(
            List<KnowledgeSearchHit> hits,
            boolean noAnswer,
            Double noAnswerScore) {
        this(hits, noAnswer, noAnswerScore, null);
    }
}
