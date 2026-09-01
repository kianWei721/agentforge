package com.agentforge.rag;

import java.util.List;

public record KnowledgeRerankResult(
        List<KnowledgeSearchHit> hits,
        boolean noAnswer,
        Double noAnswerScore) {

    public KnowledgeRerankResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
