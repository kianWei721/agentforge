package com.agentforge.rag;

import java.util.List;

public record KnowledgeCandidateEvaluationVariantReport(
        String variant,
        String queryMode,
        String retrievalStrategy,
        Integer rrfK,
        int answerableCases,
        double recallAt1,
        double recallAt3,
        double recallAt5,
        double meanReciprocalRank,
        List<KnowledgeCandidateEvaluationCaseResult> cases) {

    public KnowledgeCandidateEvaluationVariantReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
