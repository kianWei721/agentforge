package com.agentforge.rag;

import java.util.List;

public record KnowledgeEvaluationVariantReport(
        String variant,
        String retrievalMode,
        int answerableCases,
        int noAnswerCases,
        double recallAt1,
        double recallAt3,
        double recallAt5,
        double meanReciprocalRank,
        double noAnswerAccuracy,
        double overallAccuracy,
        List<KnowledgeEvaluationCaseResult> cases) {

    public KnowledgeEvaluationVariantReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
