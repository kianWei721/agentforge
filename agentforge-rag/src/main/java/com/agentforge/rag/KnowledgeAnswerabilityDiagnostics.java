package com.agentforge.rag;

import java.util.List;

public record KnowledgeAnswerabilityDiagnostics(
        String judgeMode,
        int candidateCount,
        int supportedCount,
        String decision,
        List<KnowledgeAnswerabilityDecision> decisions) {

    public KnowledgeAnswerabilityDiagnostics {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}
