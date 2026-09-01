package com.agentforge.rag;

import java.util.List;

public record KnowledgeAnswerabilityResult(
        List<KnowledgeSearchHit> hits,
        KnowledgeAnswerabilityDiagnostics diagnostics) {

    public KnowledgeAnswerabilityResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
